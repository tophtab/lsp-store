package io.github.tophtab.lspstore.ui.screens.settings

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
import io.github.tophtab.lspstore.data.install.DownloadSlots
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
        SettingsUiState(
            downloadsSize = installer.downloadsSizeBytes(),
            indexCacheSize = moduleRepository.cacheSizeBytes(),
        ),
    )
    val state = _state.asStateFlow()

    init {
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
