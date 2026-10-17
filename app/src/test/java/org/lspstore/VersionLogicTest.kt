package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.usecase.UpdateChecker

class VersionLogicTest {

    @Test
    fun parsesVersionCodeAndName() {
        val version = ModuleVersion.parse("4019-3.4.3")
        assertEquals(4019L, version?.versionCode)
        assertEquals("3.4.3", version?.versionName)
    }

    @Test
    fun rejectsMalformedTags() {
        assertNull(ModuleVersion.parse(null))
        assertNull(ModuleVersion.parse(""))
        assertNull(ModuleVersion.parse("no-dash"))
        assertNull(ModuleVersion.parse("-1.0"))
        assertNull(ModuleVersion.parse("abc-1.0"))
    }

    @Test
    fun followsLsposedUpgradeRules() {
        val version = ModuleVersion.parse("4019-3.4.3")!!
        assertTrue(version.isNewerThan(4018, "3.4.2"))
        assertFalse(version.isNewerThan(4019, "3.4.3"))
        assertTrue(version.isNewerThan(4019, "3.4.2"))
        assertFalse(version.isNewerThan(4020, "3.4.3"))
    }

    @Test
    fun updateCheckerSelectsChannelAndSkipsNonModules() {
        val module = Module(
            packageName = "a.b",
            title = "T",
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            updatedAt = null,
            stars = 0,
            contributor = null,
            contributorAvatarUrl = null,
            latestRelease = ModuleVersion.parse("10-1.0"),
            latestBetaRelease = ModuleVersion.parse("12-1.2-beta"),
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        )
        val outdated = InstalledModule("a.b", 9, "0.9", "T", true, null)
        val current = InstalledModule("a.b", 12, "1.2-beta", "T", true, null)
        val notAModule = InstalledModule("c.d", 1, "1.0", "C", false, null)

        val stable = UpdateChecker.findUpdates(listOf(outdated), listOf(module), UpdateChannel.STABLE)
        assertEquals(1, stable.size)
        assertEquals("1.0", stable.first().latest.versionName)

        val beta = UpdateChecker.findUpdates(listOf(outdated), listOf(module), UpdateChannel.BETA)
        assertEquals("1.2-beta", beta.first().latest.versionName)

        assertTrue(UpdateChecker.findUpdates(listOf(current), listOf(module), UpdateChannel.BETA).isEmpty())
        assertTrue(UpdateChecker.findUpdates(listOf(notAModule), listOf(module), UpdateChannel.STABLE).isEmpty())
    }
}
