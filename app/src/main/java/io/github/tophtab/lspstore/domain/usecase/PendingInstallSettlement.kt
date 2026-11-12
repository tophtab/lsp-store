package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall

/** What a persisted hand-off looks like against what the device currently runs. */
enum class PendingSettlement {
    /** The target (or a newer build) is installed. */
    LANDED,

    /** The old build is still installed; the installer may not have finished. */
    PENDING,

    /** Too old to still be in flight: cancelled, dismissed, or the package is gone. */
    ABANDONED,
}

/**
 * Whether the device runs the build a pending hand-off was for.
 *
 * `versionCode` alone cannot answer this: authors re-upload with the same code
 * (18% of the LSPosed index uses code 1 for every release), and the old build
 * already satisfies `installed >= target`. A re-install of the same code does
 * move [InstalledModule.lastUpdateTime] forward, so the timestamps decide that
 * case. A newer code is always enough, and an unknown target (`0`) keeps the old
 * "package exists" rule.
 */
fun pendingInstallLanded(
    pending: PendingInstall,
    installedVersionCode: Long?,
    installedLastUpdateTime: Long?,
): Boolean {
    if (installedVersionCode == null) return false
    val target = pending.versionCode
    if (target <= 0L) return true
    if (installedVersionCode > target) return true
    if (installedVersionCode < target) return false
    // A legacy hand-off has no timestamp to compare, so the old code-based rule
    // is the only one available; new entries always carry createdAt.
    if (pending.createdAt <= 0L) return true
    val installedAt = installedLastUpdateTime ?: return false
    return installedAt >= pending.createdAt
}

/**
 * Settles one persisted hand-off. [abandonAfterMs] is the window in which a
 * hand-off that has not landed is still considered in flight; past it, the
 * installer dialog was dismissed or the package was uninstalled. Background
 * scans set [allowAbandonment] to false: the user may still be reading the
 * external installer's confirmation page, so elapsed time cannot cancel it.
 */
fun settlePendingInstall(
    pending: PendingInstall,
    installed: InstalledModule?,
    now: Long,
    abandonAfterMs: Long,
    allowAbandonment: Boolean = true,
): PendingSettlement {
    if (pendingInstallLanded(pending, installed?.versionCode, installed?.lastUpdateTime)) {
        return PendingSettlement.LANDED
    }
    if (allowAbandonment && pending.createdAt > 0L && now - pending.createdAt > abandonAfterMs) {
        return PendingSettlement.ABANDONED
    }
    return PendingSettlement.PENDING
}
