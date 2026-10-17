package org.lspstore.ui.screens.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.data.install.InstallOutcome
import org.lspstore.R
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.usecase.UpdateChecker

data class InstalledUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updates: List<UpdateInfo> = emptyList(),
    val upToDate: List<InstalledModule> = emptyList(),
    val unknown: List<InstalledModule> = emptyList(),
    val installedCount: Int = 0,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val busyPackage: String? = null,
    val batchProgress: String? = null,
    val message: String? = null,
)

class InstalledViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository
    private val installer = container.installer

    private val _state = MutableStateFlow(InstalledUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var skipped: Map<String, Long> = emptyMap()
    private var links: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel ->
                _state.update { it.copy(channel = channel) }
                recompute()
            }
        }
        viewModelScope.launch {
            libraryRepository.hidden.collect { value -> hidden = value; recompute() }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { value -> skipped = value; recompute() }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value -> links = value; recompute() }
        }
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun update(info: UpdateInfo) {
        if (_state.value.busyPackage != null || _state.value.batchProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyPackage = info.module.packageName, message = null) }
            val outcome = performInstall(info)
            reloadInstalled()
            _state.update { it.copy(busyPackage = null, message = describe(outcome, info)) }
        }
    }

    fun updateAll() {
        val targets = _state.value.updates
        if (targets.isEmpty() || _state.value.batchProgress != null) return
        viewModelScope.launch {
            var failures = 0
            targets.forEachIndexed { i, info ->
                _state.update {
                    it.copy(
                        batchProgress = container.appContext.getString(
                            R.string.installed_batch_progress,
                            i + 1,
                            targets.size,
                            info.module.title,
                        ),
                    )
                }
                if (performInstall(info) !is InstallOutcome.SilentSuccess) failures++
            }
            reloadInstalled()
            val message = if (failures == 0) {
                container.appContext.getString(R.string.installed_updated_count, targets.size)
            } else {
                container.appContext.getString(R.string.installed_finished_failures, failures)
            }
            _state.update { it.copy(batchProgress = null, message = message) }
        }
    }

    private suspend fun performInstall(info: UpdateInfo): InstallOutcome = runCatching {
        val detail = moduleRepository.getDetail(info.module.packageName)
        val asset = detail.latestAssetFor(_state.value.channel)
            ?: return InstallOutcome.Failure(container.appContext.getString(R.string.error_no_apk_asset))
        val file = installer.download(asset)
        val useRoot = settingsRepository.rootInstall.first()
        installer.install(file, info.module.packageName, useRoot)
    }.getOrElse {
        InstallOutcome.Failure(it.message ?: container.appContext.getString(R.string.error_install_failed))
    }

    private fun describe(outcome: InstallOutcome, info: UpdateInfo): String = when (outcome) {
        InstallOutcome.SilentSuccess -> container.appContext.getString(
            R.string.installed_updated_one,
            info.module.title,
            info.latest.versionName,
        )
        InstallOutcome.SystemInstallerLaunched -> container.appContext.getString(
            R.string.installed_confirm_one,
            info.module.title,
        )
        is InstallOutcome.SignatureMismatch -> container.appContext.getString(
            R.string.installed_signature_skipped,
            info.module.title,
        )
        is InstallOutcome.Failure -> outcome.message
    }

    private suspend fun reloadInstalled() {
        installed = installedRepository.getInstalledModules()
        recompute()
    }

    private fun recompute() {
        if (index.isEmpty() && installed.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val xposed = installed.filter { it.isXposedModule }
        val updates = UpdateChecker.findUpdates(installed, index, _state.value.channel, skipped, hidden, links)
        val updating = updates.map { it.module.packageName }.toSet()

        val upToDate = xposed.filter { module ->
            module.packageName in byPackage &&
                module.packageName !in updating &&
                module.packageName !in hidden
        }
        val unknown = xposed.filter {
            it.packageName !in byPackage && links[it.packageName]?.let { link -> link in byPackage } != true
        }

        _state.update {
            it.copy(
                updates = updates,
                upToDate = upToDate,
                unknown = unknown,
                installedCount = xposed.size,
            )
        }
    }

    private fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = it.updates.isEmpty() && it.installedCount == 0,
                    isRefreshing = it.updates.isNotEmpty() || it.installedCount > 0,
                    error = null,
                )
            }
            runCatching {
                val modules = moduleRepository.getIndex(force)
                val installedModules = installedRepository.getInstalledModules()
                modules to installedModules
            }.onSuccess { (modules, installedModules) ->
                index = modules
                installed = installedModules
                _state.update { it.copy(isLoading = false, isRefreshing = false) }
                recompute()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: container.appContext.getString(R.string.installed_check_failed),
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { InstalledViewModel(container) }
        }
    }
}
