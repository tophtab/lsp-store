package org.lspstore.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.data.install.DownloadStatus
import org.lspstore.data.install.DownloadTask
import org.lspstore.data.install.InstallOutcome
import org.lspstore.data.repository.InstalledModulesRepositoryImpl
import org.lspstore.data.repository.LibraryRepositoryImpl
import org.lspstore.data.repository.ModuleRepositoryImpl
import org.lspstore.data.repository.SettingsRepositoryImpl
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.ApkVerification
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateChannel
import java.io.File

data class VariantPicker(
    val release: Release,
    val variants: List<ReleaseAsset>,
)

data class ModuleDetailUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val detail: ModuleDetail? = null,
    val installed: InstalledModule? = null,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val isFavourite: Boolean = false,
    val isHidden: Boolean = false,
    val skippedVersionCode: Long? = null,
    val isBusy: Boolean = false,
    val activeDownload: DownloadTask? = null,
    val message: String? = null,
    val pendingMismatch: ApkVerification.Mismatch? = null,
    val translatedSummary: String? = null,
    val isTranslating: Boolean = false,
    val variantPicker: VariantPicker? = null,
) {
    val latestVersionCode: Long?
        get() = detail?.module?.latestFor(channel)?.versionCode

    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val latest = detail?.module?.latestFor(channel) ?: return false
            return latest.isNewerThan(installed.versionCode, installed.versionName)
        }

    val isUpdateSkipped: Boolean
        get() = skippedVersionCode != null && skippedVersionCode == latestVersionCode

    val downloadProgress: Float
        get() = activeDownload?.progress ?: 0f
}

