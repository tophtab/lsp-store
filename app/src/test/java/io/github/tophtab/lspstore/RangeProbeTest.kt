package io.github.tophtab.lspstore

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.probe.ByteRangeSource
import io.github.tophtab.lspstore.data.probe.RangeProbe
import io.github.tophtab.lspstore.domain.model.ReleaseAsset

class RangeProbeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun asset(bytes: ByteArray, url: String = "https://example.invalid/app.apk") = ReleaseAsset(
        name = "app.apk",
        downloadUrl = url,
        fallbackUrl = null,
        size = bytes.size.toLong(),
        downloadCount = 0,
        contentType = null,
    )

    private fun probe(source: ByteRangeSource): RangeProbe {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "probe-${System.nanoTime()}")
        return RangeProbe(source, File(cacheDir, "variant-cache.json"), json)
    }

    @Test
    fun readsManifestAbisAndModulePropOverRanges() = runBlocking {
        val apk = apkBytes(ManifestFixture(provider = true).bytes())
        val source = FakeRangeSource(apk)
        val rangeProbe = probe(source)

        val meta = rangeProbe.fetch(asset(apk))!!
        assertEquals("com.example.module", meta.packageName)
        assertEquals(7L, meta.versionCode)
        assertEquals("1.2", meta.versionName)
        assertEquals(29, meta.minSdk)
        assertEquals(36, meta.targetSdk)
        assertTrue(meta.debuggable)
        assertEquals(setOf("arm64-v8a"), meta.abis)
        assertTrue(meta.hasXposedDeclaration)
        assertEquals(102, meta.minApiVersion)
        assertEquals(listOf("com.android.systemui"), meta.scope)

        // Cache key ignores the query string and the second lookup is free.
        val reads = source.reads
        val again = rangeProbe.fetch(asset(apk, url = "https://example.invalid/app.apk?signature=abc"))
        assertEquals(meta, again)
        assertEquals(reads, source.reads)
    }

    @Test
    fun legacyMetaDeclarationCounts() = runBlocking {
        val apk = apkBytes(
            ManifestFixture(
                meta = mapOf("xposedmodule" to "true", "xposedminversion" to "93"),
                provider = false,
            ).bytes(),
        )
        val meta = probe(FakeRangeSource(apk)).fetch(asset(apk))!!
        assertTrue(meta.hasXposedDeclaration)
    }

    @Test
    fun fallsBackToWholeFileWhenRangeIsUnsupported() = runBlocking {
        // > 70 KB so the tail request is a real range, which this source rejects.
        val apk = apkBytes(ManifestFixture().bytes(), padding = 90_000)
        val source = FakeRangeSource(apk, supportsRanges = false)
        val meta = probe(source).fetch(asset(apk))!!
        assertEquals("com.example.module", meta.packageName)
        assertTrue(meta.abis.contains("arm64-v8a"))
    }

    @Test
    fun failuresReturnNullAndAreRemembered() = runBlocking {
        val source = object : ByteRangeSource {
            var reads = 0
            override suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray? {
                reads++
                return null
            }
        }
        val rangeProbe = probe(source)
        assertNull(rangeProbe.fetch(asset(ByteArray(1024))))
        val reads = source.reads
        assertNull(rangeProbe.fetch(asset(ByteArray(1024))))
        assertEquals("negative cache", reads, source.reads)
    }

    @Test
    fun garbageBodyIsIgnored() = runBlocking {
        val garbage = ByteArray(2048) { it.toByte() }
        assertNull(probe(FakeRangeSource(garbage)).fetch(asset(garbage)))
    }

    private class FakeRangeSource(
        private val bytes: ByteArray,
        private val supportsRanges: Boolean = true,
    ) : ByteRangeSource {
        var reads = 0
            private set

        override suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray? {
            reads++
            if (start < 0 || start > endInclusive || endInclusive >= bytes.size) return null
            val wholeFile = start == 0L && endInclusive == bytes.size - 1L
            if (!supportsRanges && !wholeFile) return null
            return bytes.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
        }
    }

    private fun apkBytes(manifest: ByteArray, padding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            put("AndroidManifest.xml", manifest)
            put(
                "META-INF/xposed/module.prop",
                "minApiVersion=102\ntargetApiVersion=102\n".toByteArray(),
            )
            put("META-INF/xposed/scope.list", "com.android.systemui\n".toByteArray())
            put("lib/arm64-v8a/libhook.so", ByteArray(32))
            if (padding > 0) put("assets/padding.bin", ByteArray(padding) { 0x5A })
        }
        return out.toByteArray()
    }

    /** Minimal AXML encoder for a manifest with the attributes the probe reads. */
    private class ManifestFixture(
        private val packageName: String = "com.example.module",
        private val versionCode: Int = 7,
        private val versionName: String = "1.2",
        private val minSdk: Int = 29,
        private val targetSdk: Int = 36,
        private val debuggable: Boolean = true,
        private val meta: Map<String, String> = mapOf("xposedmodule" to "true"),
        private val provider: Boolean = false,
    ) {
        private data class Attr(
            val name: String,
            val value: String? = null,
            val int: Int? = null,
            val bool: Boolean? = null,
        )

        private data class Element(val name: String, val attrs: List<Attr>)

        fun bytes(): ByteArray {
            val elements = buildList {
                add(
                    Element(
                        "manifest",
                        listOf(
                            Attr("package", value = packageName),
                            Attr("versionCode", int = versionCode),
                            Attr("versionName", value = versionName),
                        ),
                    ),
                )
                add(
                    Element(
                        "uses-sdk",
                        listOf(
                            Attr("minSdkVersion", int = minSdk),
                            Attr("targetSdkVersion", int = targetSdk),
                        ),
                    ),
                )
                add(Element("application", listOf(Attr("debuggable", bool = debuggable))))
                meta.forEach { (key, value) ->
                    add(
                        Element(
                            "meta-data",
                            listOf(Attr("name", value = key), Attr("value", value = value)),
                        ),
                    )
                }
                if (provider) {
                    add(
                        Element(
                            "provider",
                            listOf(
                                Attr("name", value = "io.github.libxposed.service.XposedProvider"),
                            ),
                        ),
                    )
                }
            }

            val strings = mutableListOf<String>()
            fun register(value: String): Int {
                val index = strings.indexOf(value)
                return if (index >= 0) index else strings.size.also { strings += value }
            }
            elements.forEach { element ->
                register(element.name)
                element.attrs.forEach { attribute ->
                    register(attribute.name)
                    attribute.value?.let(::register)
                }
            }

            val pool = stringPool(strings)
            val chunks = elements.map { elementChunk(it, strings) }
            val total = 8 + pool.size + chunks.sumOf { it.size }
            val out = ByteArrayOutputStream()
            out.u16(0x0003)
            out.u16(8)
            out.u32(total.toLong())
            out.write(pool)
            chunks.forEach(out::write)
            return out.toByteArray()
        }

        private fun stringPool(strings: List<String>): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = ArrayList<Int>(strings.size)
            strings.forEach { value ->
                offsets += data.size()
                data.u16(value.length)
                data.write(value.toByteArray(Charsets.UTF_16LE))
                data.u16(0)
            }
            while (data.size() % 4 != 0) data.write(0)
            val headerSize = 28
            val stringsStart = headerSize + 4 * strings.size
            val size = stringsStart + data.size()
            val out = ByteArrayOutputStream()
            out.u16(0x0001)
            out.u16(headerSize)
            out.u32(size.toLong())
            out.u32(strings.size.toLong())
            out.u32(0)
            out.u32(0)
            out.u32(stringsStart.toLong())
            out.u32(0)
            offsets.forEach { out.u32(it.toLong()) }
            out.write(data.toByteArray())
            return out.toByteArray()
        }

        private fun elementChunk(element: Element, strings: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            val size = 36 + 20 * element.attrs.size
            out.u16(0x0102)
            out.u16(16)
            out.u32(size.toLong())
            out.u32(0)
            out.u32(0xFFFFFFFFL)
            out.u32(0xFFFFFFFFL)
            out.u32(strings.indexOf(element.name).toLong())
            out.u16(20)
            out.u16(20)
            out.u16(element.attrs.size)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            element.attrs.forEach { attribute ->
                out.u32(0xFFFFFFFFL)
                out.u32(strings.indexOf(attribute.name).toLong())
                out.u32(0xFFFFFFFFL)
                out.u16(8)
                out.write(0)
                when {
                    attribute.bool != null -> {
                        out.write(0x12)
                        out.u32(if (attribute.bool) 0xFFFFFFFFL else 0L)
                    }

                    attribute.int != null -> {
                        out.write(0x10)
                        out.u32(attribute.int.toLong() and 0xFFFFFFFFL)
                    }

                    else -> {
                        out.write(0x03)
                        out.u32(strings.indexOf(attribute.value).toLong())
                    }
                }
            }
            return out.toByteArray()
        }
    }

}

private fun ByteArrayOutputStream.u16(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

private fun ByteArrayOutputStream.u32(value: Long) {
    write((value and 0xFF).toInt())
    write(((value ushr 8) and 0xFF).toInt())
    write(((value ushr 16) and 0xFF).toInt())
    write(((value ushr 24) and 0xFF).toInt())
}
