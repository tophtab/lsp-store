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

data class SkippedItem(
    val packageName: String,
    val title: String,
    val skippedVersionCode: Long,
    val latestVersionCode: Long?,
)

data class SkippedUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val items: List<SkippedItem> = emptyList(),
)

class SkippedUpdatesViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(SkippedUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var skipped: Map<String, Long> = emptyMap()

    init {
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { value -> skipped = value; rebuild() }
        }
        load()
    }

    fun unskip(packageName: String) {
        viewModelScope.launch { libraryRepository.unskipUpdate(packageName) }
    }

    fun clearAll() {
        viewModelScope.launch { libraryRepository.clearSkippedUpdates() }
    }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = index.isEmpty(), error = null) }
            runCatching { moduleRepository.getIndex(force = false) }
                .onSuccess { modules ->
                    index = modules
                    _state.update { it.copy(isLoading = false) }
                    rebuild()
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Failed to load") }
                }
        }
    }

    private fun rebuild() {
        if (index.isEmpty() && skipped.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val items = skipped.map { (packageName, versionCode) ->
            val module = byPackage[packageName]
            SkippedItem(
                packageName = packageName,
                title = module?.title ?: packageName,
                skippedVersionCode = versionCode,
                latestVersionCode = module?.latestRelease?.versionCode,
            )
        }.sortedBy { it.title.lowercase() }
        _state.update { it.copy(items = items) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SkippedUpdatesViewModel(container) }
        }
    }
}
