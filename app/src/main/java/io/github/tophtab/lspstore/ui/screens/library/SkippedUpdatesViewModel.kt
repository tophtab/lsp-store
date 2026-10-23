package io.github.tophtab.lspstore.ui.screens.library

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
import io.github.tophtab.lspstore.domain.model.SkippedVersion

/**
 * One row in the "Ignored updates" screen. A row is either a module whose update
 * checks were turned off, or one or more versions the user skipped.
 */
data class SkippedItem(
    val packageName: String,
    val title: String,
    val skippedVersion: String?,
    val latestVersion: String?,
    val ignoredNames: List<String>,
    val skippedNames: List<String>,
) {
    val isModuleIgnored: Boolean get() = ignoredNames.isNotEmpty()
}

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
    private var skipped: Map<String, SkippedVersion> = emptyMap()
    private var ignored: Set<String> = emptySet()
    private var links: Map<String, String> = emptyMap()
    private var preReleases: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { value -> skipped = value; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.ignoredUpdates.collect { value -> ignored = value; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value -> links = value; rebuild() }
        }
        viewModelScope.launch {
            libraryRepository.preReleaseModules.collect { value -> preReleases = value; rebuild() }
        }
        load()
    }

    /**
     * Restores a row: turns update checks back on for an ignored module and
     * clears any versions skipped for it.
     */
    fun restore(item: SkippedItem) {
        viewModelScope.launch {
            item.ignoredNames.forEach { name ->
                libraryRepository.setUpdateCheckEnabled(name, enabled = true)
            }
            item.skippedNames.forEach { name ->
                libraryRepository.unskipUpdate(name)
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            libraryRepository.clearSkippedUpdates()
            libraryRepository.clearIgnoredUpdates()
        }
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
                    _state.update { it.copy(isLoading = false, error = error.toUserMessage(container.appContext, R.string.library_load_failed)) }
                }
        }
    }

    private class ItemBuilder(
        val packageName: String,
        val title: String,
        val latestVersion: String?,
    ) {
        val ignoredNames = mutableListOf<String>()
        val skippedNames = mutableListOf<String>()
        var skippedVersion: SkippedVersion? = null
    }

    private fun rebuild() {
        if (index.isEmpty() && skipped.isEmpty() && ignored.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        fun moduleFor(name: String): Module? = byPackage[name] ?: links[name]?.let(byPackage::get)

        val builders = LinkedHashMap<String, ItemBuilder>()

        fun builderFor(name: String): ItemBuilder {
            val module = moduleFor(name)
            val key = module?.packageName ?: name
            return builders.getOrPut(key) {
                ItemBuilder(
                    packageName = key,
                    title = module?.title ?: name,
                    latestVersion = module
                        ?.latestFor(module.packageName in preReleases || name in preReleases)
                        ?.raw,
                )
            }
        }

        ignored.forEach { name ->
            builderFor(name).ignoredNames += name
        }
        skipped.forEach { (name, version) ->
            val builder = builderFor(name)
            builder.skippedNames += name
            val current = builder.skippedVersion?.versionCode ?: Long.MIN_VALUE
            val candidate = version.versionCode ?: Long.MIN_VALUE
            if (builder.skippedVersion == null || candidate > current) {
                builder.skippedVersion = version
            }
        }

        val items = builders.values
            .map { builder ->
                SkippedItem(
                    packageName = builder.packageName,
                    title = builder.title,
                    skippedVersion = builder.skippedVersion?.versionName,
                    latestVersion = displayVersion(builder.latestVersion),
                    ignoredNames = builder.ignoredNames,
                    skippedNames = builder.skippedNames,
                )
            }
            .sortedBy { it.title.lowercase() }
        _state.update { it.copy(items = items) }
    }

    private fun displayVersion(raw: String?): String? =
        raw?.let { it.substringAfter('-', "").ifBlank { it } }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SkippedUpdatesViewModel(container) }
        }
    }
}
