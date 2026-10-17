package org.lspstore.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.data.repository.SettingsRepositoryImpl
import org.lspstore.data.work.UpdateScheduler
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.ProxyConfig
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.TranslationSettings
import org.lspstore.domain.model.UpdateChannel

data class SettingsUiState(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val sortOrder: SortOrder = SortOrder.UPDATED,
    val backgroundCheck: Boolean = true,
    val intervalHours: Long = SettingsRepositoryImpl.DEFAULT_INTERVAL_HOURS,
    val autoUpdate: Boolean = false,
    val rootInstall: Boolean = false,
    val rootAvailable: Boolean? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
    val proxy: ProxyConfig = ProxyConfig(),
    val translation: TranslationSettings = TranslationSettings(),
    val proxyTesting: Boolean = false,
    val downloadsSize: Long = 0,
    val message: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val moduleRepository = container.moduleRepository
    private val installer = container.installer
    private val appContext = container.appContext

    private val _state = MutableStateFlow(SettingsUiState(downloadsSize = installer.downloadsSizeBytes()))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel -> _state.update { it.copy(channel = channel) } }
        }
        viewModelScope.launch {
            settingsRepository.sortOrder.collect { order -> _state.update { it.copy(sortOrder = order) } }
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
            settingsRepository.proxyConfig.collect { config -> _state.update { it.copy(proxy = config) } }
        }
        viewModelScope.launch {
            settingsRepository.translationSettings.collect { settings ->
                _state.update { it.copy(translation = settings) }
            }
        }
    }

    fun saveTranslation(settings: TranslationSettings) {
        viewModelScope.launch { settingsRepository.setTranslationSettings(settings) }
    }

    fun saveProxy(config: ProxyConfig) {
        viewModelScope.launch { settingsRepository.setProxyConfig(config) }
    }

    fun testProxy() {
        if (_state.value.proxyTesting) return
        viewModelScope.launch {
            _state.update { it.copy(proxyTesting = true) }
            container.proxyTester.test()
                .onSuccess { millis ->
                    _state.update {
                        it.copy(
                            proxyTesting = false,
                            message = appContext.getString(R.string.settings_proxy_ok, millis.toInt()),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            proxyTesting = false,
                            message = appContext.getString(
                                R.string.settings_proxy_failed,
                                error.message ?: error::class.simpleName.orEmpty(),
                            ),
                        )
                    }
                }
        }
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

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
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

    fun clearCache() {
        viewModelScope.launch {
            moduleRepository.clearCache()
            installer.clearDownloads()
            _state.update {
                it.copy(downloadsSize = installer.downloadsSizeBytes(), message = appContext.getString(R.string.settings_cache_cleared))
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val payload = container.libraryRepository.exportJson()
                appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                } ?: error(appContext.getString(R.string.settings_cannot_open))
            }.onSuccess {
                _state.update { it.copy(message = appContext.getString(R.string.settings_exported)) }
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: appContext.getString(R.string.settings_export_failed)) }
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = appContext.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error(appContext.getString(R.string.settings_cannot_open))
                container.libraryRepository.importJson(text)
            }.onSuccess { result ->
                val message = result.error ?: appContext.getString(
                    R.string.settings_imported,
                    result.favourites,
                    result.hidden,
                    result.skipped,
                )
                _state.update { it.copy(message = message) }
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: appContext.getString(R.string.settings_import_failed)) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
