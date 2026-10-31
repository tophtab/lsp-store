package io.github.tophtab.lspstore.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.ThemeMode

enum class OnboardingStep { WELCOME, APPEARANCE, PERMISSIONS }

data class OnboardingUiState(
    val steps: List<OnboardingStep> = OnboardingStep.entries,
    val currentIndex: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = true,
    val dynamicColor: Boolean = false,
) {
    val currentStep: OnboardingStep get() = steps[currentIndex]
    val isFirst: Boolean get() = currentIndex == 0
    val isLast: Boolean get() = currentIndex == steps.lastIndex
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository

    private val _state = MutableStateFlow(OnboardingUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.themeMode.collect { mode -> _state.update { it.copy(themeMode = mode) } }
        }
        viewModelScope.launch {
            settingsRepository.amoled.collect { enabled -> _state.update { it.copy(amoled = enabled) } }
        }
        viewModelScope.launch {
            settingsRepository.dynamicColor.collect { enabled -> _state.update { it.copy(dynamicColor = enabled) } }
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

    fun next() {
        val current = _state.value
        if (current.isLast) {
            finish()
        } else {
            _state.update { it.copy(currentIndex = it.currentIndex + 1) }
        }
    }

    fun back() {
        _state.update { if (it.isFirst) it else it.copy(currentIndex = it.currentIndex - 1) }
    }

    fun skip() {
        if (_state.value.isLast) finish() else next()
    }

    private fun finish() {
        viewModelScope.launch { settingsRepository.setOnboardingComplete(true) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { OnboardingViewModel(container) }
        }
    }
}
