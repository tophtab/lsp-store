package io.github.tophtab.lspstore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.usecase.InstallProbe
import io.github.tophtab.lspstore.domain.usecase.InstallSettlement

/**
 * The delegated-install landing rule is the reason the batch queue used to hang
 * on device: `PACKAGE_ADDED` never arrives while the system installer owns the
 * screen, so settlement is decided by polling. The clock is injected, so the
 * 180 s timeout is exercised in microseconds.
 */
class InstallSettlementTest {

    private class FakeClock(var millis: Long = 1_000L) {
        val slept = mutableListOf<Long>()
    }

    private fun pending(versionCode: Long) = PendingInstall(
        packageName = "com.example.module",
        repoPackageName = "com.example.module",
        title = "Module",
        versionCode = versionCode,
        versionName = "1.1",
        assetName = "module-1.1.apk",
        createdAt = 1L,
    )

    @Test
    fun landsAsSoonAsTheTargetVersionAppears() {
        val clock = FakeClock()
        val settlement = InstallSettlement(
            now = { clock.millis },
            sleep = { ms ->
                clock.slept += ms
                clock.millis += ms
            },
        )
        var installed: Long? = 10L
        val outcome = runBlocking {
            settlement.await(timeoutMs = 60_000L, pollMs = 3_000L) {
                InstallProbe(pending(versionCode = 11L), installed)
            }
        }

        assertEquals(InstallSettlement.Outcome.TIMED_OUT, outcome)
        assertTrue(clock.slept.isNotEmpty())
        assertEquals(3_000L, clock.slept.first())

        // The installer finishes: the next poll sees the new version.
        installed = 11L
        val settled = runBlocking {
            settlement.await(timeoutMs = 60_000L, pollMs = 3_000L) {
                InstallProbe(pending(versionCode = 11L), installed)
            }
        }
        assertEquals(InstallSettlement.Outcome.SETTLED, settled)
    }

    @Test
    fun oldVersionIsNotEnough() {
        assertFalse(
            InstallSettlement.hasLanded(
                InstallProbe(pending(versionCode = 11L), installedVersionCode = 10L),
            ),
        )
        assertTrue(
            InstallSettlement.hasLanded(
                InstallProbe(pending(versionCode = 11L), installedVersionCode = 11L),
            ),
        )
        assertTrue(
            InstallSettlement.hasLanded(
                InstallProbe(pending(versionCode = 11L), installedVersionCode = 12L),
            ),
        )
    }

    @Test
    fun unknownTargetVersionCountsAsLandedWhenThePackageExists() {
        assertTrue(
            InstallSettlement.hasLanded(
                InstallProbe(pending(versionCode = 0L), installedVersionCode = 3L),
            ),
        )
        // A missing package cannot satisfy a hand-off even with versionCode 0.
        assertFalse(
            InstallSettlement.hasLanded(
                InstallProbe(pending(versionCode = 0L), installedVersionCode = null),
            ),
        )
    }

    @Test
    fun disappearedPendingEntryIsAlreadyReconciled() {
        assertTrue(
            InstallSettlement.hasLanded(InstallProbe(pending = null, installedVersionCode = null)),
        )
    }

    @Test
    fun timesOutWhenTheInstallNeverLands() {
        val clock = FakeClock()
        var polls = 0
        val settlement = InstallSettlement(
            now = { clock.millis },
            sleep = { ms -> clock.millis += ms },
        )

        val outcome = runBlocking {
            settlement.await(timeoutMs = 9_000L, pollMs = 3_000L) {
                polls++
                InstallProbe(pending(versionCode = 11L), installedVersionCode = 10L)
            }
        }

        assertEquals(InstallSettlement.Outcome.TIMED_OUT, outcome)
        // 1s start + 3s + 3s + 3s = 10s >= deadline.
        assertEquals(4, polls)
        assertNull(clock.slept.firstOrNull())
    }
}
