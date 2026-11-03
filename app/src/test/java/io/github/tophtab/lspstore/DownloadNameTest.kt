package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tophtab.lspstore.data.install.exportedApkName
import io.github.tophtab.lspstore.domain.model.ReleaseAsset

class DownloadNameTest {

    private val key = "0123456789abcdef"

    private fun asset(name: String, url: String) = ReleaseAsset(
        name = name,
        downloadUrl = url,
        fallbackUrl = null,
        size = 100,
        downloadCount = 0,
        contentType = "application/vnd.android.package-archive",
    )

    @Test
    fun genericGithubAssetUsesRepositoryName() {
        val name = exportedApkName(
            key,
            asset("app-release.apk", "https://github.com/owner/Wren/releases/download/v1.2/app-release.apk"),
        )
        assertEquals("Wren-$key.apk", name)
    }

    @Test
    fun genericLsposedAssetUsesPackageName() {
        val name = exportedApkName(
            key,
            asset(
                "module.apk",
                "https://assets.lsposed.org/a/com.example.module/1.2.3/module.apk",
            ),
        )
        assertEquals("com.example.module-$key.apk", name)
    }

    @Test
    fun descriptiveNameIsKeptAndExtensionStaysLast() {
        val name = exportedApkName(
            key,
            asset("Wren-v1.2.apk", "https://example.com/Wren-v1.2.apk"),
        )
        assertEquals("Wren-v1.2-$key.apk", name)
    }

    @Test
    fun genericWithoutUrlHintFallsBackToAssetStem() {
        val name = exportedApkName(
            key,
            asset("app-release.apk", "https://example.com/download"),
        )
        assertEquals("app-release-$key.apk", name)
    }

    @Test
    fun unsafeCharactersAreReplaced() {
        val name = exportedApkName(
            key,
            asset("Wren build #2.apk", "https://example.com/Wren build #2.apk"),
        )
        assertEquals("Wren_build__2-$key.apk", name)
    }
}
