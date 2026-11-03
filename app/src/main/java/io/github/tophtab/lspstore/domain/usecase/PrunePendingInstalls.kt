package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall

/**
 * Split of the persisted pending installs after a PackageManager scan.
 *
 * [landed] entries are returned rather than dropped: the caller has to record the
 * install outcome (variant memory) before forgetting them, otherwise a delegated
 * install disappears silently when the target version arrives while the process
 * is dead.
 */
data class PendingPruneResult(
    val kept: List<PendingInstall>,
    val landed: List<PendingInstall>,
)

/**
 * Pure decision table of [io.github.tophtab.lspstore.data.repository.PendingInstallRepository.prune]:
 * a pending install is done when [settlePendingInstall] says the device runs the
 * target (including a same-code re-upload), and is abandoned when it is older
 * than [maxAgeMs] without ever landing (cancelled or dismissed installer dialog).
 */
fun prunePendingInstalls(
    pending: List<PendingInstall>,
    installed: List<InstalledModule>,
    now: Long,
    maxAgeMs: Long = DEFAULT_PENDING_MAX_AGE_MS,
): PendingPruneResult {
    val installedByPackage = installed.associateBy { it.packageName }
    val kept = mutableListOf<PendingInstall>()
    val landed = mutableListOf<PendingInstall>()
    pending.forEach { entry ->
        when (
            settlePendingInstall(
                pending = entry,
                installed = installedByPackage[entry.packageName],
                now = now,
                abandonAfterMs = maxAgeMs,
            )
        ) {
            PendingSettlement.LANDED -> landed += entry
            PendingSettlement.PENDING -> kept += entry
            PendingSettlement.ABANDONED -> Unit
        }
    }
    return PendingPruneResult(kept = kept, landed = landed)
}

/** A hand-off that never produced a package event is considered abandoned. */
const val DEFAULT_PENDING_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
