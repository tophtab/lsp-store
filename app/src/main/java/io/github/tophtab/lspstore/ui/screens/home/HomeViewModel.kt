package io.github.tophtab.lspstore.ui.screens.home

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
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import java.time.Instant

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
     * Which shelf the user picked. Kept here rather than in the screen's
     * `remember`: it decides which list the scroll position belongs to, so it
     * has to survive the trip to a module page along with the position itself.
     */
    val selectedTabIndex: Int = 0,
    /**
     * Bumped whenever a load finishes, silent startup refresh included, so the
     * list jumps back to the top: the newest modules are the reason the refresh
     * exists, and leaving the viewport where it was made it look like a no-op.
     */
    val refreshTick: Int = 0,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()
    private var links: Map<String, String> = emptyMap()
    private var loadJob: Job? = null
    private val refreshScrollGate = RefreshScrollGate()

    init {
        viewModelScope.launch {
            libraryRepository.links.collect { links = it; rebuild() }
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

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        rebuild()
    }

    fun clearSearch() {
        onSearchQueryChange("")
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var paintedFromCache = false

            // 1. Paint the index saved by a previous session immediately.
            if (index.isEmpty()) {
                val cachedIndex = moduleRepository.getCachedIndex()
                if (cachedIndex != null) {
                    index = cachedIndex
                    installedRepository.getCachedInstalledModules()?.let { installed = it }
                    paintedFromCache = true
                    _state.update {
                        it.copy(
                            isLoading = false,
                            // Automatic startup refresh is silent; only a user
                            // pull-to-refresh shows the spinner.
                            isRefreshing = force,
                            error = null,
                        )
                    }
                    rebuild()
                }
            }

            if (!paintedFromCache) {
                _state.update {
                    it.copy(
                        isLoading = index.isEmpty(),
                        isRefreshing = force && index.isNotEmpty(),
                        error = null,
                    )
                }
            }

            // 2. Scan installed packages and refresh the repository in parallel.
            // PackageManager is local, but scanning hundreds of packages can still
            // take a while; the network should not wait behind it.
            launch {
                runCatching { installedRepository.getInstalledModules() }
                    .onSuccess { installedModules ->
                        installed = installedModules
                        rebuild()
                    }
            }

            // 3. Refresh the repository in the background of the cached UI.
            // The first screen of the session performs the fetch; later screens
            // reuse the refreshed in-memory index. Pull-to-refresh forces it.
            runCatching { moduleRepository.refreshIndexIfNeeded(force) }
                .onSuccess { modules ->
                    index = modules
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            refreshTick = it.refreshTick + 1,
                        )
                    }
                    rebuild()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            // Keep showing cached content when the refresh fails.
                            error = if (index.isEmpty()) {
                                error.toUserMessage(container.appContext, R.string.home_load_failed)
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    private fun rebuild() {
        if (index.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val installedPackages = installed
            .filter { it.isXposedModule }
            .mapNotNull { installedModule ->
                val repoPackage = links[installedModule.packageName]
                when {
                    installedModule.packageName in byPackage -> installedModule.packageName
                    repoPackage != null && repoPackage in byPackage -> repoPackage
                    else -> null
                }
            }
            .toSet()

        // Both shelves list the whole index, not a top-N cut: the repository is
        // the only place to browse modules, so a fixed limit hid the rest.
        // Zero-star modules stay at the end of the "Most starred" list instead
        // of being dropped.
        val recentlyUpdated = index
            .sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH }
        val mostStarred = index
            .sortedWith(compareByDescending<Module> { it.stars }.thenBy { it.title })

        val query = _state.value.searchQuery.trim()
        val searchResults = if (query.isBlank()) {
            emptyList()
        } else {
            index
                .filter { module ->
                    module.title.contains(query, ignoreCase = true) ||
                        module.packageName.contains(query, ignoreCase = true) ||
                        module.summary?.contains(query, ignoreCase = true) == true
                }
                .sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH }
        }

        _state.update {
            it.copy(
                recentlyUpdated = recentlyUpdated,
                mostStarred = mostStarred,
                installedPackages = installedPackages,
                searchResults = searchResults,
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
