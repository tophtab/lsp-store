package org.lspstore.ui.screens.updates

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
import org.lspstore.data.repository.InstalledModulesRepositoryImpl
import org.lspstore.data.repository.ModuleRepositoryImpl
import org.lspstore.data.repository.SettingsRepositoryImpl
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.usecase.UpdateChecker

data class UpdatesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updates: List<UpdateInfo> = emptyList(),
    val installedCount: Int = 0,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val busyPackage: String? = null,
    val batchProgress: String? = null,
    val message: String? = null,
)

class UpdatesViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository: ModuleRepositoryImpl = container.moduleRepository
    private val installedRepository: InstalledModulesRepositoryImpl = container.installedRepository
    private val settingsRepository: SettingsRepositoryImpl = container.settingsRepository
    private val installer = container.installer

    private val _state = MutableStateFlow(UpdatesUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel ->
                _state.update { it.copy(channel = channel) }
                recompute()
            }
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
            val message = describe(outcome, info)
            reloadInstalled()
            _state.update { it.copy(busyPackage = null, message = message) }
        }
    }

    fun updateAll() {
        val targets = _state.value.updates
        if (targets.isEmpty() || _state.value.batchProgress != null) return
        viewModelScope.launch {
            var failures = 0
            targets.forEachIndexed { i, info ->
                _state.update { it.copy(batchProgress = "${i + 1}/${targets.size}: ${info.module.title}") }
                val outcome = performInstall(info)
                if (outcome is InstallOutcome.Failure) failures++
            }
            reloadInstalled()
            val message = if (failures == 0) {
                "Updated ${targets.size} modules"
            } else {
                "Finished with $failures failure(s)"
            }
            _state.update { it.copy(batchProgress = null, message = message) }
        }
    }

    private suspend fun performInstall(info: UpdateInfo): InstallOutcome = runCatching {
        val detail = moduleRepository.getDetail(info.module.packageName)
        val asset = detail.latestAssetFor(_state.value.channel)
            ?: return InstallOutcome.Failure("No APK asset found")
        val file = installer.download(asset)
        val useRoot = settingsRepository.rootInstall.first()
        installer.install(file, info.module.packageName, useRoot)
    }.getOrElse { InstallOutcome.Failure(it.message ?: "Install failed") }

    private fun describe(outcome: InstallOutcome, info: UpdateInfo): String = when (outcome) {
        InstallOutcome.SilentSuccess -> "Updated ${info.module.title} to ${info.latest.versionName}"
        InstallOutcome.SystemInstallerLaunched -> "Confirm the update for ${info.module.title}"
        is InstallOutcome.SignatureMismatch -> "Signature mismatch for ${info.module.title}, update skipped"
        is InstallOutcome.Failure -> outcome.message
    }

    private suspend fun reloadInstalled() {
        installed = installedRepository.getInstalledModules()
        recompute()
    }

    private fun recompute() {
        val updates = UpdateChecker.findUpdates(installed, index, _state.value.channel)
        _state.update { it.copy(updates = updates, installedCount = installed.count { module -> module.isXposedModule }) }
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
                        error = error.message ?: "Failed to check updates",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { UpdatesViewModel(container) }
        }
    }
}
