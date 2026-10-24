package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The repository's `updatedAt` is GitHub's repo-level `updated_at`: a star or a
 * README edit refreshes it, so it must not be shown as the update time when the
 * module has ever published a release.
 */
class ModuleUpdatedAtTest {

    private fun module(
        repoUpdatedAt: String?,
        stable: String? = null,
        beta: String? = null,
        snapshot: String? = null,
        stableTime: String? = null,
        betaTime: String? = null,
        snapshotTime: String? = null,
    ): Module = Module(
        packageName = "a.b",
        title = "T",
        summary = null,
        url = null,
        homepageUrl = null,
        sourceUrl = null,
        updatedAt = repoUpdatedAt?.let(Instant::parse),
        stars = 0,
        contributor = null,
        contributorAvatarUrl = null,
        latestRelease = ModuleVersion.parse(stable),
        latestBetaRelease = ModuleVersion.parse(beta),
        latestSnapshotRelease = ModuleVersion.parse(snapshot),
        latestReleaseTime = stableTime,
        latestBetaReleaseTime = betaTime,
        latestSnapshotReleaseTime = snapshotTime,
    )

    @Test
    fun `a star that bumps updatedAt is not a release`() {
        // HyperClipFix on 2026-10-24: a WatchEvent set updatedAt, while the
        // release and the last push were days older.
        val hyperClipFix = module(
            repoUpdatedAt = "2026-10-24T02:18:44Z",
            stable = "118-2.0.3",
            stableTime = "2026-10-22T04:34:14Z",
        )

        assertEquals(Instant.parse("2026-10-22T04:34:14Z"), hyperClipFix.lastPublishedAt)
    }

    @Test
    fun `the newest stream wins`() {
        val betaNewer = module(
            repoUpdatedAt = "2026-10-24T02:18:44Z",
            stable = "1-1.0",
            beta = "2-2.0",
            snapshot = "3-3.0",
            stableTime = "2026-10-20T00:00:00Z",
            betaTime = "2026-10-22T00:00:00Z",
            snapshotTime = "2026-10-23T00:00:00Z",
        )

        assertEquals(Instant.parse("2026-10-23T00:00:00Z"), betaNewer.lastPublishedAt)
    }

    @Test
    fun `a timestamp without a version does not count`() {
        // The list payload repeats the stable time in the beta/snapshot fields
        // even when no such release exists; only streams with a tag count.
        val betaOnly = module(
            repoUpdatedAt = "2026-10-24T02:18:44Z",
            beta = "2-2.0",
            stableTime = "2026-10-20T00:00:00Z",
            betaTime = "2026-10-22T00:00:00Z",
            snapshotTime = "2026-10-23T00:00:00Z",
        )

        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), betaOnly.lastPublishedAt)
    }

    @Test
    fun `modules without releases have no update time`() {
        val noReleases = module(repoUpdatedAt = "2026-10-24T02:18:44Z")

        assertNull(noReleases.lastPublishedAt)
    }

    @Test
    fun `no timestamps at all is null`() {
        assertNull(module(repoUpdatedAt = null).lastPublishedAt)
    }
}
