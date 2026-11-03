package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.usecase.PendingSettlement
import io.github.tophtab.lspstore.domain.usecase.pendingInstallLanded
import io.github.tophtab.lspstore.domain.usecase.settlePendingInstall

/**
 * The same-code rule is the one that hid updates on device: `installed >= target`
 * is already true for the old build, so a dismissed installer used to look like
 * a successful re-upload. The install timestamp now decides that case.
 */
class PendingInstallSettlementTest {

    private fun pending(versionCode: Long, createdAt: Long = 1_000L) = PendingInstall(
        packageName = "com.example.module",
        repoPackageName = "com.example.module",
        title = "Module",
        versionCode = versionCode,
        versionName = "1.1",
        assetName = "module-1.1.apk",
        createdAt = createdAt,
    )

    private fun installed(versionCode: Long, lastUpdateTime: Long = 0L) = InstalledModule(
        packageName = "com.example.module",
        versionCode = versionCode,
        versionName = "1.1",
        label = "Module",
        isXposedModule = true,
        description = null,
        lastUpdateTime = lastUpdateTime,
    )

    @Test
    fun newerCodeLandsRegardlessOfTimestamps() {
        assertTrue(pendingInstallLanded(pending(versionCode = 11L), 12L, installedLastUpdateTime = 0L))
        assertTrue(pendingInstallLanded(pending(versionCode = 11L), 99L, installedLastUpdateTime = 0L))
    }

    @Test
    fun sameCodeOldInstallIsNotLanded() {
        // The hand-off was written at t=1000, the installed copy predates it:
        // this is the still-installed old build, not the new upload.
        assertFalse(
            pendingInstallLanded(
                pending = pending(versionCode = 11L, createdAt = 1_000L),
                installedVersionCode = 11L,
                installedLastUpdateTime = 500L,
            ),
        )
    }

    @Test
    fun sameCodeReinstallLands() {
        assertTrue(
            pendingInstallLanded(
                pending = pending(versionCode = 11L, createdAt = 1_000L),
                installedVersionCode = 11L,
                installedLastUpdateTime = 1_000L,
            ),
        )
        assertTrue(
            pendingInstallLanded(
                pending = pending(versionCode = 11L, createdAt = 1_000L),
                installedVersionCode = 11L,
                installedLastUpdateTime = 2_000L,
            ),
        )
    }

    @Test
    fun sameCodeWithoutAnInstallTimestampIsNotLanded() {
        assertFalse(
            pendingInstallLanded(
                pending = pending(versionCode = 11L),
                installedVersionCode = 11L,
                installedLastUpdateTime = null,
            ),
        )
    }

    @Test
    fun olderCodeIsNotLanded() {
        assertFalse(pendingInstallLanded(pending(versionCode = 11L), 10L, 2_000L))
    }

    @Test
    fun unknownTargetLandsWhenThePackageExists() {
        assertTrue(pendingInstallLanded(pending(versionCode = 0L), 3L, 0L))
        assertFalse(pendingInstallLanded(pending(versionCode = 0L), null, null))
    }

    @Test
    fun staleHandOffIsAbandoned() {
        assertEquals(
            PendingSettlement.ABANDONED,
            settlePendingInstall(
                pending = pending(versionCode = 11L, createdAt = 1_000L),
                installed = installed(versionCode = 10L),
                now = 62_000L,
                abandonAfterMs = 60_000L,
            ),
        )
    }

    @Test
    fun landedBeatsTheAbandonWindow() {
        assertEquals(
            PendingSettlement.LANDED,
            settlePendingInstall(
                pending = pending(versionCode = 11L, createdAt = 1_000L),
                installed = installed(versionCode = 11L, lastUpdateTime = 1_500L),
                now = 100_000L,
                abandonAfterMs = 60_000L,
            ),
        )
    }

    @Test
    fun missingPackageStaysPendingUntilItAgesOut() {
        assertEquals(
            PendingSettlement.PENDING,
            settlePendingInstall(pending(versionCode = 11L), null, 2_000L, 60_000L),
        )
        assertEquals(
            PendingSettlement.ABANDONED,
            settlePendingInstall(pending(versionCode = 11L), null, 100_000L, 60_000L),
        )
    }

    @Test
    fun entriesWithoutATimestampNeverExpire() {
        assertEquals(
            PendingSettlement.PENDING,
            settlePendingInstall(
                pending = pending(versionCode = 11L, createdAt = 0L),
                installed = installed(versionCode = 10L),
                now = Long.MAX_VALUE / 2,
                abandonAfterMs = 60_000L,
            ),
        )
    }
}
