package io.github.tophtab.lspstore.ui.screens.author

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
import java.time.Instant

data class AuthorUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val author: String = "",
    val modules: List<Module> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
)

class AuthorViewModel(
    private val container: AppContainer,
    private val author: String,
) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(AuthorUiState(author = author))
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installedPackages: Set<String> = emptySet()
    private var ignoredModules: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            libraryRepository.ignoredModules.collect { value ->
                ignoredModules = value
                applyIndex()
            }
        }
        load()
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
                installedPackages = installed
                _state.update { it.copy(isLoading = false) }
                applyIndex()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.toUserMessage(container.appContext, R.string.author_load_failed),
                    )
                }
            }
        }
    }

    /** The author's shelf, minus modules the user chose to ignore. */
    private fun applyIndex() {
        val mine = index
            .filter { it.contributor.equals(author, ignoreCase = true) }
            .filterNot { it.packageName in ignoredModules }
            .sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH }
        _state.update {
            it.copy(modules = mine, installedPackages = installedPackages)
        }
    }

    companion object {
        fun factory(container: AppContainer, author: String) = viewModelFactory {
            initializer { AuthorViewModel(container, author) }
        }
    }
}
