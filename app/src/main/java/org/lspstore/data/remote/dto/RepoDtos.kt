package org.lspstore.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModuleListResponseDto(
    val modules: List<ModuleListItemDto> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
)

@Serializable
data class ModuleListItemDto(
    val name: String,
    val description: String? = null,
    val summary: String? = null,
    val url: String? = null,
    val homepageUrl: String? = null,
    val sourceUrl: String? = null,
    val updatedAt: String? = null,
    val stargazerCount: Int = 0,
    val firstContributor: String? = null,
    val firstContributorAvatarUrl: String? = null,
    val latestRelease: String? = null,
    val latestBetaRelease: String? = null,
    val latestSnapshotRelease: String? = null,
    val latestReleaseTime: String? = null,
    val latestBetaReleaseTime: String? = null,
    val latestSnapshotReleaseTime: String? = null,
)

@Serializable
data class OnlineModuleDto(
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val homepageUrl: String? = null,
    val collaborators: List<CollaboratorDto> = emptyList(),
    val summary: String? = null,
    val sourceUrl: String? = null,
    val hide: Boolean = false,
    val scope: List<String>? = null,
    val releases: List<ReleaseDto> = emptyList(),
    val betaReleases: List<ReleaseDto> = emptyList(),
    val snapshotReleases: List<ReleaseDto> = emptyList(),
    val latestRelease: String? = null,
    val latestBetaRelease: String? = null,
    val latestSnapshotRelease: String? = null,
    val latestReleaseTime: String? = null,
    val latestBetaReleaseTime: String? = null,
    val latestSnapshotReleaseTime: String? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
    val pushedAt: String? = null,
    val stargazerCount: Int = 0,
    val defaultBranch: String? = null,
    val readmeHTML: String? = null,
)

@Serializable
data class CollaboratorDto(
    val login: String? = null,
    val name: String? = null,
)

@Serializable
data class ReleaseDto(
    val id: String? = null,
    val databaseId: Long = 0,
    val name: String? = null,
    val url: String? = null,
    val isDraft: Boolean = false,
    val createdAt: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val tagName: String? = null,
    val isPrerelease: Boolean = false,
    val isLatest: Boolean = false,
    val isLatestBeta: Boolean = false,
    val isLatestSnapshot: Boolean = false,
    val description: String? = null,
    @SerialName("descriptionHTML")
    val descriptionHtml: String? = null,
    val releaseAssets: List<ReleaseAssetDto> = emptyList(),
)

@Serializable
data class ReleaseAssetDto(
    val name: String? = null,
    val contentType: String? = null,
    val downloadUrl: String? = null,
    val downloadCount: Int = 0,
    val size: Long = 0,
    val originalDownloadUrl: String? = null,
)
