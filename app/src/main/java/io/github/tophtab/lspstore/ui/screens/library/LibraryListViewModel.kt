package io.github.tophtab.lspstore.ui.screens.library

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.domain.model.Module

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
                installedRaw = installed
                _state.update { it.copy(isLoading = false) }
                refreshInstalledBadges()
                rebuild()
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = error.toUserMessage(container.appContext, R.string.library_load_failed)) }
            }
        }
    }

    private fun rebuild() {
        if (index.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        _state.update { it.copy(modules = names.mapNotNull { name -> byPackage[name] }) }
    }

    private fun refreshInstalledBadges() {
        if (index.isEmpty()) return
        val repoPackages = index.map { it.packageName }.toSet()
        val resolved = installedRaw.mapNotNull { installedPackage ->
            val repoPackage = links[installedPackage]
            when {
                installedPackage in repoPackages -> installedPackage
                repoPackage != null && repoPackage in repoPackages -> repoPackage
                else -> null
            }
        }.toSet()
        _state.update { it.copy(installedPackages = resolved) }
    }

    companion object {
        fun factory(container: AppContainer, kind: LibraryKind) = viewModelFactory {
            initializer { LibraryListViewModel(container, kind) }
        }
    }
}
