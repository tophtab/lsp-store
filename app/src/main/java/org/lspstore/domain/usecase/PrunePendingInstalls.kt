package org.lspstore.domain.usecase

import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.PendingInstall

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
 * Pure decision table of [org.lspstore.data.repository.PendingInstallRepository.prune]:
 * a pending install is done when the device reports its target version (or a
 * newer one), and is abandoned when it is older than [maxAgeMs] without ever
 * landing (cancelled or dismissed installer dialog).
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
        val installedModule = installedByPackage[entry.packageName]
        val targetReached = entry.versionCode > 0L &&
            installedModule != null &&
            installedModule.versionCode >= entry.versionCode
        val stale = entry.createdAt > 0L && now - entry.createdAt > maxAgeMs
        when {
            targetReached -> landed += entry
            !stale -> kept += entry
        }
    }
    return PendingPruneResult(kept = kept, landed = landed)
}

/** A hand-off that never produced a package event is considered abandoned. */
const val DEFAULT_PENDING_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
