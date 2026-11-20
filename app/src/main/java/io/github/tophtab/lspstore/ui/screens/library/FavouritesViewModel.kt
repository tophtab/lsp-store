package io.github.tophtab.lspstore.ui.screens.library

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
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.domain.model.HiddenModules
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.installedCopyForRepo
import io.github.tophtab.lspstore.domain.model.repoModuleFor
import kotlin.coroutines.cancellation.CancellationException

data class FavouritesUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val modules: List<Module> = emptyList(),
    val installedCopies: Map<String, InstalledModule> = emptyMap(),
)

class FavouritesViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(FavouritesUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var byPackage: Map<String, Module> = emptyMap()
    private var names: List<String> = emptyList()
    private var installedRaw: List<InstalledModule> = emptyList()
    private var links: Map<String, String> = emptyMap()
    private var hidden: HiddenModules = HiddenModules()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            libraryRepository.favourites.collect { names = it; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value ->
                links = value
                refreshInstalledBadges()
            }
        }
        viewModelScope.launch {
            // An ignored module stays in the stored list so ignoring is
            // reversible, but it is not shown on any browsing screen. Keyword
            // matches hide the same way.
            libraryRepository.hiddenModules.collect { value ->
                hidden = value
                rebuild()
            }
        }
        load()
    }

    fun remove(packageName: String) {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    fun refresh() = load()

    private fun load() {
        // A pull-to-refresh while the previous load is still running replaces
        // it: both would write the index and the installed set.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = index.isEmpty(), error = null) }
            runCatching {
                val modules = moduleRepository.getIndex(force = false)
                val installed = installedRepository.getInstalledModules()
                    .filter { it.isXposedModule }
                modules to installed
            }.onSuccess { (modules, installed) ->
                index = modules
                byPackage = modules.associateBy { it.packageName }
                installedRaw = installed
                _state.update { it.copy(isLoading = false) }
                refreshInstalledBadges()
                rebuild()
            }.onFailure { error ->
                // A cancelled load was replaced by the one that cancelled it.
                if (error is CancellationException) return@onFailure
                _state.update { it.copy(isLoading = false, error = error.toUserMessage(container.appContext, R.string.library_load_failed)) }
            }
        }
    }

    private fun rebuild() {
        _state.update {
            it.copy(
                modules = names
                    .mapNotNull { name -> byPackage[name] }
                    .filterNot(hidden::hides),
            )
        }
    }

    private fun refreshInstalledBadges() {
        val resolved = installedRaw
            .mapNotNull { installed ->
                repoModuleFor(installed.packageName, byPackage, links)?.packageName?.let { it to installed }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (repo, copies) -> installedCopyForRepo(repo, copies, links)!! }
        _state.update { it.copy(installedCopies = resolved) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { FavouritesViewModel(container) }
        }
    }
}
