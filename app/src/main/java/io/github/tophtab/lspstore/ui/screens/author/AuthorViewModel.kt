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

    private val _state = MutableStateFlow(AuthorUiState(author = author))
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()

    init {
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
                val mine = modules
                    .filter { it.contributor.equals(author, ignoreCase = true) }
                    .sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH }
                _state.update {
                    it.copy(isLoading = false, modules = mine, installedPackages = installed)
                }
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

    companion object {
        fun factory(container: AppContainer, author: String) = viewModelFactory {
            initializer { AuthorViewModel(container, author) }
        }
    }
}
