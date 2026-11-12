package io.github.tophtab.lspstore.ui.screens.library

import androidx.annotation.StringRes
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
import io.github.tophtab.lspstore.domain.model.repoModuleFor
import kotlin.coroutines.cancellation.CancellationException

enum class LibraryKind(
    @param:StringRes val titleRes: Int,
    @param:StringRes val emptyRes: Int,
) {
    FAVOURITES(R.string.library_favourites, R.string.library_empty_favourites),
    RECENTLY_VIEWED(R.string.library_recently_viewed, R.string.library_empty_recent),
}

data class LibraryUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val modules: List<Module> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
)

class LibraryListViewModel(
    private val container: AppContainer,
    private val kind: LibraryKind,
) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var names: List<String> = emptyList()
    private var installedRaw: Set<String> = emptySet()
    private var links: Map<String, String> = emptyMap()
    private var hidden: HiddenModules = HiddenModules()
    private var loadJob: Job? = null

    init {
        when (kind) {
            LibraryKind.FAVOURITES -> viewModelScope.launch {
                libraryRepository.favourites.collect { names = it; rebuild() }
            }
            LibraryKind.RECENTLY_VIEWED -> viewModelScope.launch {
                libraryRepository.recentlyViewed.collect { names = it; rebuild() }
            }
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
        viewModelScope.launch {
            when (kind) {
                LibraryKind.FAVOURITES -> libraryRepository.toggleFavourite(packageName)
                LibraryKind.RECENTLY_VIEWED -> Unit
            }
        }
    }

    fun clearAll() {
        if (kind == LibraryKind.RECENTLY_VIEWED) {
            viewModelScope.launch { libraryRepository.clearRecentlyViewed() }
        }
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
                    .map { it.packageName }
                    .toSet()
                modules to installed
            }.onSuccess { (modules, installed) ->
                index = modules
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
        if (index.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        _state.update {
            it.copy(
                modules = names
                    .mapNotNull { name -> byPackage[name] }
                    .filterNot(hidden::hides),
            )
        }
    }

    private fun refreshInstalledBadges() {
        if (index.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val resolved = installedRaw
            .mapNotNull { repoModuleFor(it, byPackage, links)?.packageName }
            .toSet()
        _state.update { it.copy(installedPackages = resolved) }
    }

    companion object {
        fun factory(container: AppContainer, kind: LibraryKind) = viewModelFactory {
            initializer { LibraryListViewModel(container, kind) }
        }
    }
}
