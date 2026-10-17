package org.lspstore.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.Module

enum class LibraryKind(val title: String) {
    FAVOURITES("Favourites"),
    RECENTLY_VIEWED("Recently viewed"),
    HIDDEN("Hidden modules"),
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

    init {
        when (kind) {
            LibraryKind.FAVOURITES -> viewModelScope.launch {
                libraryRepository.favourites.collect { names = it; rebuild() }
            }
            LibraryKind.RECENTLY_VIEWED -> viewModelScope.launch {
                libraryRepository.recentlyViewed.collect { names = it; rebuild() }
            }
            LibraryKind.HIDDEN -> viewModelScope.launch {
                libraryRepository.hidden.collect { names = it.toList(); rebuild() }
            }
        }
        load()
    }

    fun remove(packageName: String) {
        viewModelScope.launch {
            when (kind) {
                LibraryKind.FAVOURITES -> libraryRepository.toggleFavourite(packageName)
                LibraryKind.HIDDEN -> libraryRepository.toggleHidden(packageName)
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
        viewModelScope.launch {
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
                _state.update { it.copy(isLoading = false, installedPackages = installed) }
                rebuild()
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = error.message ?: "Failed to load") }
            }
        }
    }

    private fun rebuild() {
        if (index.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        _state.update { it.copy(modules = names.mapNotNull { name -> byPackage[name] }) }
    }

    companion object {
        fun factory(container: AppContainer, kind: LibraryKind) = viewModelFactory {
            initializer { LibraryListViewModel(container, kind) }
        }
    }
}
