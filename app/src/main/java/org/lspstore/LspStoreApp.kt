package org.lspstore

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lspstore.data.work.UpdateNotifier
import org.lspstore.data.work.UpdateScheduler
import org.lspstore.di.AppContainer

class LspStoreApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        UpdateNotifier.ensureChannel(this)
        applySchedule()
        applyProxy()
        applyMirror()
    }

    private fun applyMirror() {
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsRepository.preferredMirror.collect { host ->
                container.mirrorState.preferred = host
            }
        }
    }

    private fun applyProxy() {
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsRepository.proxyConfig.collect { config ->
                container.proxyState.current = config
            }
        }
    }

    private fun applySchedule() {
        CoroutineScope(Dispatchers.IO).launch {
            val enabled = container.settingsRepository.backgroundCheckEnabled.first()
            if (enabled) {
                val interval = container.settingsRepository.backgroundCheckIntervalHours.first()
                UpdateScheduler.schedule(this@LspStoreApp, interval)
            } else {
                UpdateScheduler.cancel(this@LspStoreApp)
            }
        }
    }
}
