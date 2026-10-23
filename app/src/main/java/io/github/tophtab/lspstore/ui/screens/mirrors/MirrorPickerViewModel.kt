package io.github.tophtab.lspstore.ui.screens.mirrors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.remote.MIRRORS
import io.github.tophtab.lspstore.data.remote.Mirror
import io.github.tophtab.lspstore.di.AppContainer

data class MirrorUiState(
    val mirrors: List<Mirror> = MIRRORS,
    val preferred: String = "",
    val latencies: Map<String, Long> = emptyMap(),
    val failures: Set<String> = emptySet(),
    val testing: Boolean = false,
    val message: String? = null,
)

class MirrorPickerViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val tester = container.mirrorTester
    private val appContext = container.appContext

    private val _state = MutableStateFlow(MirrorUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.preferredMirror.collect { host ->
                _state.update { it.copy(preferred = host) }
            }
        }
        testAll()
    }

    fun select(host: String) {
        viewModelScope.launch {
            settingsRepository.setPreferredMirror(host)
            _state.update {
                it.copy(message = appContext.getString(R.string.mirror_saved))
            }
        }
    }

    fun testAll() {
        if (_state.value.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, latencies = emptyMap(), failures = emptySet()) }
            val results = MIRRORS.associate { mirror -> mirror.host to tester.test(mirror) }
            _state.update {
                it.copy(
                    testing = false,
                    latencies = results.mapNotNull { (host, result) ->
                        result.getOrNull()?.let { latency -> host to latency }
                    }.toMap(),
                    failures = results.filterValues { it.isFailure }.keys,
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MirrorPickerViewModel(container) }
        }
    }
}
