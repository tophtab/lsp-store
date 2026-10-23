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
 * so the only reliable completion signal is the package broadcast. Receiving it
 * releases the install slot in [SystemInstallSerializer], clears the persisted
 * pending-install state and lets the UI refresh the installed list.
 */
class PackageEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val app = context.applicationContext as? LspStoreApp ?: return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            -> {
                // Records the variant outcome, releases the install slot and the
                // persisted pending state, then notifies the UI. Keep the process
                // alive until the DataStore write finishes.
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        app.container.completePendingInstall(packageName)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
