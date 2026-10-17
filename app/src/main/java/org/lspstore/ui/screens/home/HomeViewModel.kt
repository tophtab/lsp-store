package org.lspstore.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.usecase.UpdateChecker
import java.time.Instant

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updateCount: Int = 0,
    val recentlyUpdated: List<Module> = emptyList(),
    val mostStarred: List<Module> = emptyList(),
    val betaBuilds: List<Module> = emptyList(),
    val favourites: List<Module> = emptyList(),
    val recentlyViewed: List<Module> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
    val channel: UpdateChannel = UpdateChannel.STABLE,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var favourites: List<String> = emptyList()
    private var recentlyViewed: List<String> = emptyList()
    private var skipped: Map<String, Long> = emptyMap()
    private var channel: UpdateChannel = UpdateChannel.STABLE

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel = it; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.favourites.collect { favourites = it; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.recentlyViewed.collect { recentlyViewed = it; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.hidden.collect { hidden = it; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { skipped = it; rebuild() }
        }
        load(force = false)
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = it.recentlyUpdated.isEmpty(),
                    isRefreshing = it.recentlyUpdated.isNotEmpty(),
                    error = null,
                )
            }
            runCatching {
                val modules = moduleRepository.getIndex(force)
                val installedModules = installedRepository.getInstalledModules()
                modules to installedModules
            }.onSuccess { (modules, installedModules) ->
                index = modules
                installed = installedModules
                _state.update { it.copy(isLoading = false, isRefreshing = false) }
                rebuild()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: container.appContext.getString(R.string.home_load_failed),
                    )
                }
            }
        }
    }

    private fun rebuild() {
        if (index.isEmpty()) return
        val visible = index.filterNot { it.packageName in hidden }
        val byPackage = visible.associateBy { it.packageName }
        val installedPackages = installed.filter { it.isXposedModule }.map { it.packageName }.toSet()
        val updates = UpdateChecker.findUpdates(installed, index, channel, skipped, hidden)

        val recentlyUpdated = visible
            .sortedByDescending { it.updatedAt ?: Instant.EPOCH }
            .take(SECTION_SIZE)
        val mostStarred = visible
            .filter { it.stars > 0 }
            .sortedByDescending { it.stars }
            .take(SECTION_SIZE)
        val betaBuilds = visible
            .filter { it.latestBetaRelease != null }
            .sortedByDescending { it.updatedAt ?: Instant.EPOCH }
            .take(SECTION_SIZE)

        _state.update {
            it.copy(
                updateCount = updates.size,
                recentlyUpdated = recentlyUpdated,
                mostStarred = mostStarred,
                betaBuilds = betaBuilds,
                favourites = favourites.mapNotNull { name -> byPackage[name] },
                recentlyViewed = recentlyViewed.mapNotNull { name -> byPackage[name] },
                installedPackages = installedPackages,
                channel = channel,
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
