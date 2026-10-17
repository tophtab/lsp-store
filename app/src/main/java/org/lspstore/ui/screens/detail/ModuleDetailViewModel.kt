package org.lspstore.ui.screens.detail

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
import org.lspstore.domain.model.ApkVerification
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateChannel
import java.io.File

data class ModuleDetailUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val detail: ModuleDetail? = null,
    val installed: InstalledModule? = null,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val isFavourite: Boolean = false,
    val isBusy: Boolean = false,
    val downloadProgress: Float? = null,
    val message: String? = null,
    val pendingMismatch: ApkVerification.Mismatch? = null,
) {
    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val latest = detail?.module?.latestFor(channel) ?: return false
            return latest.isNewerThan(installed.versionCode, installed.versionName)
        }
}

class ModuleDetailViewModel(
    private val container: AppContainer,
    private val packageName: String,
) : ViewModel() {

    private val moduleRepository: ModuleRepositoryImpl = container.moduleRepository
    private val installedRepository: InstalledModulesRepositoryImpl = container.installedRepository
    private val settingsRepository: SettingsRepositoryImpl = container.settingsRepository
    private val installer = container.installer

    private val _state = MutableStateFlow(ModuleDetailUiState())
    val state = _state.asStateFlow()

    private var pendingInstall: Pair<File, ReleaseAsset>? = null

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel ->
                _state.update { it.copy(channel = channel) }
            }
        }
        viewModelScope.launch {
            settingsRepository.favourites.collect { favourites ->
                _state.update { it.copy(isFavourite = packageName in favourites) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.detail == null, error = null) }
            runCatching {
                val detail = moduleRepository.getDetail(packageName)
                val installed = installedRepository.getInstalledModule(packageName)
                detail to installed
            }.onSuccess { (detail, installed) ->
                _state.update { it.copy(isLoading = false, detail = detail, installed = installed) }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = error.message ?: "Failed to load module") }
            }
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch { settingsRepository.toggleFavourite(packageName) }
    }

    fun installLatest() {
        val asset = latestAsset() ?: return
        install(asset)
    }

    fun installAsset(asset: ReleaseAsset) = install(asset)

    fun installAnyway() {
        val asset = pendingInstall?.second ?: return
        _state.update { it.copy(pendingMismatch = null) }
        install(asset, allowSignatureMismatch = true)
    }

    fun dismissMismatch() {
        pendingInstall = null
        _state.update { it.copy(pendingMismatch = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun latestAsset(): ReleaseAsset? =
        _state.value.detail?.latestAssetFor(_state.value.channel)

    private fun install(asset: ReleaseAsset, allowSignatureMismatch: Boolean = false) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, downloadProgress = 0f, message = null) }
            runCatching {
                val file = if (allowSignatureMismatch && pendingInstall?.second == asset) {
                    pendingInstall!!.first
                } else {
                    installer.download(asset) { downloaded, total ->
                        val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else null
                        _state.update { it.copy(downloadProgress = progress) }
                    }
                }
                pendingInstall = file to asset
                val useRoot = settingsRepository.rootInstall.first()
                installer.install(file, packageName, useRoot, allowSignatureMismatch)
            }.onSuccess { outcome ->
                when (outcome) {
                    InstallOutcome.SilentSuccess -> {
                        pendingInstall = null
                        finishInstall("Installed ${asset.name}")
                    }
                    InstallOutcome.SystemInstallerLaunched -> {
                        pendingInstall = null
                        finishInstall("Confirm the installation in the system dialog")
                    }
                    is InstallOutcome.SignatureMismatch -> {
                        _state.update {
                            it.copy(isBusy = false, downloadProgress = null, pendingMismatch = outcome.verification)
                        }
                    }
                    is InstallOutcome.Failure -> {
                        pendingInstall = null
                        finishInstall(outcome.message)
                    }
                }
            }.onFailure { error ->
                pendingInstall = null
                finishInstall(error.message ?: "Download failed")
            }
        }
    }

    private suspend fun finishInstall(message: String) {
        val installed = installedRepository.getInstalledModule(packageName)
        _state.update {
            it.copy(isBusy = false, downloadProgress = null, message = message, installed = installed)
        }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer { ModuleDetailViewModel(container, packageName) }
        }
    }
}
