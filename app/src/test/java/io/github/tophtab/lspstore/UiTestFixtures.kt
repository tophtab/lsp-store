package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo

internal fun uiAsset(
    name: String = "module.apk",
    downloads: Int = 0,
    size: Long = 1_024,
    baseUrl: String = "https://example.test",
): ReleaseAsset = ReleaseAsset(name, "$baseUrl/$name", null, size, downloads, null)

internal fun uiModule(packageName: String = "module", summary: String? = null): Module = Module(
    packageName = packageName,
    title = packageName,
    summary = summary,
    url = null,
    homepageUrl = null,
    sourceUrl = null,
    updatedAt = null,
    stars = 0,
    contributor = null,
    contributorAvatarUrl = null,
    latestRelease = ModuleVersion.parse("2-new"),
    latestBetaRelease = null,
    latestSnapshotRelease = null,
    latestReleaseTime = null,
)

internal fun uiInstalled(
    packageName: String = "module",
    label: String = packageName,
    code: Long = 1,
    name: String = "old",
    xposed: Boolean = true,
    updatedAt: Long = 0,
): InstalledModule = InstalledModule(
    packageName = packageName,
    versionCode = code,
    versionName = name,
    label = label,
    isXposedModule = xposed,
    description = null,
    lastUpdateTime = updatedAt,
)

internal fun uiRelease(tag: String = "2-new", names: List<String> = listOf("module.apk")): Release = Release(
    tagName = tag,
    name = tag,
    url = null,
    isPrerelease = false,
    publishedAt = null,
    descriptionHtml = "Release notes",
    version = ModuleVersion.parse(tag),
    assets = names.map { uiAsset(it) },
)

internal fun uiDetail(
    module: Module = uiModule(),
    readme: String? = null,
    releases: List<Release> = listOf(uiRelease()),
): ModuleDetail = ModuleDetail(module, readme, emptyList(), emptyList(), releases, emptyList(), emptyList(), null, null)

internal fun uiUpdate(packageName: String = "module", installedPackage: String = packageName): UpdateInfo {
    val module = uiModule(packageName)
    return UpdateInfo(module, uiInstalled(installedPackage), module.latestRelease!!)
}
