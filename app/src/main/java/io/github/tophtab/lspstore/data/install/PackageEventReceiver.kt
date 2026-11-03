package io.github.tophtab.lspstore.data.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.LspStoreApp

/**
 * Port of komi-store's `PackageEventReceiver`.
 *
 * Delegated installs are finished by another app (InstallerX, system installer),
 * so the only reliable completion signal is the package broadcast. The action
 * matters: an install and an uninstall are not the same outcome, and a broadcast
 * for an unrelated package must not touch the pending hand-off or its slot.
 */
class PackageEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val app = context.applicationContext as? LspStoreApp ?: return
        val removed = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            -> false

            Intent.ACTION_PACKAGE_FULLY_REMOVED -> true
            else -> return
        }
        // Keep the process alive until the DataStore write/scan finishes.
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                app.container.onPackageEvent(packageName, removed)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
