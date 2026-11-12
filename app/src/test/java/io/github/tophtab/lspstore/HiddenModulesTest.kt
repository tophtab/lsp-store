package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.HiddenModules
import io.github.tophtab.lspstore.domain.model.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenModulesTest {

    private fun module(
        name: String,
        packageName: String = "dev.$name",
        summary: String? = null,
    ) = Module(
        packageName = packageName,
        title = name,
        summary = summary,
        url = null,
        homepageUrl = null,
        sourceUrl = null,
        updatedAt = null,
        stars = 0,
        contributor = null,
        contributorAvatarUrl = null,
        latestRelease = null,
        latestBetaRelease = null,
        latestSnapshotRelease = null,
        latestReleaseTime = null,
    )

    @Test
    fun `keyword matches title package and summary ignoring case and padding`() {
        val byTitle = module("Douyin Helper")
        val byPackage = module("Helper", packageName = "com.ss.android.ugc.aweme")
        val bySummary = module("Helper", summary = "抖音 视频工具")

        assertTrue(HiddenModules(keywords = listOf("  douyin ")).hides(byTitle))
        assertTrue(HiddenModules(keywords = listOf("UGC")).hides(byPackage))
        assertTrue(HiddenModules(keywords = listOf("抖音")).hides(bySummary))
    }

    @Test
    fun `blank keyword matches nothing`() {
        val any = module("Anything")
        assertFalse(HiddenModules(keywords = listOf("", "   ")).hides(any))
    }

    @Test
    fun `package and keyword rules are independent and both hide`() {
        val explicit = module("Explicit")
        val keywordMatch = module("TikTok")
        val visible = module("Visible")
        val hidden = HiddenModules(
            packages = setOf(explicit.packageName),
            keywords = listOf("tiktok"),
        )

        assertTrue(hidden.hides(explicit))
        assertTrue(hidden.hides(keywordMatch))
        assertFalse(hidden.hides(visible))
        assertEquals(listOf(visible), hidden.filter(listOf(explicit, keywordMatch, visible)))
    }
}
