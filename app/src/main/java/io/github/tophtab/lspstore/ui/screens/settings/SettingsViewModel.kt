package io.github.tophtab.lspstore.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.device.LauncherIcon
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.update.UpdateScheduler
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.domain.model.TranslationSettings

data class SettingsUiState(
    val rootInstall: Boolean = false,
    val rootAvailable: Boolean? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = true,
    val dynamicColor: Boolean = false,
    val translation: TranslationSettings = TranslationSettings(),
    val maxConcurrentDownloads: Int = DownloadSlots.DEFAULT_LIMIT,
    val backgroundCheck: Boolean = false,
    val checkIntervalHours: Int = UpdateScheduler.DEFAULT_INTERVAL_HOURS,
    val wifiOnly: Boolean = false,
    val autoUpdateRoot: Boolean = false,
    val hideLauncherIcon: Boolean = false,
    /** Document tree URI of the chosen download folder; blank means default. */
    val downloadLocation: String = "",
    /** Readable path of [downloadLocation], null while the default is used. */
    val downloadLocationLabel: String? = null,
    val downloadsSize: Long = 0,
    val indexCacheSize: Long = 0,
    val message: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val moduleRepository = container.moduleRepository
    private val installer = container.installer
    private val appContext = container.appContext

    /** True once the user has changed a translation field, so the load cannot overwrite it. */
    private var translationEdited = false

    private val _state = MutableStateFlow(
        SettingsUiState(indexCacheSize = moduleRepository.cacheSizeBytes()),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The export query touches MediaStore, so it stays off the main thread.
            val size = installer.downloadsSizeBytes()
            _state.update { it.copy(downloadsSize = size) }
        }
        viewModelScope.launch {
            settingsRepository.rootInstall.collect { enabled -> _state.update { it.copy(rootInstall = enabled) } }
        }
        viewModelScope.launch {
            settingsRepository.themeMode.collect { mode -> _state.update { it.copy(themeMode = mode) } }
        }
        viewModelScope.launch {
            settingsRepository.amoled.collect { enabled -> _state.update { it.copy(amoled = enabled) } }
        }
        viewModelScope.launch {
            settingsRepository.dynamicColor.collect { enabled -> _state.update { it.copy(dynamicColor = enabled) } }
        }
        viewModelScope.launch {
            // The translation fields are edited in place, so this is the only
            // time the stored settings are read into the UI state.
            val stored = settingsRepository.translationSettings.first()
            _state.update { if (translationEdited) it else it.copy(translation = stored) }
        }
        viewModelScope.launch {
            settingsRepository.maxConcurrentDownloads.collect { value ->
                _state.update { it.copy(maxConcurrentDownloads = value) }
            }
        }
        viewModelScope.launch {
            settingsRepository.backgroundCheck.collect { enabled ->
                _state.update { it.copy(backgroundCheck = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.checkIntervalHours.collect { hours ->
                _state.update { it.copy(checkIntervalHours = hours) }
            }
        }
        viewModelScope.launch {
            settingsRepository.wifiOnly.collect { enabled ->
                _state.update { it.copy(wifiOnly = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoUpdateRoot.collect { enabled ->
                _state.update { it.copy(autoUpdateRoot = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.hideLauncherIcon.collect { hidden ->
                _state.update { it.copy(hideLauncherIcon = hidden) }
            }
        }
        viewModelScope.launch {
            settingsRepository.downloadLocation.collect { uri ->
                val changed = _state.value.downloadLocation != uri
                _state.update { it.copy(downloadLocation = uri) }
                if (!changed) return@collect
                // The tree label needs a provider query, so it stays off the
                // main thread and only runs when the setting actually changed.
                val label = if (uri.isBlank()) null else container.apkExportStore.locationLabel()
                val size = installer.downloadsSizeBytes()
                _state.update {
                    it.copy(downloadLocationLabel = label, downloadsSize = size)
                }
            }
        }
    }

    /**
     * Applies one translation change and persists it at once. There is no Save
     * button: every tap and keystroke is the stored value.
     */
    fun updateTranslation(transform: (TranslationSettings) -> TranslationSettings) {
        translationEdited = true
        val updated = transform(_state.value.translation)
        _state.update { it.copy(translation = updated) }
        viewModelScope.launch { settingsRepository.setTranslationSettings(updated) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAmoled(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setMaxConcurrentDownloads(count: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(count) }
    }

    fun setRootInstall(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val available = installer.isRootAvailable()
                _state.update { it.copy(rootAvailable = available) }
                if (!available) {
                    _state.update { it.copy(message = appContext.getString(R.string.settings_root_unavailable_msg)) }
                    return@launch
                }
            }
            settingsRepository.setRootInstall(enabled)
            // A disabled root switch cannot keep silent auto-updates on: the
            // switch below would stay checked while the root path is unreachable.
            if (!enabled) settingsRepository.setAutoUpdateRoot(false)
        }
    }

    fun setBackgroundCheck(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundCheck(enabled)
            UpdateScheduler.sync(
                context = appContext,
                enabled = enabled,
                intervalHours = _state.value.checkIntervalHours,
                wifiOnly = _state.value.wifiOnly,
                reset = true,
            )
        }
    }

    fun setCheckIntervalHours(hours: Int) {
        viewModelScope.launch {
            settingsRepository.setCheckIntervalHours(hours)
            if (_state.value.backgroundCheck) {
                UpdateScheduler.sync(
                    context = appContext,
                    enabled = true,
                    intervalHours = hours,
                    wifiOnly = _state.value.wifiOnly,
                    reset = true,
                )
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWifiOnly(enabled)
            if (_state.value.backgroundCheck) {
                UpdateScheduler.sync(
                    context = appContext,
                    enabled = true,
                    intervalHours = _state.value.checkIntervalHours,
                    wifiOnly = enabled,
                    reset = true,
                )
            }
        }
    }

    fun setAutoUpdateRoot(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !_state.value.rootInstall) {
                _state.update {
                    it.copy(message = appContext.getString(R.string.settings_auto_update_needs_root))
                }
                return@launch
            }
            settingsRepository.setAutoUpdateRoot(enabled)
        }
    }

    /**
     * Hides or restores the desktop entry. The setting is persisted only after
     * the component state changed, so the two cannot disagree after a restart.
     */
    fun setHideLauncherIcon(hidden: Boolean) {
        viewModelScope.launch {
            val applied = runCatching { LauncherIcon.setHidden(appContext, hidden) }.isSuccess
            if (!applied) return@launch
            settingsRepository.setHideLauncherIcon(hidden)
            _state.update {
                it.copy(
                    hideLauncherIcon = hidden,
                    message = if (hidden) {
                        appContext.getString(R.string.settings_hide_icon_hidden)
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun clearIndexCache() {
        viewModelScope.launch {
            moduleRepository.clearCache()
            _state.update {
                it.copy(
                    indexCacheSize = moduleRepository.cacheSizeBytes(),
                    message = appContext.getString(R.string.settings_index_cleared),
                )
            }
        }
    }

    fun clearDownloadedApks() {
        viewModelScope.launch {
            installer.clearDownloads()
            val size = installer.downloadsSizeBytes()
            _state.update {
                it.copy(
                    downloadsSize = size,
                    message = appContext.getString(R.string.settings_apks_cleared),
                )
            }
        }
    }

    /**
     * Persists the folder picked in the system folder picker. The grant has to
     * be persistable, otherwise the setting would stop working after a restart;
     * a provider that refuses is reported instead of stored.
     */
    fun setDownloadLocation(uri: Uri) {
        val granted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, GRANT_FLAGS)
        }.isSuccess
        if (!granted) {
            _state.update {
                it.copy(message = appContext.getString(R.string.settings_download_location_failed))
            }
            return
        }
        val previous = _state.value.downloadLocation
        viewModelScope.launch {
            settingsRepository.setDownloadLocation(uri.toString())
            if (previous != uri.toString()) releaseGrant(previous)
        }
    }

    /** Returns to `Download/LSP_Store` and drops the old folder's grant. */
    fun resetDownloadLocation() {
        val previous = _state.value.downloadLocation
        if (previous.isBlank()) return
        viewModelScope.launch {
            settingsRepository.setDownloadLocation("")
            releaseGrant(previous)
            _state.update {
                it.copy(message = appContext.getString(R.string.settings_download_location_reset))
            }
        }
    }

    private fun releaseGrant(treeUri: String) {
        if (treeUri.isBlank()) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                treeUri.toUri(),
                GRANT_FLAGS,
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        /** Read and write are both needed to create and later delete exports. */
        private const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
