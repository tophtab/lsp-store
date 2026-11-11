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
import io.github.tophtab.lspstore.domain.model.Module
import kotlin.coroutines.cancellation.CancellationException

/** One row in the "Ignored repository modules" screen. */
data class IgnoredModuleItem(
    val packageName: String,
    val title: String,
)

data class IgnoredModulesUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val items: List<IgnoredModuleItem> = emptyList(),
)

class IgnoredModulesViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(IgnoredModulesUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var ignored: Set<String> = emptySet()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            libraryRepository.ignoredModules.collect { value ->
                ignored = value
                rebuild()
            }
        }
        load()
    }

    fun restore(packageName: String) {
        viewModelScope.launch { libraryRepository.setModuleIgnored(packageName, ignored = false) }
    }

    fun clearAll() {
        viewModelScope.launch { libraryRepository.clearIgnoredModules() }
    }

    fun refresh() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = index.isEmpty(), error = null) }
            runCatching { moduleRepository.getIndex(force = false) }
                .onSuccess { modules ->
                    index = modules
                    _state.update { it.copy(isLoading = false) }
                    rebuild()
                }
                .onFailure { error ->
                    // A cancelled load was replaced by the one that cancelled it.
                    if (error is CancellationException) return@onFailure
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.toUserMessage(
                                container.appContext,
                                R.string.library_load_failed,
                            ),
                        )
                    }
                }
        }
    }

    private fun rebuild() {
        // The index supplies the display title; a module that left the
        // repository still has an entry, so the row falls back to its package
        // name and stays restorable.
        val byPackage = index.associateBy { it.packageName }
        val items = ignored.map { name ->
            IgnoredModuleItem(
                packageName = name,
                title = byPackage[name]?.title ?: name,
            )
        }
        _state.update { it.copy(items = items) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { IgnoredModulesViewModel(container) }
        }
    }
}
