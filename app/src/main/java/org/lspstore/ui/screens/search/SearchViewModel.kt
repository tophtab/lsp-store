package org.lspstore.ui.screens.search

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
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.UpdateChannel
import java.time.Instant

data class SearchUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.UPDATED,
    val selectedChannel: UpdateChannel = UpdateChannel.STABLE,
    val showFavouritesOnly: Boolean = false,
    val modules: List<Module> = emptyList(),
    val totalCount: Int = 0,
    val installedPackages: Set<String> = emptySet(),
    val favourites: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
)

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()

    private var allModules: List<Module> = emptyList()
    private var hidden: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            settingsRepository.sortOrder.collect { order ->
                _state.update { it.copy(sortOrder = order) }
                applyFilter()
            }
        }
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel ->
                _state.update { it.copy(selectedChannel = channel) }
            }
        }
        viewModelScope.launch {
            libraryRepository.favourites.collect { favourites ->
                _state.update { it.copy(favourites = favourites.toSet()) }
                applyFilter()
            }
        }
        viewModelScope.launch {
            libraryRepository.hidden.collect { value ->
                hidden = value
                applyFilter()
            }
        }
        viewModelScope.launch {
            libraryRepository.searchHistory.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        load(force = false)
    }

    fun recordQuery() {
        val query = _state.value.query
        viewModelScope.launch { libraryRepository.addSearchQuery(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearSearchHistory() }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        applyFilter()
    }

    fun onSortChange(order: SortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    fun onChannelChange(channel: UpdateChannel) {
        viewModelScope.launch { settingsRepository.setUpdateChannel(channel) }
    }

    fun toggleFavouritesOnly() {
        _state.update { it.copy(showFavouritesOnly = !it.showFavouritesOnly) }
        applyFilter()
    }

    fun toggleFavourite(packageName: String) {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = it.modules.isEmpty(),
                    isRefreshing = it.modules.isNotEmpty(),
                    error = null,
                )
            }
            runCatching {
                val modules = moduleRepository.getIndex(force)
                val installed = installedRepository.getInstalledModules()
                    .filter { it.isXposedModule }
                    .map { it.packageName }
                    .toSet()
                modules to installed
            }.onSuccess { (modules, installed) ->
                allModules = modules
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        installedPackages = installed,
                        totalCount = modules.size,
                    )
                }
                applyFilter()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: "Failed to load modules",
                    )
                }
            }
        }
    }

    private fun applyFilter() {
        val current = _state.value
        val query = current.query.trim()
        val filtered = allModules.asSequence()
            .filterNot { it.packageName in hidden }
            .filter { module ->
                query.isBlank() ||
                    module.title.contains(query, ignoreCase = true) ||
                    module.packageName.contains(query, ignoreCase = true) ||
                    module.summary?.contains(query, ignoreCase = true) == true
            }
            .filter { module -> !current.showFavouritesOnly || module.packageName in current.favourites }
            .toList()
        val sorted = when (current.sortOrder) {
            SortOrder.UPDATED -> filtered.sortedByDescending { it.updatedAt ?: Instant.EPOCH }
            SortOrder.STARS -> filtered.sortedByDescending { it.stars }
            SortOrder.NAME -> filtered.sortedBy { it.title.lowercase() }
        }
        _state.update { it.copy(modules = sorted) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
