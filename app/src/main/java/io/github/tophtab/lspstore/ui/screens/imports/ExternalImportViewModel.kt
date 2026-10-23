package io.github.tophtab.lspstore.ui.screens.imports

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
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module

data class UnknownModuleItem(
    val installed: InstalledModule,
    val linkedModule: Module?,
)

data class ExternalImportUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val unknown: List<UnknownModuleItem> = emptyList(),
    val totalModules: Int = 0,
    val linkedCount: Int = 0,
    val pickerOpen: Boolean = false,
    val pickerTarget: String? = null,
    val pickerQuery: String = "",
    val pickerResults: List<Module> = emptyList(),
    val message: String? = null,
)

class ExternalImportViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val libraryRepository = container.libraryRepository
    private val appContext = container.appContext

    private val _state = MutableStateFlow(ExternalImportUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()
    private var links: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            libraryRepository.links.collect { value -> links = value; rebuild() }
        }
        load()
    }

    fun refresh() = load()

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun openPicker(installedPackage: String) {
        _state.update {
            it.copy(pickerOpen = true, pickerTarget = installedPackage, pickerQuery = "", pickerResults = index.take(50))
        }
    }

    fun closePicker() {
        _state.update { it.copy(pickerOpen = false, pickerTarget = null, pickerQuery = "", pickerResults = emptyList()) }
    }

    fun onPickerQueryChange(query: String) {
        val trimmed = query.trim()
        val results = if (trimmed.isBlank()) {
            index.take(50)
        } else {
            index.asSequence()
                .filter {
                    it.title.contains(trimmed, ignoreCase = true) ||
                        it.packageName.contains(trimmed, ignoreCase = true)
                }
                .take(50)
                .toList()
        }
        _state.update { it.copy(pickerQuery = query, pickerResults = results) }
    }

    fun link(repoPackage: String) {
        val target = _state.value.pickerTarget ?: return
        viewModelScope.launch {
            libraryRepository.linkModule(target, repoPackage)
            _state.update {
                it.copy(
                    pickerOpen = false,
                    pickerTarget = null,
                    message = appContext.getString(R.string.import_linked),
                )
            }
        }
    }

    fun unlink(installedPackage: String) {
        viewModelScope.launch {
            libraryRepository.unlinkModule(installedPackage)
            _state.update { it.copy(message = appContext.getString(R.string.import_unlinked)) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = index.isEmpty(), error = null) }
            runCatching {
                val modules = moduleRepository.getIndex(force = false)
                val installedModules = installedRepository.getInstalledModules()
                modules to installedModules
            }.onSuccess { (modules, installedModules) ->
                index = modules
                installed = installedModules
                _state.update { it.copy(isLoading = false) }
                rebuild()
            }.onFailure { error ->
                _state.update {
                    it.copy(isLoading = false, error = error.toUserMessage(appContext, R.string.import_load_failed))
                }
            }
        }
    }

    private fun rebuild() {
        if (index.isEmpty() && installed.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val unknown = installed
            .filter { it.isXposedModule && it.packageName !in byPackage }
            .map { UnknownModuleItem(installed = it, linkedModule = links[it.packageName]?.let(byPackage::get)) }
            .sortedBy { it.installed.label.lowercase() }

        _state.update {
            it.copy(
                unknown = unknown,
                totalModules = index.size,
                linkedCount = links.count { (installedPackage, repo) ->
                    installedPackage !in byPackage && repo in byPackage
                },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExternalImportViewModel(container) }
        }
    }
}
