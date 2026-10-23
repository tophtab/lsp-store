package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import java.time.Instant

/**
 * The module page's three stream buttons (stable / pre-release / all) have to
 * pick the same versions the update checker would for the two streams it knows,
 * and simply add snapshots for "all".
 */
class ReleaseChannelTest {

    private fun release(
        raw: String,
        publishedAt: String,
        isPrerelease: Boolean = false,
    ): Release = Release(
        tagName = raw,
        name = null,
        url = null,
        isPrerelease = isPrerelease,
        publishedAt = Instant.parse(publishedAt),
        descriptionHtml = null,
        version = ModuleVersion.parse(raw),
        assets = listOf(
            ReleaseAsset(
                name = "$raw.apk",
                downloadUrl = "https://example.test/$raw.apk",
                fallbackUrl = null,
                size = 1024,
                downloadCount = 0,
                contentType = null,
            ),
        ),
    )

    private fun detail(
        latestStable: ModuleVersion?,
        latestBeta: ModuleVersion?,
        latestSnapshot: ModuleVersion? = null,
        stable: List<Release> = emptyList(),
        beta: List<Release> = emptyList(),
        snapshot: List<Release> = emptyList(),
    ): ModuleDetail = ModuleDetail(
        module = Module(
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
            latestRelease = latestStable,
            latestBetaRelease = latestBeta,
            latestSnapshotRelease = latestSnapshot,
            latestReleaseTime = "2026-01-01T00:00:00Z",
            latestBetaReleaseTime = "2026-01-02T00:00:00Z",
            latestSnapshotReleaseTime = "2026-01-03T00:00:00Z",
        ),
        readmeHtml = null,
        collaborators = emptyList(),
        scope = emptyList(),
        releases = stable,
        betaReleases = beta,
        snapshotReleases = snapshot,
        sourceUrl = null,
        homepageUrl = null,
    )

    private fun tags(releases: List<Release>) = releases.map { it.tagName }

    @Test
    fun stableChannelListsOnlyStableReleases() {
        val stable = release("60-3.5.0", "2026-01-01T00:00:00Z")
        val beta = release("37-2.7.0-beta", "2026-01-02T00:00:00Z", isPrerelease = true)
        val detail = detail(stable.version, beta.version, stable = listOf(stable), beta = listOf(beta))

        assertEquals(listOf("60-3.5.0"), tags(detail.releasesFor(ReleaseChannel.STABLE)))
        assertEquals("60-3.5.0", detail.latestReleaseFor(ReleaseChannel.STABLE)?.tagName)
    }

    @Test
    fun preReleaseChannelMergesBothStreamsNewestFirst() {
        val stable = release("60-3.5.0", "2026-01-01T00:00:00Z")
        val beta = release("37-2.7.0-beta", "2026-01-02T00:00:00Z", isPrerelease = true)
        val detail = detail(stable.version, beta.version, stable = listOf(stable), beta = listOf(beta))

        assertEquals(
            listOf("37-2.7.0-beta", "60-3.5.0"),
            tags(detail.releasesFor(ReleaseChannel.PRE_RELEASE)),
        )
        // 24 of 29 modules publish a beta older than their stable build, so the
        // pre-release channel must still pick the stable one there.
        assertEquals("60-3.5.0", detail.latestReleaseFor(ReleaseChannel.PRE_RELEASE)?.tagName)
    }

    @Test
    fun allChannelAddsSnapshots() {
        val stable = release("60-3.5.0", "2026-01-01T00:00:00Z")
        val beta = release("61-3.6.0-beta", "2026-01-02T00:00:00Z", isPrerelease = true)
        val snapshot = release("62-3.7.0-snapshot", "2026-01-03T00:00:00Z", isPrerelease = true)
        val detail = detail(
            latestStable = stable.version,
            latestBeta = beta.version,
            latestSnapshot = snapshot.version,
            stable = listOf(stable),
            beta = listOf(beta),
            snapshot = listOf(snapshot),
        )

        assertEquals(
            listOf("62-3.7.0-snapshot", "61-3.6.0-beta", "60-3.5.0"),
            tags(detail.releasesFor(ReleaseChannel.ALL)),
        )
        assertEquals("62-3.7.0-snapshot", detail.latestReleaseFor(ReleaseChannel.ALL)?.tagName)
        // "All" also reaches a snapshot-only module, which pre-release mode skips.
        assertEquals(62L, detail.module.latestFor(ReleaseChannel.ALL)?.versionCode)
    }

    @Test
    fun moduleWithoutPreReleasesOffersNothingOnThePreReleaseChannel() {
        val stable = release("60-3.5.0", "2026-01-01T00:00:00Z")
        val detail = detail(latestStable = stable.version, latestBeta = null, stable = listOf(stable))

        assertTrue(!detail.module.hasPreReleases)
        assertEquals(tags(detail.releasesFor(ReleaseChannel.STABLE)), tags(detail.releasesFor(ReleaseChannel.PRE_RELEASE)))
        assertEquals(tags(detail.releasesFor(ReleaseChannel.STABLE)), tags(detail.releasesFor(ReleaseChannel.ALL)))
        assertNull(detail.module.latestSnapshotRelease)
    }
}
