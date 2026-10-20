package org.lspstore.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.data.repository.SettingsRepository
import org.lspstore.data.work.UpdateCheckWorker
import org.lspstore.data.work.UpdateScheduler
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.TranslationSettings
import org.lspstore.domain.model.UpdateChannel

data class SettingsUiState(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val backgroundCheck: Boolean = true,
    val intervalHours: Long = SettingsRepository.DEFAULT_INTERVAL_HOURS,
    val autoUpdate: Boolean = false,
    val rootInstall: Boolean = false,
    val rootAvailable: Boolean? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val translation: TranslationSettings = TranslationSettings(),
    val checkingNow: Boolean = false,
    val downloadsSize: Long = 0,
    val indexCacheSize: Long = 0,
    val message: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val moduleRepository = container.moduleRepository
    private val installer = container.installer
    private val appContext = container.appContext

    private val _state = MutableStateFlow(
        SettingsUiState(
            downloadsSize = installer.downloadsSizeBytes(),
            indexCacheSize = moduleRepository.cacheSizeBytes(),
        ),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel -> _state.update { it.copy(channel = channel) } }
        }
        viewModelScope.launch {
            settingsRepository.backgroundCheckEnabled.collect { enabled ->
                _state.update { it.copy(backgroundCheck = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.backgroundCheckIntervalHours.collect { hours ->
                _state.update { it.copy(intervalHours = hours) }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoUpdate.collect { enabled -> _state.update { it.copy(autoUpdate = enabled) } }
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
            settingsRepository.translationSettings.collect { settings ->
                _state.update { it.copy(translation = settings) }
            }
        }
        viewModelScope.launch {
            var lastWorkId = java.util.UUID(0L, 0L)
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(UpdateScheduler.CHECK_NOW_WORK_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                            _state.update { it.copy(checkingNow = true) }
                        WorkInfo.State.SUCCEEDED -> {
                            // Ignore the previous session's finished work on entry.
                            if (_state.value.checkingNow && info.id != lastWorkId) {
                                lastWorkId = info.id
                                val updates = info.outputData.getInt(UpdateCheckWorker.KEY_UPDATES, 0)
                                val message = if (updates > 0) {
                                    appContext.getString(R.string.settings_check_now_found, updates)
                                } else {
                                    appContext.getString(R.string.settings_check_now_none)
                                }
                                _state.update { it.copy(checkingNow = false, message = message) }
                            }
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                            _state.update {
                                it.copy(
                                    checkingNow = false,
                                    message = appContext.getString(R.string.settings_check_now_failed),
                                )
                            }
                    }
                }
        }
    }

    fun checkNow() {
        if (_state.value.checkingNow) return
        _state.update { it.copy(checkingNow = true, message = null) }
        UpdateScheduler.checkNow(appContext)
    }

    fun saveTranslation(settings: TranslationSettings) {
        viewModelScope.launch { settingsRepository.setTranslationSettings(settings) }
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

    fun setChannel(channel: UpdateChannel) {
        viewModelScope.launch { settingsRepository.setUpdateChannel(channel) }
    }

    fun setBackgroundCheck(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundCheckEnabled(enabled)
            if (enabled) {
                UpdateScheduler.schedule(appContext, _state.value.intervalHours)
            } else {
                UpdateScheduler.cancel(appContext)
            }
        }
    }

    fun setIntervalHours(hours: Long) {
        viewModelScope.launch {
            settingsRepository.setBackgroundCheckIntervalHours(hours)
            if (_state.value.backgroundCheck) {
                UpdateScheduler.schedule(appContext, hours)
            }
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val available = installer.isRootAvailable()
                _state.update { it.copy(rootAvailable = available) }
                if (!available) {
                    _state.update { it.copy(message = appContext.getString(R.string.settings_auto_update_needs_root)) }
                    return@launch
                }
            }
            settingsRepository.setAutoUpdate(enabled)
        }
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
            _state.update {
                it.copy(
                    downloadsSize = installer.downloadsSizeBytes(),
                    message = appContext.getString(R.string.settings_apks_cleared),
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
