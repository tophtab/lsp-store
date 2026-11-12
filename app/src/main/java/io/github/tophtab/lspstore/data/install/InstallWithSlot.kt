package io.github.tophtab.lspstore.data.install

import java.io.File

/** Release a failed root attempt before waiting to launch its interactive fallback. */
suspend fun installWithSlot(
    installer: ApkInstallDriver,
    serializer: SystemInstallSerializer,
    file: File,
    packageName: String,
    useRoot: Boolean,
    allowUi: Boolean,
): InstallOutcome {
    suspend fun attempt(root: Boolean, ui: Boolean): InstallOutcome {
        while (true) {
            if (ui) installer.awaitForeground()
            serializer.awaitFreeAndMarkPending(packageName)
            // The previous installer may have taken the store out of the
            // foreground while this attempt was waiting for the slot.
            if (ui && !installer.canLaunchUi) {
                serializer.markCompleted(packageName)
                continue
            }
            var delegated = false
            try {
                val outcome = installer.install(file, root, ui)
                delegated = outcome == InstallOutcome.DelegatedToSystem
                return outcome
            } finally {
                if (!delegated) serializer.markCompleted(packageName)
            }
        }
    }
    if (useRoot) {
        val rootOutcome = attempt(root = true, ui = false)
        if (rootOutcome !is InstallOutcome.Failure || !allowUi) return rootOutcome
    }
    return attempt(root = false, ui = allowUi)
}
