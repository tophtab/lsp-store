package org.lspstore.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lspstore.LspStoreApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (appContext as LspStoreApp).container
                val enabled = container.settingsRepository.backgroundCheckEnabled.first()
                if (enabled) {
                    val interval = container.settingsRepository.backgroundCheckIntervalHours.first()
                    UpdateScheduler.schedule(appContext, interval)
                } else {
                    UpdateScheduler.cancel(appContext)
                }
            } catch (_: Throwable) {
                // Best effort: WorkManager also persists its own periodic work across reboots.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
