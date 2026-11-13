package io.github.tophtab.lspstore.ui

import io.github.tophtab.lspstore.data.install.UninstallOutcome

/** Refresh the caller's installed state only after an in-process uninstall completes. */
internal suspend fun reportUninstallOutcome(
    outcome: UninstallOutcome,
    completedMessage: String,
    onCompleted: suspend () -> Unit,
    onMessage: (String) -> Unit,
) {
    when (outcome) {
        UninstallOutcome.Completed -> {
            onCompleted()
            onMessage(completedMessage)
        }
        UninstallOutcome.DelegatedToSystem -> Unit
        is UninstallOutcome.Failure -> onMessage(outcome.message)
    }
}
