package org.lspstore.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.di.AppContainer
import org.lspstore.ui.toUserMessage
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
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

    init {
        viewModelScope.launch {
            libraryRepository.links.collect { links = it; rebuild() }
        }
        load(force = false)
    }

    fun refresh() = load(force = true)

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
                    _state.update { it.copy(isLoading = false, isRefreshing = false) }
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

        val recentlyUpdated = index
            .sortedByDescending { it.updatedAt ?: Instant.EPOCH }
            .take(SECTION_SIZE)
        val mostStarred = index
            .filter { it.stars > 0 }
            .sortedByDescending { it.stars }
            .take(SECTION_SIZE)

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
                .sortedByDescending { it.updatedAt ?: Instant.EPOCH }
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
        private const val SECTION_SIZE = 15

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
