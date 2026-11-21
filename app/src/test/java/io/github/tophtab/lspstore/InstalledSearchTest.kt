package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.ui.screens.installed.matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledSearchTest {

    private fun installed(
        label: String,
        packageName: String = "dev.$label",
        description: String? = null,
    ) = InstalledModule(
        packageName = packageName,
        versionCode = 1,
        versionName = "1.0",
        label = label,
        isXposedModule = true,
        description = description,
    )

    private fun repo(
        title: String,
        packageName: String = "dev.$title",
        summary: String? = null,
    ) = Module(
        packageName = packageName,
        title = title,
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
    fun `blank query keeps installed rows`() {
        val rows = listOf(installed("Douyin"), installed("WeChat"))
        assertEquals(rows, rows.matching(""))
        assertEquals(rows, rows.matching("   "))
    }

    @Test
    fun `installed search requires every term within one row`() {
        val douyin = installed("抖音", description = "微信 登录工具")
        val wechat = installed("微信")
        val rows = listOf(douyin, wechat)

        assertEquals(listOf(douyin), rows.matching("抖音 微信"))
        assertEquals(listOf(douyin), rows.matching(" \u3000抖音\tdev.抖音 "))
        assertEquals(rows, rows.matching("微信"))
        assertTrue(rows.matching("抖音 不存在的词").isEmpty())
    }

    @Test
    fun `installed rows also match their repository entry`() {
        val better = installed("BetterHeybox", packageName = "com.better.heybox")
        val repository = mapOf(
            "com.better.heybox" to repo(
                "BetterHeybox——小黑盒增强",
                packageName = "com.better.heybox",
                summary = "为小黑盒 App 添加更多功能项",
            ),
        )

        assertEquals(listOf(better), listOf(better).matching("小黑盒", repository))
        assertEquals(listOf(better), listOf(better).matching("小黑盒 增强", repository))
        assertTrue(listOf(better).matching("小黑盒", emptyMap()).isEmpty())
    }

    @Test
    fun `installed search combines installed and repository fields`() {
        val row = installed("BetterHeybox", packageName = "com.better.heybox", description = "English only")
        val repository = mapOf(
            "com.better.heybox" to repo(
                "BetterHeybox——小黑盒增强",
                packageName = "com.better.heybox",
                summary = "为小黑盒 App 添加更多功能项",
            ),
        )

        assertEquals(listOf(row), listOf(row).matching("english 小黑盒", repository))
        assertEquals(listOf(row), listOf(row).matching(" \u3000小黑盒\tenglish ", repository))
        assertTrue(listOf(row).matching("english 不存在的词", repository).isEmpty())
    }

    @Test
    fun `update rows follow the installed row rule`() {
        val douyin = UpdateInfo(
            module = repo("Douyin Helper"),
            installed = installed("抖音"),
            latest = ModuleVersion(1, "1", "1-1"),
        )
        val wechat = UpdateInfo(
            module = repo("WeChat"),
            installed = installed("微信"),
            latest = ModuleVersion(1, "1", "1-1"),
        )
        val rows = listOf(douyin, wechat)

        assertEquals(rows, rows.matching("", completed = emptySet()))
        assertEquals(listOf(douyin), rows.matching("douyin 抖音", completed = emptySet()))
        assertEquals(listOf(wechat), rows.matching("微信", completed = emptySet()))
        assertTrue(rows.matching("douyin 微信", completed = emptySet()).isEmpty())
        assertEquals(
            listOf(wechat),
            rows.matching("", completed = setOf(douyin.module.packageName)),
        )
    }
}
