package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.usecase.prunePendingInstalls

/**
 * Pending installs hide their package from the update list, so a wrong prune
 * either loses an install result or hides an update forever.
 */
class PendingPruneTest {

    private fun pending(packageName: String, versionCode: Long, createdAt: Long = 0L) =
        PendingInstall(
            packageName = packageName,
            repoPackageName = packageName,
            title = packageName,
            versionCode = versionCode,
            versionName = "1.1",
            assetName = "module.apk",
            createdAt = createdAt,
        )

    private fun installed(packageName: String, versionCode: Long) = InstalledModule(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0",
        label = packageName,
        isXposedModule = true,
        description = null,
    )

    @Test
    fun reachedTargetIsReportedAsLanded() {
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L)),
            installed = listOf(installed("a.b", versionCode = 11L)),
            now = 1_000L,
        )

        assertEquals(1, result.landed.size)
        assertTrue(result.kept.isEmpty())
    }

    @Test
    fun newerThanTargetAlsoCountsAsLanded() {
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L)),
            installed = listOf(installed("a.b", versionCode = 12L)),
            now = 1_000L,
        )

        assertEquals(1, result.landed.size)
    }

    @Test
    fun oldVersionKeepsWaiting() {
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L, createdAt = 900L)),
            installed = listOf(installed("a.b", versionCode = 10L)),
            now = 1_000L,
        )

        assertEquals(1, result.kept.size)
        assertTrue(result.landed.isEmpty())
    }

    @Test
    fun missingPackageKeepsWaiting() {
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L, createdAt = 900L)),
            installed = emptyList(),
            now = 1_000L,
        )

        assertEquals(1, result.kept.size)
    }

    @Test
    fun abandonedHandOffIsDroppedWithoutRecordingAResult() {
        val maxAge = 60_000L
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L, createdAt = 1_000L)),
            installed = listOf(installed("a.b", versionCode = 10L)),
            now = 1_000L + maxAge + 1L,
            maxAgeMs = maxAge,
        )

        assertTrue(result.kept.isEmpty())
        assertTrue(result.landed.isEmpty())
    }

    @Test
    fun entriesWithoutTimestampNeverExpire() {
        val result = prunePendingInstalls(
            pending = listOf(pending("a.b", versionCode = 11L, createdAt = 0L)),
            installed = listOf(installed("a.b", versionCode = 10L)),
            now = Long.MAX_VALUE / 2,
        )

        assertEquals(1, result.kept.size)
    }

    @Test
    fun mixedBatchesKeepOrder() {
        val result = prunePendingInstalls(
            pending = listOf(
                pending("a.a", versionCode = 5L),
                pending("b.b", versionCode = 5L),
                pending("c.c", versionCode = 5L),
            ),
            installed = listOf(
                installed("a.a", versionCode = 5L),
                installed("b.b", versionCode = 4L),
            ),
            now = 1_000L,
        )

        assertEquals(listOf("a.a"), result.landed.map { it.packageName })
        assertEquals(listOf("b.b", "c.c"), result.kept.map { it.packageName })
    }
}
