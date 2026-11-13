package io.github.tophtab.lspstore.ui.screens.home

import io.github.tophtab.lspstore.ui.CatalogLoader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.ui.RefreshScrollGate
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.domain.model.HiddenModules
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.repoPackagesOf

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val recentlyUpdated: List<Module> = emptyList(),
    val mostStarred: List<Module> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<Module> = emptyList(),
    /**
     * Bumped whenever the search text changes, so a new result list starts at
     * the top instead of inheriting the offset of the previous one.
     */
    val searchTick: Int = 0,
    /**
     * Which shelf the user picked. Kept here rather than in the screen's
     * `remember`: it decides which list the scroll position belongs to, so it
     * has to survive the trip to a module page along with the position itself.
     */
    val selectedTabIndex: Int = 0,
    /**
     * Bumped whenever a load finishes, including the first load of the process.
     * A completed load is a new dataset, so the screen shows it from its first
     * item instead of keeping an offset that belonged to the previous data.
     */
    val refreshTick: Int = 0,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository
    private val catalogLoader = CatalogLoader(moduleRepository, installedRepository)

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var byPackage: Map<String, Module> = emptyMap()
    private var shelves = HomeShelves()
    private var installed: List<InstalledModule> = emptyList()
    private var links: Map<String, String> = emptyMap()
    private var hidden: HiddenModules = HiddenModules()
    private var loadJob: Job? = null
    private val refreshScrollGate = RefreshScrollGate()
    private val searchScrollGate = RefreshScrollGate()

    init {
        viewModelScope.launch {
            libraryRepository.links.collect { links = it; rebuildInstalledBadges() }
        }
        viewModelScope.launch {
            // Ignoring a module, by package or by keyword, hides it from every
            // shelf and from search, but not from the installed tab: the package
            // is still installed and still needs managing.
            libraryRepository.hiddenModules.collect { hidden = it; rebuildShelves() }
        }
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTabIndex = index.coerceAtLeast(0)) }
    }

    /**
     * Whether the finished refresh that produced the current
     * [HomeUiState.refreshTick] still owes the list a scroll back to the top.
     * Asking the view model (which outlives the composition) rather than
     * remembering the tick in the screen is what keeps a trip to a module page
     * from scrolling the list: the screen comes back as a fresh composition and
     * launches its effect with the same tick again.
     */
    fun consumeRefreshScroll(): Boolean = refreshScrollGate.consume(_state.value.refreshTick)

    /**
     * Whether the current [HomeUiState.searchTick] still owes the result list a
     * scroll back to the top. The gate lives here for the same reason as the
     * refresh one: rebuilding the screen on the way back from a module page
     * re-launches the effect with a tick it already acted on.
     */
    fun consumeSearchScroll(): Boolean = searchScrollGate.consume(_state.value.searchTick)

    fun onSearchQueryChange(query: String) {
        if (query == _state.value.searchQuery) return
        _state.update {
            it.copy(
                searchQuery = query,
                // Clearing the field reveals the ordinary shelves again; only a
                // new search owes the result list a reset.
                searchTick = if (query.isNotBlank()) it.searchTick + 1 else it.searchTick,
            )
        }
        _state.update { it.copy(searchResults = shelves.search(query)) }
    }

    fun clearSearch() {
        onSearchQueryChange("")
    }

    /**
     * Hides [packageName] from both shelves and from search. The ignored-modules
     * stream collected above rebuilds the lists, so the card disappears as soon
     * as the preference write lands.
     */
    fun ignoreModule(packageName: String) {
        viewModelScope.launch { libraryRepository.setModuleIgnored(packageName, ignored = true) }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            catalogLoader.load(
                hasIndex = index.isNotEmpty(),
                forceRefresh = force,
                forceScan = false,
                onCache = { modules, cachedInstalled ->
                    updateIndex(modules)
                    cachedInstalled?.let { installed = it }
                    rebuildInstalledBadges()
                },
                onLoading = { loading, refreshing ->
                    _state.update {
                        it.copy(isLoading = loading, isRefreshing = refreshing, error = null)
                    }
                },
                onScanned = { modules ->
                    installed = modules
                    rebuildInstalledBadges()
                },
                onRefreshed = { modules ->
                    updateIndex(modules)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                            refreshTick = it.refreshTick + 1,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = error?.toUserMessage(container.appContext, R.string.home_load_failed),
                        )
                    }
                },
            )
        }
    }

    private fun updateIndex(modules: List<Module>) {
        index = modules
        byPackage = modules.associateBy { it.packageName }
        rebuildShelves()
        rebuildInstalledBadges()
    }

    private fun rebuildShelves() {
        shelves = HomeShelves.build(index, hidden)
        _state.update {
            it.copy(
                recentlyUpdated = shelves.recentlyUpdated,
                mostStarred = shelves.mostStarred,
                searchResults = shelves.search(it.searchQuery),
            )
        }
    }

    private fun rebuildInstalledBadges() {
        val installedPackages = repoPackagesOf(installed, byPackage, links)
        _state.update { it.copy(installedPackages = installedPackages) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
