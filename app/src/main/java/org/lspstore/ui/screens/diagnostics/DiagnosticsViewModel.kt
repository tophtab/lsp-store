package org.lspstore.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.BuildConfig
import org.lspstore.R
import org.lspstore.di.AppContainer

data class DiagnosticsUiState(
    val appVersion: String = "",
    val androidVersion: String = "",
    val device: String = "",
    val installedModules: Int = 0,
    val repoModules: Int = 0,
    val indexCacheSize: Long = 0,
    val downloadsSize: Long = 0,
    val channel: String = "",
    val theme: String = "",
    val backgroundCheck: Boolean = true,
    val rootInstall: Boolean = false,
    val translationProvider: String = "",
    val message: String? = null,
) {
    fun asText(): String = buildString {
        appendLine("LSPosed Store diagnostics")
        appendLine("app: $appVersion")
        appendLine("android: $androidVersion")
        appendLine("device: $device")
        appendLine("installed modules: $installedModules")
        appendLine("repository modules: $repoModules")
        appendLine("index cache: $indexCacheSize bytes")
        appendLine("downloads: $downloadsSize bytes")
        appendLine("channel: $channel")
        appendLine("theme: $theme")
        appendLine("background check: $backgroundCheck")
        appendLine("root install: $rootInstall")
        appendLine("translation: $translationProvider")
    }
}

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun copyDiagnostics() {
        val text = _state.value.asText()
        val clipboard = container.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LSPosed Store diagnostics", text))
        _state.update { it.copy(message = container.appContext.getString(R.string.diagnostics_copied)) }
    }

    fun shareDiagnostics() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LSPosed Store diagnostics")
            putExtra(Intent.EXTRA_TEXT, _state.value.asText())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            container.appContext.startActivity(
                Intent.createChooser(intent, container.appContext.getString(R.string.diagnostics_share)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            val settings = container.settingsRepository
            val channel = settings.updateChannel.first()
            val theme = settings.themeMode.first()
            val background = settings.backgroundCheckEnabled.first()
            val root = settings.rootInstall.first()
            val translation = settings.translationSettings.first()
            val installed = runCatching { container.installedRepository.getInstalledModules() }
                .getOrDefault(emptyList())
                .count { it.isXposedModule }
            val repo = runCatching { container.moduleRepository.getIndex(force = false).size }
                .getOrDefault(0)

            _state.update {
                it.copy(
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    installedModules = installed,
                    repoModules = repo,
                    indexCacheSize = container.moduleRepository.cacheSizeBytes(),
                    downloadsSize = container.installer.downloadsSizeBytes(),
                    channel = channel.name,
                    theme = theme.name,
                    backgroundCheck = background,
                    rootInstall = root,
                    translationProvider = translation.provider.name,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DiagnosticsViewModel(container) }
        }
    }
}
