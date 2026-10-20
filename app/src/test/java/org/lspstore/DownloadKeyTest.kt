package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.lspstore.data.install.assetCacheKey
import org.lspstore.domain.model.ReleaseAsset

class DownloadKeyTest {

    private fun asset(
        name: String,
        url: String,
        size: Long = 100,
        fallback: String? = null,
    ) = ReleaseAsset(
        name = name,
        downloadUrl = url,
        fallbackUrl = fallback,
        size = size,
        downloadCount = 0,
        contentType = "application/vnd.android.package-archive",
    )

    @Test
    fun sameAssetNameFromDifferentModulesGetsDifferentKeys() {
        val first = asset("app-release.apk", "https://assets.lsposed.org/a/module.one/app-release.apk")
        val second = asset("app-release.apk", "https://assets.lsposed.org/a/module.two/app-release.apk")

        assertNotEquals(assetCacheKey(first), assetCacheKey(second))
    }

    @Test
    fun sameModuleAcrossReleasesGetsDifferentKeys() {
        val v1 = asset("app-release.apk", "https://assets.lsposed.org/a/module.one/v1/app-release.apk")
        val v2 = asset("app-release.apk", "https://assets.lsposed.org/a/module.one/v2/app-release.apk")

        assertNotEquals(assetCacheKey(v1), assetCacheKey(v2))
    }

    @Test
    fun identicalAssetKeepsStableKey() {
        val asset = asset("module.apk", "https://assets.lsposed.org/a/module.apk", size = 42)
        assertEquals(assetCacheKey(asset), assetCacheKey(asset.copy()))
    }

    @Test
    fun fallbackUrlAndSizeParticipateInIdentity() {
        val base = asset("module.apk", "https://cdn.example/module.apk")
        assertNotEquals(
            assetCacheKey(base),
            assetCacheKey(base.copy(fallbackUrl = "https://github.com/other/module.apk")),
        )
        assertNotEquals(assetCacheKey(base), assetCacheKey(base.copy(size = 43)))
    }
}
