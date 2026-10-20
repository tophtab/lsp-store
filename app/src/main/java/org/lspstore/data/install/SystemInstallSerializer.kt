package org.lspstore.data.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Port of komi-store's `DefaultSystemInstallSerializer`.
 *
 * Delegated installs (InstallerX, system PackageInstaller, ...) are
 * asynchronous and have no callback of their own, so the app keeps a single
 * global "install in flight" slot: the next install waits for the previous one
 * to be released by [PackageEventReceiver] (or until the timeout force-claims
 * the slot) so that installer dialogs never stack up.
 */
class SystemInstallSerializer {
    private val pending = MutableStateFlow<String?>(null)

    suspend fun awaitFreeAndMarkPending(
        packageName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        val acquired = withTimeoutOrNull(timeoutMs) {
            while (!pending.compareAndSet(null, packageName)) {
                pending.first { it == null }
            }
        }
        if (acquired == null) {
            // The delegated installer never reported back in time; carry on so a
            // batch update cannot deadlock on a dismissed dialog.
            pending.value = packageName
        }
    }

    fun markCompleted(packageName: String) {
        pending.value = null
    }

    companion object {
        /**
         * Long enough to cover the system installer UI (permissions, package
         * verifier, user reading the dialog); shorter than a forever hang.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L
    }
}
