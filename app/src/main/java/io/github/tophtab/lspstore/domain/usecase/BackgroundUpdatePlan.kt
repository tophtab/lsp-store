package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.UpdateInfo

/**
 * Pure decisions of the background update check. Kept free of Android types so
 * the JVM tests can pin them without a device.
 */
object BackgroundUpdatePlan {

    /** Stable identity of an update set, for notification dedup. Order-independent. */
    fun notificationSignature(updates: List<UpdateInfo>): String =
        updates.map { "${it.module.packageName}:${it.latest.versionCode}" }
            .sorted()
            .joinToString("\n")

    /** Drops targets another path already owns (pending hand-off / running install). */
    fun autoUpdateTargets(
        updates: List<UpdateInfo>,
        pendingPackages: Set<String>,
        runningPackages: Set<String>,
    ): List<UpdateInfo> = updates.filterNot { info ->
        info.installed.packageName in pendingPackages ||
            info.module.packageName in runningPackages ||
            info.installed.packageName in runningPackages
    }
}
