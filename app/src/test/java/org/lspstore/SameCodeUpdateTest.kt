package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.usecase.UpdateChecker

/**
 * LSPosed treats "same versionCode, different versionName" as an update, which
 * is right for re-uploads but loops forever on pure naming drift. The store adds
 * a timestamp gate: an equal code only counts when the release was published
 * after the installed copy landed.
 */
class SameCodeUpdateTest {

    private val installTime = 1_700_000_000_000L

    private fun installed(code: Long, name: String) = InstalledModule(
        packageName = "com.example.module",
        versionCode = code,
        versionName = name,
        label = "Example",
        isXposedModule = true,
        description = null,
        lastUpdateTime = installTime,
    )

    private fun module(code: Long, name: String, published: String?) = Module(
        packageName = "com.example.module",
        title = "Example",
        summary = null,
        url = null,
        homepageUrl = null,
        sourceUrl = null,
        updatedAt = null,
        stars = 0,
        contributor = null,
        contributorAvatarUrl = null,
        latestRelease = org.lspstore.domain.model.ModuleVersion(code, name, "$code-$name"),
        latestBetaRelease = null,
        latestSnapshotRelease = null,
        latestReleaseTime = published,
    )

    private fun hasUpdate(installed: InstalledModule, module: Module): Boolean =
        UpdateChecker.findUpdates(
            installed = listOf(installed),
            index = listOf(module),
            channel = UpdateChannel.STABLE,
        ).isNotEmpty()

    @Test
    fun higherCodeIsAlwaysAnUpdate() {
        assertTrue(hasUpdate(installed(10, "1.0"), module(11, "1.0", null)))
    }

    @Test
    fun lowerCodeIsNeverAnUpdate() {
        assertEquals(false, hasUpdate(installed(11, "1.0"), module(10, "1.0", null)))
    }

    @Test
    fun sameCodeRepublishedAfterInstallIsAnUpdate() {
        val published = "2023-11-15T00:00:00Z" // after installTime (2023-11-14T22:13:20Z)
        assertTrue(hasUpdate(installed(10, "1.0"), module(10, "1.0b", published)))
    }

    @Test
    fun sameCodePublishedBeforeInstallIsNotAnUpdate() {
        // The Cleaner.Royall shape: the tag name never matches the APK name, so
        // the timestamp is the only thing that stops a permanent prompt.
        val published = "2023-11-14T00:00:00Z"
        assertEquals(false, hasUpdate(installed(10, "4.8"), module(10, "4.7_AraafRoyall", published)))
    }

    @Test
    fun sameCodeAndSameNameIsNotAnUpdate() {
        val published = "2023-11-15T00:00:00Z"
        assertEquals(false, hasUpdate(installed(10, "1.0"), module(10, "1.0", published)))
    }

    @Test
    fun unknownInstallTimestampFallsBackToCodeOnly() {
        val published = "2023-11-15T00:00:00Z"
        val cached = installed(10, "1.0").copy(lastUpdateTime = 0L)
        assertEquals(false, hasUpdate(cached, module(10, "1.0b", published)))
    }

    @Test
    fun betaChannelUsesTheBetaTimestamp() {
        val betaPublished = "2023-11-15T00:00:00Z"
        val stablePublished = "2023-11-01T00:00:00Z"
        val module = module(10, "1.0b", stablePublished).copy(
            latestBetaRelease = org.lspstore.domain.model.ModuleVersion(10, "1.0b", "10-1.0b"),
            latestBetaReleaseTime = betaPublished,
        )
        val updates = UpdateChecker.findUpdates(
            installed = listOf(installed(10, "1.0")),
            index = listOf(module),
            channel = UpdateChannel.BETA,
        )
        assertEquals(1, updates.size)
        assertEquals("1.0b", updates.first().latest.versionName)
    }
}
