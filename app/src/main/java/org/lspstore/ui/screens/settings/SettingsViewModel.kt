package org.lspstore.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.UpdateChannel

data class SettingsUiState(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val sortOrder: SortOrder = SortOrder.UPDATED,
    val autoCheck: Boolean = true,
    val rootInstall: Boolean = false,
    val rootAvailable: Boolean? = null,
    val downloadsSize: Long = 0,
    val message: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val moduleRepository = container.moduleRepository
    private val installer = container.installer

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
            settingsRepository.autoCheckUpdates.collect { enabled -> _state.update { it.copy(autoCheck = enabled) } }
        }
        viewModelScope.launch {
            settingsRepository.rootInstall.collect { enabled -> _state.update { it.copy(rootInstall = enabled) } }
        }
    }

    fun setChannel(channel: UpdateChannel) {
        viewModelScope.launch { settingsRepository.setUpdateChannel(channel) }
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(enabled) }
    }

    fun setRootInstall(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val available = installer.isRootAvailable()
                _state.update { it.copy(rootAvailable = available) }
                if (!available) {
                    _state.update { it.copy(message = "Root (su) is not available on this device") }
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
                it.copy(downloadsSize = installer.downloadsSizeBytes(), message = "Cache cleared")
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