class ModuleDetailViewModel(
    private val container: AppContainer,
    private val packageName: String,
) : ViewModel() {

    private val moduleRepository: ModuleRepositoryImpl = container.moduleRepository
    private val installedRepository: InstalledModulesRepositoryImpl = container.installedRepository
    private val settingsRepository: SettingsRepositoryImpl = container.settingsRepository
    private val libraryRepository: LibraryRepositoryImpl = container.libraryRepository
    private val installer = container.installer
    private val downloadManager = container.downloadManager
    private val translationRepository = container.translationRepository

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
            libraryRepository.favourites.collect { favourites ->
                _state.update { it.copy(isFavourite = packageName in favourites) }
            }
        }
        viewModelScope.launch {
            libraryRepository.hidden.collect { hidden ->
                _state.update { it.copy(isHidden = packageName in hidden) }
            }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { skipped ->
                _state.update { it.copy(skippedVersionCode = skipped[packageName]) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.detail == null, error = null, translatedSummary = null) }
            runCatching {
                val detail = moduleRepository.getDetail(packageName)
                val installed = installedRepository.getInstalledModule(packageName)
                detail to installed
            }.onSuccess { (detail, installed) ->
                _state.update { it.copy(isLoading = false, detail = detail, installed = installed) }
                libraryRepository.addRecentlyViewed(packageName)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: container.appContext.getString(R.string.detail_load_failed),
                    )
                }
            }
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    fun toggleHidden() {
        viewModelScope.launch { libraryRepository.toggleHidden(packageName) }
    }

    fun skipThisVersion() {
        val versionCode = _state.value.latestVersionCode ?: return
        viewModelScope.launch { libraryRepository.skipUpdate(packageName, versionCode) }
    }

    fun unskipVersion() {
        viewModelScope.launch { libraryRepository.unskipUpdate(packageName) }
    }

    fun installLatest() {
        val detail = _state.value.detail ?: return
        val release = detail.latestReleaseFor(_state.value.channel) ?: return
        val apks = release.apkAssets
        when {
            apks.isEmpty() -> Unit
            apks.size == 1 -> beginDownload(apks.first())
            else -> _state.update { it.copy(variantPicker = VariantPicker(release, apks)) }
        }
    }

    fun installAsset(asset: ReleaseAsset) = beginDownload(asset)

    fun dismissVariantPicker() {
        _state.update { it.copy(variantPicker = null) }
    }

    fun pauseDownload() {
        val task = _state.value.activeDownload ?: return
        downloadManager.pause(task.key)
    }

    fun resumeDownload() {
        val asset = pendingInstall?.second ?: return
        downloadManager.resume(asset)
    }

    fun cancelDownload() {
        val task = _state.value.activeDownload ?: return
        downloadManager.cancel(task.key)
        pendingInstall = null
        _state.update {
            it.copy(
                isBusy = false,
                activeDownload = null,
                message = container.appContext.getString(R.string.download_cancelled),
            )
        }
    }

    fun installAnyway() {
        val asset = pendingInstall?.second ?: return
        val file = pendingInstall?.first ?: return
        _state.update { it.copy(pendingMismatch = null) }
        viewModelScope.launch { performInstall(file, asset, allowSignatureMismatch = true) }
    }

    fun dismissMismatch() {
        pendingInstall = null
        _state.update { it.copy(pendingMismatch = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun translateSummary() {
        val summary = _state.value.detail?.module?.summary ?: return
        if (summary.isBlank() || _state.value.isTranslating) return
        viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, message = null) }
            translationRepository.translate(summary)
                .onSuccess { translated ->
                    _state.update { it.copy(isTranslating = false, translatedSummary = translated) }
                }
                .onFailure { error ->
                    val message = if (error.message?.contains("disabled", ignoreCase = true) == true) {
                        container.appContext.getString(R.string.detail_translation_disabled)
                    } else {
                        container.appContext.getString(R.string.detail_translate_failed)
                    }
                    _state.update { it.copy(isTranslating = false, message = message) }
                }
        }
    }

    fun clearTranslation() {
        _state.update { it.copy(translatedSummary = null) }
    }

    private fun beginDownload(asset: ReleaseAsset) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null, variantPicker = null) }
        val key = downloadManager.start(asset)
        viewModelScope.launch {
            val progressJob = launch {
                downloadManager.tasks.collect { tasks ->
                    tasks[key]?.let { task -> _state.update { it.copy(activeDownload = task) } }
                }
            }
            val task = downloadManager.tasks
                .mapNotNull { it[key] }
                .first {
                    it.status == DownloadStatus.COMPLETED ||
                        it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELLED
                }
            progressJob.cancel()

            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    val file = task.file
                    if (file == null) {
                        _state.update { it.copy(isBusy = false, activeDownload = null) }
                        return@launch
                    }
                    pendingInstall = file to asset
                    performInstall(file, asset, allowSignatureMismatch = false)
                }
                DownloadStatus.CANCELLED -> {
                    pendingInstall = null
                    _state.update {
                        it.copy(
                            isBusy = false,
                            activeDownload = null,
                            message = container.appContext.getString(R.string.download_cancelled),
                        )
                    }
                }
                else -> {
                    pendingInstall = null
                    _state.update {
                        it.copy(
                            isBusy = false,
                            activeDownload = null,
                            message = task.error ?: container.appContext.getString(R.string.detail_download_failed),
                        )
                    }
                }
            }
        }
    }

    private suspend fun performInstall(
        file: File,
        asset: ReleaseAsset,
        allowSignatureMismatch: Boolean,
    ) {
        val useRoot = settingsRepository.rootInstall.first()
        val outcome = runCatching {
            installer.install(file, packageName, useRoot, allowSignatureMismatch)
        }.getOrElse { InstallOutcome.Failure(it.message ?: "install failed") }
        when (outcome) {
            InstallOutcome.SilentSuccess -> {
                pendingInstall = null
                finishInstall(container.appContext.getString(R.string.detail_installed_apk, asset.name))
            }
            InstallOutcome.SystemInstallerLaunched -> {
                pendingInstall = null
                finishInstall(container.appContext.getString(R.string.detail_confirm_install))
            }
            is InstallOutcome.SignatureMismatch -> {
                _state.update {
                    it.copy(isBusy = false, activeDownload = null, pendingMismatch = outcome.verification)
                }
            }
            is InstallOutcome.Failure -> {
                pendingInstall = null
                finishInstall(outcome.message)
            }
        }
    }

    private suspend fun finishInstall(message: String) {
        val installed = installedRepository.getInstalledModule(packageName)
        _state.update {
            it.copy(isBusy = false, activeDownload = null, message = message, installed = installed)
        }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer { ModuleDetailViewModel(container, packageName) }
        }
    }
}
