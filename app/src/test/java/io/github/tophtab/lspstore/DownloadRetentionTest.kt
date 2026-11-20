package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.DownloadRetention
import io.github.tophtab.lspstore.data.install.shouldDeleteExportedApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetentionTest {
    private val name = "Module-0123456789abcdef.apk"
    private val week = 7L * 24 * 60 * 60 * 1_000
    private val now = 100 * week

    @Test
    fun eachFinitePolicyDeletesAtItsBoundaryAndKeepsNewerFiles() {
        listOf(1, 2, 4).forEach { weeks ->
            val boundary = now - weeks * week
            assertFalse(shouldDeleteExportedApk(name, boundary + 1, weeks, now))
            assertTrue(shouldDeleteExportedApk(name, boundary, weeks, now))
            assertTrue(shouldDeleteExportedApk(name, boundary - 1, weeks, now))
        }
    }

    @Test
    fun zeroRemovesExistingExportsRegardlessOfAgeOrMissingTimestamp() {
        listOf(0L, now, now + week).forEach { timestamp ->
            assertTrue(shouldDeleteExportedApk(name, timestamp, 0, now))
        }
    }

    @Test
    fun foreverKeepsEvenOldExports() {
        assertFalse(shouldDeleteExportedApk(name, 1L, DownloadRetention.FOREVER, now))
        assertFalse(shouldDeleteExportedApk(name, 0L, DownloadRetention.FOREVER, now))
    }

    @Test
    fun unknownAndFutureTimestampsDoNotExpireWithFiniteRetention() {
        listOf(1, 2, 4).forEach { weeks ->
            listOf(-1L, 0L, now + 1).forEach { timestamp ->
                assertFalse(shouldDeleteExportedApk(name, timestamp, weeks, now))
            }
        }
    }

    @Test
    fun cleanupKeepsOtherFilesEvenAtZeroRetention() {
        listOf("module.apk", "notes.txt", "Module-0123456789abcde.apk", "Module-0123456789abcdef.apk.bak")
            .forEach { foreignName ->
                assertFalse(shouldDeleteExportedApk(foreignName, 1L, 0, now))
                assertFalse(shouldDeleteExportedApk(foreignName, 1L, 1, now))
            }
    }

    @Test
    fun missingAndInvalidPreferencesPreserveExistingBehavior() {
        assertEquals(DownloadRetention.FOREVER, DownloadRetention.DEFAULT_WEEKS)
        assertEquals(listOf(0, 1, 2, 4, DownloadRetention.FOREVER), DownloadRetention.WEEK_OPTIONS)
        DownloadRetention.WEEK_OPTIONS.forEach { assertEquals(it, DownloadRetention.normalize(it)) }
        listOf(-2, 3, 5, Int.MAX_VALUE).forEach { weeks ->
            assertEquals(DownloadRetention.FOREVER, DownloadRetention.normalize(weeks))
            assertFalse(shouldDeleteExportedApk(name, 1L, weeks, now))
        }
    }
}
