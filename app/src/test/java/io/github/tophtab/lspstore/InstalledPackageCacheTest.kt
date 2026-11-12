package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.device.InstalledPackageCandidate
import io.github.tophtab.lspstore.data.device.InstalledPackageRead
import io.github.tophtab.lspstore.data.device.InstalledPackageSource
import io.github.tophtab.lspstore.data.device.PackageFingerprint
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepositoryImpl
import io.github.tophtab.lspstore.data.repository.findInstalledCopy
import io.github.tophtab.lspstore.domain.model.InstalledModule
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledPackageCacheTest {
    @get:Rule val directory = TemporaryFolder()

    private class Item {
        var fingerprint = PackageFingerprint(1, "1", 100, "/base.apk", emptyList(), "", 29, 36, 0, "/lib/arm64")
        var readable = true
        var throws = false
        var reads = 0
    }

    private class Source : InstalledPackageSource {
        override var environmentKey = "36:en"
        val items = linkedMapOf("direct" to Item(), "renamed" to Item())
        val queries = mutableListOf<String>()
        var enumerations = 0
        var duplicate = false
        override fun allPackages(): List<InstalledPackageCandidate> {
            enumerations++
            val result = items.map { (name, item) -> candidate(name, item) }
            return if (duplicate) result + result else result
        }
        override fun packageNamed(packageName: String): InstalledPackageCandidate? {
            queries += packageName
            return items[packageName]?.let { candidate(packageName, it) }
        }
        private fun candidate(name: String, item: Item): InstalledPackageCandidate {
            val signature = item.fingerprint.copy(environment = environmentKey)
            val fallback = InstalledModule(name, signature.versionCode, signature.versionName, name, false, null, lastUpdateTime = signature.lastUpdateTime)
            return InstalledPackageCandidate(name, signature, fallback) {
                item.reads++
                if (item.throws) throw IOException("unreadable metadata")
                InstalledPackageRead(
                    fallback.copy(label = "$name:$environmentKey", isXposedModule = item.readable),
                    reusable = item.readable,
                )
            }
        }
    }

    private fun repository(source: Source, clock: () -> Long = { 1000L }) =
        InstalledModulesRepositoryImpl(directory.newFile(), Json { ignoreUnknownKeys = true }, source, clock)

    @Test
    fun `single package queries see installs replacements and removals without enumeration`() = runBlocking {
        val source = Source()
        val repository = repository(source)
        assertEquals(1L, repository.getInstalledModule("direct")!!.versionCode)
        source.items.getValue("direct").fingerprint = source.items.getValue("direct").fingerprint.copy(versionCode = 2)
        assertEquals(2L, repository.getInstalledModule("direct")!!.versionCode)
        source.items.remove("direct")
        assertNull(repository.getInstalledModule("direct"))
        source.items["new"] = Item()
        assertNotNull(repository.getInstalledModule("new"))
        assertEquals(0, source.enumerations)
        assertEquals(listOf("direct", "direct", "direct", "new"), source.queries)
    }

    @Test
    fun `forced lists reuse unchanged packages and invalidate only a replacement`() = runBlocking {
        val source = Source()
        val repository = repository(source)
        val initial = repository.getInstalledModules(force = true)
        assertEquals(initial, repository.getInstalledModules(force = true))
        assertEquals(1, source.items.getValue("direct").reads)
        assertEquals(1, source.items.getValue("renamed").reads)
        val direct = source.items.getValue("direct")
        direct.fingerprint = direct.fingerprint.copy(lastUpdateTime = 200)
        assertEquals(200L, repository.getInstalledModules(force = true).first().lastUpdateTime)
        assertEquals(2, direct.reads)
        assertEquals(1, source.items.getValue("renamed").reads)
        assertEquals(3, source.enumerations)
    }

    @Test
    fun `version name APK paths SDK and flags invalidate module fields`() = runBlocking {
        val source = Source()
        val repository = repository(source)
        val item = source.items.getValue("direct")
        repository.getInstalledModule("direct")
        val changes: List<(PackageFingerprint) -> PackageFingerprint> = listOf(
            { it.copy(versionName = "same-code-new-name") },
            { it.copy(sourceDir = "/replaced.apk") },
            { it.copy(splitSourceDirs = listOf("/split.apk")) },
            { it.copy(minSdk = 30) },
            { it.copy(targetSdk = 37) },
            { it.copy(flags = 2) },
            { it.copy(nativeLibraryDir = "/lib/x86") },
        )
        changes.forEachIndexed { index, change ->
            item.fingerprint = change(item.fingerprint)
            repository.getInstalledModule("direct")
            assertEquals(index + 2, item.reads)
        }
        source.environmentKey = "37:en"
        repository.getInstalledModule("direct")
        assertEquals(changes.size + 2, item.reads)
    }

    @Test
    fun `language changes bypass the list TTL and reload translated labels`() = runBlocking {
        val source = Source()
        var now = 0L
        val repository = repository(source) { now }
        repository.getInstalledModules()
        now = 100
        repository.getInstalledModules()
        assertEquals(1, source.enumerations)
        source.environmentKey = "36:zh-CN"
        assertEquals("direct:36:zh-CN", repository.getInstalledModules().first().label)
        assertEquals(2, source.enumerations)
        assertEquals(2, source.items.getValue("direct").reads)
    }

    @Test
    fun `failed module reads retain package versions and retry without memoizing`() = runBlocking {
        val source = Source()
        val repository = repository(source)
        val item = source.items.getValue("direct")
        item.throws = true
        val failed = repository.getInstalledModules(force = true).first()
        assertEquals(1L, failed.versionCode)
        assertEquals(100L, failed.lastUpdateTime)
        assertFalse(failed.isXposedModule)
        item.throws = false
        item.readable = false
        assertFalse(repository.getInstalledModule("direct")!!.isXposedModule)
        item.readable = true
        assertTrue(repository.getInstalledModule("direct")!!.isXposedModule)
        repository.getInstalledModule("direct")
        assertEquals(3, item.reads)
    }

    @Test
    fun `a removed package loses its metadata cache even when reinstalled identically`() = runBlocking {
        val source = Source()
        source.duplicate = true
        val repository = repository(source)
        assertEquals(2, repository.getInstalledModules(force = true).size)
        val old = source.items.remove("direct")!!
        repository.getInstalledModules(force = true)
        source.items["direct"] = old
        repository.getInstalledModules(force = true)
        assertEquals(2, old.reads)
    }

    @Test
    fun `detail queries only related copies and preserve preferred and newest rules`() = runBlocking {
        val source = Source()
        source.items["unrelated"] = Item()
        val renamed = source.items.getValue("renamed")
        renamed.fingerprint = renamed.fingerprint.copy(versionCode = 2, lastUpdateTime = 200)
        val repository = repository(source)
        val links = mapOf("renamed" to "direct", "unrelated" to "other")
        assertEquals("direct", repository.findInstalledCopy("direct", links, "direct")!!.packageName)
        assertEquals("renamed", repository.findInstalledCopy("direct", links, null)!!.packageName)
        source.items.remove("direct")
        assertEquals("renamed", repository.findInstalledCopy("direct", links, "direct")!!.packageName)
        assertEquals(setOf("direct", "renamed"), source.queries.toSet())
        assertEquals(0, source.enumerations)
    }
}
