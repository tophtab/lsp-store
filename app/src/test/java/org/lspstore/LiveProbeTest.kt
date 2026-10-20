package org.lspstore

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lspstore.data.probe.RangeProbe
import org.lspstore.data.remote.createJson
import org.lspstore.data.remote.createProbeHttpClient
import org.lspstore.domain.model.ReleaseAsset

/**
 * Hits the real GitHub release CDN with [RangeProbe]. Opt-in like [LiveRepoTest]:
 *
 *     LIVE_REPO_TEST=1 ./gradlew :app:testDebugUnitTest --tests org.lspstore.LiveProbeTest
 *
 * Covers the three shapes seen in the wild: a legacy-xposed APK, a libxposed
 * `META-INF/xposed/module.prop` APK, and an asset whose CDN rejects range
 * requests (502/501) so the whole-file fallback is exercised.
 */
class LiveProbeTest {

    private val json: Json = createJson()

    private fun asset(pkg: String, name: String): ReleaseAsset {
        val text = File("src/test/resources/variant-corpus.json").readText()
        val modules = json.parseToJsonElement(text).jsonArray
        val row = modules.first { it.jsonObject["pkg"]!!.jsonPrimitive.content == pkg }
        val release = row.jsonObject["releases"]!!.jsonArray
            .first { it.jsonObject["latest"]?.jsonPrimitive?.content == "true" }
        val apk = release.jsonObject["apks"]!!.jsonArray
            .first { it.jsonArray[0].jsonPrimitive.content == name }
        val size = apk.jsonArray[1].jsonPrimitive.long
        return ReleaseAsset(
            name = name,
            downloadUrl = "https://github.com/Xposed-Modules-Repo/$pkg/releases/download/" +
                "${release.jsonObject["tag"]!!.jsonPrimitive.content}/$name",
            fallbackUrl = null,
            size = size,
            downloadCount = 0,
            contentType = null,
        )
    }

    private fun probe(): RangeProbe {
        val dir = File(System.getProperty("java.io.tmpdir"), "live-probe-${System.nanoTime()}")
        return RangeProbe(createProbeHttpClient(), dir, json)
    }

    @Test
    fun readsLegacyXposedApkOverHttpRanges() = runBlocking {
        assumeTrue("set LIVE_REPO_TEST=1 to run", System.getenv("LIVE_REPO_TEST") == "1")
        val apk = asset("com.sevtinge.hyperceiler", "OS3_HyperCeiler-2.10.166-20260331-release.apk")
        val meta = probe().fetch(apk)
        assertNotNull("probe failed", meta)
        meta!!
        assertEquals("com.sevtinge.hyperceiler", meta.packageName)
        assertEquals(4571L, meta.versionCode)
        assertEquals(35, meta.minSdk)
        assertEquals(37, meta.targetSdk)
        assertEquals(setOf("arm64-v8a"), meta.abis)
        assertTrue("xposed declaration expected", meta.hasXposedDeclaration)
    }

    @Test
    fun readsLibXposedModulePropOverHttpRanges() = runBlocking {
        assumeTrue("set LIVE_REPO_TEST=1 to run", System.getenv("LIVE_REPO_TEST") == "1")
        val apk = asset("io.github.lcebot.clipsync", "app-release.apk")
        val meta = probe().fetch(apk)
        assertNotNull("probe failed", meta)
        meta!!
        assertEquals("io.github.lcebot.clipsync", meta.packageName)
        assertEquals(35, meta.minSdk)
        assertEquals(102, meta.minApiVersion)
        assertTrue(meta.hasXposedDeclaration)
        assertEquals(listOf("system"), meta.scope)
    }

    @Test
    fun fallsBackToWholeFileWhenRangeIsRejected() = runBlocking {
        assumeTrue("set LIVE_REPO_TEST=1 to run", System.getenv("LIVE_REPO_TEST") == "1")
        // This asset answered 501 "Unsupported client range" during the corpus
        // survey, so it exercises the <= 8 MB whole-file fallback.
        val apk = asset("com.amanoteam.nel", "app-release.apk")
        val meta = probe().fetch(apk)
        assertNotNull("probe failed on range-rejecting asset", meta)
        assertEquals("com.amanoteam.nel", meta!!.packageName)
        assertTrue(meta.hasXposedDeclaration)
    }
}
