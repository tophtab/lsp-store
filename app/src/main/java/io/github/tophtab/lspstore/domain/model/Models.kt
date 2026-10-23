package io.github.tophtab.lspstore.domain.model

import java.time.Instant
import kotlinx.serialization.Serializable

enum class SortOrder {
    UPDATED,
    STARS,
    NAME,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Which release streams a module page offers. The page shows one stream picker
 * (stable / pre-release / all) instead of a single binary opt-in, so the same
 * three buttons work for modules that never published a pre-release too.
 */
enum class ReleaseChannel {
    /** Tagged stable releases only: the stream update checks use by default. */
    STABLE,

    /** Stable plus pre-release builds (what "track pre-releases" shows). */
    PRE_RELEASE,

    /** Every stream the repository exposes, including snapshots. */
    ALL,
}

data class ModuleVersion(
    val versionCode: Long,
    val versionName: String,
    val raw: String,
) {
    /** LSPosed's upgradability rule: higher code wins, or same code with a different name. */
    fun isNewerThan(installedCode: Long, installedName: String?): Boolean {
        if (versionCode > installedCode) return true
        if (versionCode != installedCode) return false
        val normalizedInstalled = installedName.orEmpty().replace(' ', '_')
        return versionName.replace(' ', '_') != normalizedInstalled
    }

    companion object {
        fun parse(raw: String?): ModuleVersion? {
            if (raw.isNullOrBlank()) return null
            val idx = raw.indexOf('-')
            if (idx <= 0) return null
            val code = raw.substring(0, idx).toLongOrNull() ?: return null
            return ModuleVersion(code, raw.substring(idx + 1), raw)
        }
    }
}

/**
 * A version the user chose to skip. New entries keep the repository tag
 * (`code-versionName`) so a module that reuses one versionCode for every release
 * (about 18% of the repository) only skips the version the user actually saw.
 *
 * Entries written by older builds contain only the numeric code; those keep the
 * old code-wide behaviour.
 */
data class SkippedVersion(val raw: String) {
    val versionCode: Long?
        get() = raw.substringBefore('-').toLongOrNull()

    /** The author's version name, without the internal `versionCode-` prefix. */
    val versionName: String
        get() = raw.substringAfter('-', "").ifBlank { raw }

