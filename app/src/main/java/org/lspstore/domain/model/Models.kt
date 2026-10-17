package org.lspstore.domain.model

import java.time.Instant

enum class UpdateChannel(val key: String) {
    STABLE("CHANNEL_STABLE"),
    BETA("CHANNEL_BETA"),
    NIGHTLY("CHANNEL_NIGHTLY");

    companion object {
        fun fromKey(key: String?): UpdateChannel =
            entries.firstOrNull { it.key == key } ?: STABLE
    }
}

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
) {
    fun latestFor(channel: UpdateChannel): ModuleVersion? = when (channel) {
        UpdateChannel.STABLE -> latestRelease
        UpdateChannel.BETA -> latestBetaRelease ?: latestRelease
        UpdateChannel.NIGHTLY -> latestSnapshotRelease ?: latestBetaRelease ?: latestRelease
    }

    fun hasChannel(channel: UpdateChannel): Boolean = when (channel) {
        UpdateChannel.STABLE -> latestRelease != null
        UpdateChannel.BETA -> latestBetaRelease != null
        UpdateChannel.NIGHTLY -> latestSnapshotRelease != null
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
    fun releasesFor(channel: UpdateChannel): List<Release> = when (channel) {
        UpdateChannel.STABLE -> releases
        UpdateChannel.BETA -> betaReleases.ifEmpty { releases }
        UpdateChannel.NIGHTLY -> snapshotReleases.ifEmpty { betaReleases.ifEmpty { releases } }
    }

    fun latestReleaseFor(channel: UpdateChannel): Release? {
        val target = module.latestFor(channel)
        val channelReleases = releasesFor(channel)
        return channelReleases.firstOrNull { it.tagName == target?.raw }
            ?: channelReleases.firstOrNull { it.apkAssets.isNotEmpty() }
    }

    fun latestAssetFor(channel: UpdateChannel): ReleaseAsset? {
        val target = module.latestFor(channel)
        val channelReleases = releasesFor(channel)
        val release = channelReleases.firstOrNull { it.tagName == target?.raw }
            ?: channelReleases.firstOrNull { it.apkAssets.isNotEmpty() }
            ?: return null
        return release.apkAssets.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
            ?: release.apkAssets.firstOrNull()
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

data class InstalledModule(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val label: String,
    val isXposedModule: Boolean,
    val description: String?,
)

data class UpdateInfo(
    val module: Module,
    val installed: InstalledModule,
    val latest: ModuleVersion,
)
