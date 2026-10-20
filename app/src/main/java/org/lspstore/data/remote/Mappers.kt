package org.lspstore.data.remote

import org.lspstore.data.remote.dto.CollaboratorDto
import org.lspstore.data.remote.dto.FullModuleIndexDto
import org.lspstore.data.remote.dto.ModuleListItemDto
import org.lspstore.data.remote.dto.OnlineModuleDto
import org.lspstore.data.remote.dto.ReleaseAssetDto
import org.lspstore.data.remote.dto.ReleaseDto
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import java.time.Instant

private fun parseInstant(raw: String?): Instant? =
    raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Converts the single-file `/modules.json` payload into the same list DTO used by
 * the paginated index, so the cache format stays unchanged.
 */
fun FullModuleIndexDto.toModuleListItem(): ModuleListItemDto {
    val login = collaborators.firstOrNull()?.login?.takeIf { it.isNotBlank() }
    val contributorName = collaborators.firstOrNull()?.name?.takeIf { it.isNotBlank() }
    return ModuleListItemDto(
        name = this.name,
        description = this.description,
        summary = summary,
        url = url,
        homepageUrl = homepageUrl,
        sourceUrl = sourceUrl,
        updatedAt = updatedAt,
        stargazerCount = stargazerCount,
        firstContributor = login ?: contributorName,
        firstContributorAvatarUrl = login?.let { "https://github.com/$it.png?size=200" },
        latestRelease = latestRelease,
        latestBetaRelease = latestBetaRelease,
        latestSnapshotRelease = latestSnapshotRelease,
        latestReleaseTime = latestReleaseTime,
        latestBetaReleaseTime = latestBetaReleaseTime,
        latestSnapshotReleaseTime = latestSnapshotReleaseTime,
    )
}

fun ModuleListItemDto.toModule(): Module = Module(
    packageName = name,
    title = description?.takeIf { it.isNotBlank() } ?: name,
    summary = summary?.takeIf { it.isNotBlank() },
    url = url,
    homepageUrl = homepageUrl?.takeIf { it.isNotBlank() },
    sourceUrl = sourceUrl?.takeIf { it.isNotBlank() },
    updatedAt = parseInstant(updatedAt),
    stars = stargazerCount,
    contributor = firstContributor,
    contributorAvatarUrl = firstContributorAvatarUrl,
    latestRelease = ModuleVersion.parse(latestRelease),
    latestBetaRelease = ModuleVersion.parse(latestBetaRelease),
    latestSnapshotRelease = ModuleVersion.parse(latestSnapshotRelease),
    latestReleaseTime = latestReleaseTime,
    latestBetaReleaseTime = latestBetaReleaseTime,
    latestSnapshotReleaseTime = latestSnapshotReleaseTime,
)

fun OnlineModuleDto.toModule(): Module = Module(
    packageName = name,
    title = description?.takeIf { it.isNotBlank() } ?: name,
    summary = summary?.takeIf { it.isNotBlank() },
    url = url,
    homepageUrl = homepageUrl?.takeIf { it.isNotBlank() },
    sourceUrl = sourceUrl?.takeIf { it.isNotBlank() },
    updatedAt = parseInstant(updatedAt),
    stars = stargazerCount,
    contributor = collaborators.firstOrNull()?.login?.takeIf { it.isNotBlank() }
        ?: collaborators.firstOrNull()?.name?.takeIf { it.isNotBlank() },
    contributorAvatarUrl = collaborators.firstOrNull()
        ?.login
        ?.takeIf { it.isNotBlank() }
        ?.let { "https://github.com/$it.png?size=200" },
    latestRelease = ModuleVersion.parse(latestRelease),
    latestBetaRelease = ModuleVersion.parse(latestBetaRelease),
    latestSnapshotRelease = ModuleVersion.parse(latestSnapshotRelease),
    latestReleaseTime = latestReleaseTime,
    latestBetaReleaseTime = latestBetaReleaseTime,
    latestSnapshotReleaseTime = latestSnapshotReleaseTime,
)

fun OnlineModuleDto.toDetail(): ModuleDetail = ModuleDetail(
    module = toModule(),
    readmeHtml = readmeHTML,
    collaborators = (collaborators.mapNotNull { it.displayName() } +
        additionalAuthors.mapNotNull { it.name?.takeIf(String::isNotBlank) }).distinct(),
    scope = scope.orEmpty(),
    releases = releases.map { it.toRelease() },
    betaReleases = betaReleases.map { it.toRelease() },
    snapshotReleases = snapshotReleases.map { it.toRelease() },
    sourceUrl = sourceUrl?.takeIf { it.isNotBlank() },
    homepageUrl = homepageUrl?.takeIf { it.isNotBlank() },
)

private fun CollaboratorDto.displayName(): String? =
    name?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() }

fun ReleaseDto.toRelease(): Release = Release(
    tagName = tagName.orEmpty(),
    name = name?.takeIf { it.isNotBlank() },
    url = url,
    isPrerelease = isPrerelease,
    publishedAt = parseInstant(publishedAt ?: createdAt),
    descriptionHtml = descriptionHtml ?: description,
    version = ModuleVersion.parse(tagName),
    assets = releaseAssets.map { it.toAsset() },
)

private fun ReleaseAssetDto.toAsset(): ReleaseAsset = ReleaseAsset(
    name = name.orEmpty(),
    downloadUrl = downloadUrl.orEmpty(),
    fallbackUrl = originalDownloadUrl?.takeIf { it.isNotBlank() },
    size = size,
    downloadCount = downloadCount,
    contentType = contentType,
)
