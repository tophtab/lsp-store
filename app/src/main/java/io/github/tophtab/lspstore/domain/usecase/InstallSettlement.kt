package io.github.tophtab.lspstore.domain.usecase

import kotlinx.coroutines.delay
import io.github.tophtab.lspstore.domain.model.PendingInstall

/** What the device currently reports for one delegated install. */
data class InstallProbe(
    /** Persisted hand-off, or null when it was already reconciled elsewhere. */
    val pending: PendingInstall?,
    /** Version code currently installed, or null when the package is absent. */
    val installedVersionCode: Long?,
)

/**
 * Decides whether a delegated install has landed.
 *
 * Package broadcasts cannot be used for this: `PACKAGE_ADDED` is an implicit
 * broadcast that a backgrounded app never receives, and the system installer
 * keeps its "App installed" screen open until the user dismisses it, so the app
 * stays backgrounded for the whole hand-off. Polling the cheap PackageManager
 * lookup is the only reliable signal.
 *
 * The device work is injected through [probe]; this class only owns the timing
 * and the comparison rules.
 */
class InstallSettlement(
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
) {

    enum class Outcome { SETTLED, TIMED_OUT }

    suspend fun await(
        timeoutMs: Long,
        pollMs: Long,
        probe: suspend () -> InstallProbe,
    ): Outcome {
        val deadline = now() + timeoutMs
        while (true) {
            if (hasLanded(probe())) return Outcome.SETTLED
            if (now() >= deadline) return Outcome.TIMED_OUT
            sleep(pollMs)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 180_000L
        const val DEFAULT_POLL_MS: Long = 3_000L

        /**
         * A pending entry that is gone was already reconciled (broadcast, resume
         * or another caller), and a target version of 0 means "any version".
         */
        fun hasLanded(probe: InstallProbe): Boolean {
            val pending = probe.pending ?: return true
            val installed = probe.installedVersionCode ?: return false
            return pending.versionCode == 0L || installed >= pending.versionCode
        }
    }
}
