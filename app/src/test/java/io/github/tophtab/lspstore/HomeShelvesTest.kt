package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.ui.screens.home.HomeShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelvesTest {
    private fun module(name: String, stars: Int, date: String? = null, summary: String? = null) = Module(
        packageName = "dev.$name", title = name, summary = summary, url = null,
        homepageUrl = null, sourceUrl = null, updatedAt = null, stars = stars,
        contributor = null, contributorAvatarUrl = null,
        latestRelease = date?.let { ModuleVersion(1, "1", "1-1") },
        latestBetaRelease = null, latestSnapshotRelease = null, latestReleaseTime = date,
    )

    @Test
    fun `shelves preserve publication ordering star ties and zero star modules`() {
        val old = module("Bravo", 10, "2026-01-01T00:00:00Z")
        val fresh = module("Alpha", 10, "2026-02-01T00:00:00Z")
        val unpublished = module("Zero", 0)
        val shelves = HomeShelves.build(listOf(old, unpublished, fresh), emptySet())
        assertEquals(listOf(fresh, old, unpublished), shelves.recentlyUpdated)
        assertEquals(listOf(fresh, old, unpublished), shelves.mostStarred)
    }

    @Test
    fun `search filters the publication order and honours ignored modules`() {
        val old = module("Alpha", 100, "2026-01-01T00:00:00Z", "TOOLS")
        val fresh = module("Beta", 0, "2026-02-01T00:00:00Z", "tools")
        val hidden = module("Hidden", 1000, "2026-03-01T00:00:00Z", "tools")
        val shelves = HomeShelves.build(listOf(old, hidden, fresh), setOf(hidden.packageName))
        assertEquals(listOf(fresh, old), shelves.search("  Tools  "))
        assertEquals(listOf(fresh), shelves.search("DEV.BETA"))
        assertEquals(listOf(old), shelves.search("alpha"))
        assertTrue(shelves.search(" ").isEmpty())
        assertTrue(shelves.search("hidden").isEmpty())
        assertEquals(listOf(old, fresh), shelves.mostStarred)
    }

    @Test
    fun `catalogue clearing clears both shelves`() {
        assertEquals(HomeShelves(), HomeShelves.build(emptyList(), emptySet()))
    }
}