    fun matches(version: ModuleVersion): Boolean =
        raw == version.raw || raw.toLongOrNull() == version.versionCode
}

data class Module(
    val packageName: String,
    val title: String,
    val summary: String?,
    val url: String?,
    val homepageUrl: String?,
    val sourceUrl: String?,
    val updatedAt: Instant?,
    val stars: Int,
    val contributor: String?,
    val contributorAvatarUrl: String?,
    val latestRelease: ModuleVersion?,
    val latestBetaRelease: ModuleVersion?,
    val latestSnapshotRelease: ModuleVersion?,
    val latestReleaseTime: String?,
    val latestBetaReleaseTime: String? = null,
    val latestSnapshotReleaseTime: String? = null,
) {
    /** Whether the author ever published a pre-release for this module. */
    val hasPreReleases: Boolean
        get() = latestBetaRelease != null

    /**
     * Latest version to offer, optionally following the pre-release stream.
     *
     * Following pre-releases does **not** mean "beta wins": 24 of the 29 modules
     * that publish pre-releases ship a beta whose versionCode is older than
     * their stable, so taking the beta unconditionally would hide stable
     * updates. Both streams are compared and the newer one wins, which is what
     * "track this module's pre-release updates" has to mean in practice.
     */
    fun latestFor(includePreReleases: Boolean): ModuleVersion? =
        chosenStream(includePreReleases).first

    /** Publication time of the tag that [latestFor] returns. */
    fun latestTimeFor(includePreReleases: Boolean): String? =
        chosenStream(includePreReleases).second

    /**
     * Newest version across the streams [channel] offers. Same rule as
     * [chosenStream]: a higher versionCode wins, and equal codes fall back to the
     * stream that was published last.
     */
    fun latestFor(channel: ReleaseChannel): ModuleVersion? = newestOf(
        when (channel) {
            ReleaseChannel.STABLE -> listOf(latestRelease to latestReleaseTime)
            ReleaseChannel.PRE_RELEASE -> listOf(
                latestRelease to latestReleaseTime,
                latestBetaRelease to latestBetaReleaseTime,
            )

            ReleaseChannel.ALL -> listOf(
                latestRelease to latestReleaseTime,
                latestBetaRelease to latestBetaReleaseTime,
                latestSnapshotRelease to latestSnapshotReleaseTime,
            )
        },
    )

    private fun newestOf(streams: List<Pair<ModuleVersion?, String?>>): ModuleVersion? =
        streams
            .filter { it.first != null }
            .maxWithOrNull(
                compareBy<Pair<ModuleVersion?, String?>> { it.first!!.versionCode }
                    .thenBy { it.second.orEmpty() },
            )
            ?.first

    /**
     * The version and publication time to compare against the installed copy.
     * A higher versionCode wins; on identical codes the stream that was
     * published last wins, which is how an author re-uploading a tag under the
     * same code still shows up as an update.
     */
    private fun chosenStream(includePreReleases: Boolean): Pair<ModuleVersion?, String?> {
        val stable = latestRelease
        if (!includePreReleases) return stable to latestReleaseTime
        val beta = latestBetaRelease ?: return stable to latestReleaseTime
        val betaTime = latestBetaReleaseTime ?: latestReleaseTime
        if (stable == null) return beta to betaTime
        return when {
            beta.versionCode > stable.versionCode -> beta to betaTime
            beta.versionCode < stable.versionCode -> stable to latestReleaseTime
            // ISO-8601 timestamps with the same zone sort correctly as strings.
            betaTime.orEmpty() > latestReleaseTime.orEmpty() -> beta to betaTime
            else -> stable to latestReleaseTime
        }
    }
}

data class ModuleDetail(
    val module: Module,
    val readmeHtml: String?,
    val collaborators: List<String>,
    val scope: List<String>,
    val releases: List<Release>,
    val betaReleases: List<Release>,
    val snapshotReleases: List<Release>,
    val sourceUrl: String?,
    val homepageUrl: String?,
) {
    /**
     * Releases to list, newest first. With pre-releases enabled both streams are
     * merged (de-duplicated by tag) so the beta builds show up in the version
     * picker; otherwise only the stable stream is offered.
     */
    fun releasesFor(includePreReleases: Boolean): List<Release> =
        releasesFor(if (includePreReleases) ReleaseChannel.PRE_RELEASE else ReleaseChannel.STABLE)

    /** Releases offered by [channel], newest first, merged and de-duplicated by tag. */
    fun releasesFor(channel: ReleaseChannel): List<Release> {
        val streams = when (channel) {
            ReleaseChannel.STABLE -> listOf(releases)
            ReleaseChannel.PRE_RELEASE -> listOf(releases, betaReleases)
            ReleaseChannel.ALL -> listOf(releases, betaReleases, snapshotReleases)
        }
        val seen = HashSet<String>()
        return streams.flatten()
            .filter { seen.add(it.tagName) }
            .sortedByDescending { it.publishedAt }
    }

    fun latestReleaseFor(includePreReleases: Boolean): Release? = latestReleaseFor(
        if (includePreReleases) ReleaseChannel.PRE_RELEASE else ReleaseChannel.STABLE,
    )

    fun latestReleaseFor(channel: ReleaseChannel): Release? {
        val target = module.latestFor(channel)
        val candidates = releasesFor(channel)
        return candidates.firstOrNull { it.tagName == target?.raw }
            ?: candidates.firstOrNull { it.apkAssets.isNotEmpty() }
    }
}

data class Release(
    val tagName: String,
    val name: String?,
    val url: String?,
    val isPrerelease: Boolean,
    val publishedAt: Instant?,
    val descriptionHtml: String?,
    val version: ModuleVersion?,
    val assets: List<ReleaseAsset>,
) {
    val apkAssets: List<ReleaseAsset>
        get() = assets.filter { it.isApk }

    /**
     * Whether this release is the exact version an update check promised. A
     * stale mirror can return a detail payload that is days behind the index;
     * downloading its "latest" APK would install an older build.
     */
    fun matchesVersion(version: ModuleVersion): Boolean {
        val releaseVersion = this.version ?: return false
        return releaseVersion.versionCode == version.versionCode &&
            releaseVersion.versionName.replace(' ', '_') ==
            version.versionName.replace(' ', '_')
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val fallbackUrl: String?,
    val size: Long,
    val downloadCount: Int,
    val contentType: String?,
) {
    val isApk: Boolean
        get() = name.endsWith(".apk", ignoreCase = true) ||
            contentType?.contains("android.package", ignoreCase = true) == true
}

@Serializable
data class InstalledModule(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val label: String,
    val isXposedModule: Boolean,
    val description: String?,
    // Added for the variant seed (P0). Defaults keep old caches readable.
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val primaryCpuAbi: String? = null,
    val debuggable: Boolean = false,
    val hasLibXposedProvider: Boolean = false,
    /** PackageManager install/update timestamp; 0 when an old cache had no value. */
    val lastUpdateTime: Long = 0L,
)

data class UpdateInfo(
    val module: Module,
    val installed: InstalledModule,
    val latest: ModuleVersion,
)

/**
 * An install handed to an external installer that has not been confirmed by a
 * package broadcast yet. Mirrors komi-store's `isPendingInstall` state.
 */
@Serializable
data class PendingInstall(
    val packageName: String,
    val repoPackageName: String? = null,
    val title: String = "",
    val versionCode: Long = 0L,
    val versionName: String = "",
    val assetName: String = "",
    val createdAt: Long = 0L,
)
