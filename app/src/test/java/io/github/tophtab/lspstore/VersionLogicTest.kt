package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.usecase.UpdateChecker

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
    fun updateCheckerFollowsPreReleasesWhenOptedIn() {
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

        val stable = UpdateChecker.findUpdates(listOf(outdated), listOf(module))
        assertEquals(1, stable.size)
        assertEquals("1.0", stable.first().latest.versionName)

        val beta = UpdateChecker.findUpdates(listOf(outdated), listOf(module), preReleases = setOf("a.b"))
        assertEquals("1.2-beta", beta.first().latest.versionName)

        assertTrue(UpdateChecker.findUpdates(listOf(current), listOf(module), preReleases = setOf("a.b")).isEmpty())
        assertTrue(UpdateChecker.findUpdates(listOf(notAModule), listOf(module)).isEmpty())
    }

    @Test
    fun updateCheckerRespectsSkippedAndIgnored() {
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
            latestBetaRelease = null,
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        )
        val installed = InstalledModule("a.b", 9, "0.9", "T", true, null)

        val skippedCurrent = UpdateChecker.findUpdates(
            listOf(installed), listOf(module), skipped = mapOf("a.b" to SkippedVersion("10")),
        )
        assertTrue(skippedCurrent.isEmpty())

        val skippedOlder = UpdateChecker.findUpdates(
            listOf(installed), listOf(module), skipped = mapOf("a.b" to SkippedVersion("9")),
        )
        assertEquals(1, skippedOlder.size)

        val ignored = UpdateChecker.findUpdates(
            listOf(installed), listOf(module), ignored = setOf("a.b"),
        )
        assertTrue(ignored.isEmpty())
    }

    @Test
    fun updateCheckerResolvesLinkedModules() {
        val module = Module(
            packageName = "repo.pkg",
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
            latestBetaRelease = null,
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        )
        val installed = InstalledModule("com.vendor.alias", 9, "0.9", "T", true, null)

        assertTrue(
            UpdateChecker.findUpdates(listOf(installed), listOf(module)).isEmpty(),
        )

        val linked = UpdateChecker.findUpdates(
            listOf(installed),
            listOf(module),
            links = mapOf("com.vendor.alias" to "repo.pkg"),
        )
        assertEquals(1, linked.size)
        assertEquals("repo.pkg", linked.first().module.packageName)
    }

    @Test
    fun linkedModuleHonoursRepoKeyForSkippedAndIgnored() {
        val module = Module(
            packageName = "repo.pkg",
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
            latestBetaRelease = null,
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        )
        val installed = InstalledModule("com.vendor.alias", 9, "0.9", "T", true, null)
        val links = mapOf("com.vendor.alias" to "repo.pkg")

        assertTrue(
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                skipped = mapOf("repo.pkg" to SkippedVersion("10")), links = links,
            ).isEmpty(),
        )
        assertTrue(
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                links = links, ignored = setOf("com.vendor.alias"),
            ).isEmpty(),
        )
        assertTrue(
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                links = links, ignored = setOf("repo.pkg"),
            ).isEmpty(),
        )
        assertEquals(
            1,
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                skipped = mapOf("com.vendor.alias" to SkippedVersion("9")), links = links,
            ).size,
        )
    }

    @Test
    fun exposesPreReleaseStream() {
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
            latestBetaRelease = ModuleVersion.parse("11-1.1-beta"),
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        )
        assertTrue(module.hasPreReleases)
        assertEquals("1.1-beta", module.latestFor(includePreReleases = true)?.versionName)
        assertEquals("1.0", module.latestFor(includePreReleases = false)?.versionName)
    }

    /**
     * 24 of the 29 modules that publish pre-releases ship a beta older than
     * their stable. Opting in must keep the stable update visible.
     */
    @Test
    fun preReleaseOptInKeepsNewerStable() {
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
            latestRelease = ModuleVersion.parse("60-3.5.0"),
            latestBetaRelease = ModuleVersion.parse("37-2.7.0"),
            latestSnapshotRelease = null,
            latestReleaseTime = "2026-09-30T06:58:32Z",
            latestBetaReleaseTime = "2024-09-08T15:29:29Z",
        )
        val installed = InstalledModule("a.b", 30, "1.0", "T", true, null)

        val stableOnly = UpdateChecker.findUpdates(listOf(installed), listOf(module))
        assertEquals("3.5.0", stableOnly.single().latest.versionName)

        val optedIn = UpdateChecker.findUpdates(
            listOf(installed), listOf(module), preReleases = setOf("a.b"),
        )
        assertEquals("3.5.0", optedIn.single().latest.versionName)
    }

    /** A newer beta does replace the stable one for opted-in modules. */
    @Test
    fun preReleaseOptInTakesNewerBeta() {
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
            latestRelease = ModuleVersion.parse("186-1.63.3"),
            latestBetaRelease = ModuleVersion.parse("194-1.63.11"),
            latestSnapshotRelease = null,
            latestReleaseTime = "2026-10-12T12:00:17Z",
            latestBetaReleaseTime = "2026-10-18T09:41:13Z",
        )
        val installed = InstalledModule("a.b", 180, "1.63.0", "T", true, null)

        assertEquals("1.63.3", UpdateChecker.findUpdates(listOf(installed), listOf(module)).single().latest.versionName)
        assertEquals(
            "1.63.11",
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module), preReleases = setOf("a.b"),
            ).single().latest.versionName,
        )
    }

    /** Modules that never shipped a stable build surface once opted in. */
    @Test
    fun preReleaseOnlyModuleSurfacesWhenOptedIn() {
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
            latestRelease = null,
            latestBetaRelease = ModuleVersion.parse("5-0.5.0"),
            latestSnapshotRelease = null,
            latestReleaseTime = null,
            latestBetaReleaseTime = "2026-09-23T06:57:41Z",
        )
        val installed = InstalledModule("a.b", 1, "0.1", "T", true, null)

        assertTrue(UpdateChecker.findUpdates(listOf(installed), listOf(module)).isEmpty())
        assertEquals(
            "0.5.0",
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module), preReleases = setOf("a.b"),
            ).single().latest.versionName,
        )
    }

    @Test
    fun skippedTagOnlySkipsThatExactVersion() {
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
            latestRelease = ModuleVersion.parse("1-1.1"),
            latestBetaRelease = null,
            latestSnapshotRelease = null,
            latestReleaseTime = "2023-11-15T00:00:00Z",
        )
        val installed = InstalledModule(
            packageName = "a.b",
            versionCode = 1,
            versionName = "1.0",
            label = "T",
            isXposedModule = true,
            description = null,
            lastUpdateTime = 1_700_000_000_000L,
        )

        // Legacy records store only the code, so they still hide every 1-x tag.
        assertTrue(
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                skipped = mapOf("a.b" to SkippedVersion("1")),
            ).isEmpty(),
        )

        // New records store the tag; skipping 1-1.0 must not hide 1-1.1.
        assertEquals(
            1,
            UpdateChecker.findUpdates(
                listOf(installed), listOf(module),
                skipped = mapOf("a.b" to SkippedVersion("1-1.0")),
            ).size,
        )
    }

    @Test
    fun releaseMatchesTheVersionTheIndexPromised() {
        val release = Release(
            tagName = "42-2.0.5",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("42-2.0.5"),
            assets = emptyList(),
        )
        assertTrue(release.matchesVersion(ModuleVersion.parse("42-2.0.5")!!))
        assertFalse(release.matchesVersion(ModuleVersion.parse("14-1.1.1")!!))
    }

}
