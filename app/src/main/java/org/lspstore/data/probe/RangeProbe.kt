package org.lspstore.data.probe

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import java.io.File
import java.util.zip.Inflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.variant.ApkMeta
import org.lspstore.domain.variant.ApkProbe

/** A transport that can return an exact byte range, or the whole file from 0. */
fun interface ByteRangeSource {
    suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray?
}

private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

class HttpRangeSource(private val client: HttpClient) : ByteRangeSource {
    override suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray? =
        runCatching {
            client.prepareGet(url) {
                header(HttpHeaders.Range, "bytes=$start-$endInclusive")
                header(HttpHeaders.Accept, "*/*")
            }.execute { response ->
                when (response.status.value) {
                    // Partial content: exactly what we asked for.
                    206 -> response.bodyAsBytes()
                    // Some CDNs ignore Range. Only accept that for a whole-file read.
                    200 -> {
                        val length = response.contentLength()
                        if (start != 0L || length == null || length > MAX_RESPONSE_BYTES) {
                            null
                        } else {
                            val body = response.bodyAsBytes()
                            if (body.size.toLong() == length) body else null
                        }
                    }

                    else -> null
                }
            }
        }.getOrNull()
}

/**
 * P2 Range probe: central directory + AndroidManifest.xml +
 * `META-INF/xposed/module.prop`, with an 8 MB whole-file fallback, a persisted
 * LRU cache and a 10-minute negative cache. Any failure returns null and the
 * caller stays on the file-name layer — probing never blocks an install.
 */
class RangeProbe(
    private val source: ByteRangeSource,
    private val cacheFile: File,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApkProbe {

    constructor(client: HttpClient, cacheDir: File, json: Json) : this(
        source = HttpRangeSource(client),
        cacheFile = File(cacheDir, CACHE_FILE),
        json = json,
    )

    private val mutex = Mutex()
    private val semaphore = Semaphore(MAX_CONCURRENCY)
    private val failures = LinkedHashMap<String, Long>()
    private var cache: LinkedHashMap<String, ApkMeta>? = null

    override suspend fun fetch(asset: ReleaseAsset): ApkMeta? {
        val key = cacheKey(asset)
        cacheGet(key)?.let { return it }
        if (failedRecently(key)) return null
        val meta = semaphore.withPermit {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { probe(asset.downloadUrl, asset.size) }.getOrNull()
            }
        }
        if (meta == null) {
            mutex.withLock { failures[key] = clock() }
        } else {
            cachePut(key, meta)
        }
        return meta
    }

    private suspend fun probe(url: String, size: Long): ApkMeta? {
        if (size <= 0L) return null
        val tailStart = maxOf(0L, size - TAIL_BYTES)
        val tail = source.read(url, tailStart, size - 1) ?: return fullProbe(url, size)
        if (tail.size > MAX_TRAFFIC) return null
        val end = ZipDirectory.findEndRecord(tail) ?: return fullProbe(url, size)
        if (end.directorySize <= 0L || end.directorySize > MAX_DIRECTORY_BYTES) {
            return fullProbe(url, size)
        }
        if (end.directoryOffset + end.directorySize > size) return null
        val directory = source.read(
            url,
            end.directoryOffset,
            end.directoryOffset + end.directorySize - 1,
        ) ?: return fullProbe(url, size)
        var traffic = tail.size.toLong() + directory.size
        if (traffic > MAX_TRAFFIC) return null
        val entries = ZipDirectory.parseDirectory(directory, end.entryCount)
        if (entries.isEmpty()) return fullProbe(url, size)

        return buildMeta(entries) { entry ->
            if (entry.compressedSize <= 0L || entry.compressedSize > MAX_ENTRY_BYTES) {
                return@buildMeta null
            }
            val header = source.read(url, entry.localHeaderOffset, entry.localHeaderOffset + 29)
                ?: return@buildMeta null
            val dataOffset = ZipDirectory.dataOffset(header) ?: return@buildMeta null
            val start = entry.localHeaderOffset + dataOffset
            val data = source.read(url, start, start + entry.compressedSize - 1)
                ?: return@buildMeta null
            traffic += header.size + data.size
            if (traffic > MAX_TRAFFIC) return@buildMeta null
            decompress(entry, data)
        }
    }

    private suspend fun fullProbe(url: String, size: Long): ApkMeta? {
        if (size <= 0L || size > MAX_FULL_BYTES) return null
        val bytes = source.read(url, 0, size - 1) ?: return null
        if (bytes.size.toLong() > MAX_FULL_BYTES) return null
        val end = ZipDirectory.findEndRecord(bytes) ?: return null
        val start = end.directoryOffset.toInt()
        val finish = (end.directoryOffset + end.directorySize).toInt()
        if (start < 0 || finish > bytes.size || start > finish) return null
        val entries = ZipDirectory.parseDirectory(bytes.copyOfRange(start, finish), end.entryCount)
        if (entries.isEmpty()) return null
        return buildMeta(entries) { entry -> extractLocal(bytes, entry) }
    }

    private suspend fun buildMeta(
        entries: List<ZipEntry>,
        readEntry: suspend (ZipEntry) -> ByteArray?,
    ): ApkMeta? {
        val manifestEntry = entries.firstOrNull { it.name == ANDROID_MANIFEST } ?: return null
        if (manifestEntry.uncompressedSize > MAX_ENTRY_BYTES ||
            manifestEntry.compressedSize > MAX_ENTRY_BYTES
        ) {
            return null
        }
        val manifestBytes = readEntry(manifestEntry) ?: return null
        val facts = AxmlParser.parse(manifestBytes) ?: return null

        val propEntry = entries.firstOrNull { it.name == MODULE_PROP }
        val moduleProp = if (propEntry != null &&
            propEntry.uncompressedSize <= MAX_PROP_BYTES &&
            propEntry.compressedSize <= MAX_PROP_BYTES
        ) {
            readEntry(propEntry)?.let(::parseProperties).orEmpty()
        } else {
            emptyMap()
        }
        val scopeEntry = entries.firstOrNull { it.name == SCOPE_LIST }
        val scope = if (scopeEntry != null &&
            scopeEntry.uncompressedSize in 1..MAX_PROP_BYTES &&
            scopeEntry.compressedSize in 1..MAX_PROP_BYTES
        ) {
            readEntry(scopeEntry)
                ?.let { String(it, Charsets.UTF_8) }
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
        } else {
            emptyList()
        }

        val legacyDeclaration = facts.metaData.containsKey("xposedmodule") ||
            facts.metaData.containsKey("xposedminversion")
        return ApkMeta(
            packageName = facts.packageName,
            versionCode = facts.versionCode,
            versionName = facts.versionName,
            minSdk = facts.minSdk,
            targetSdk = facts.targetSdk,
            debuggable = facts.debuggable,
            abis = ZipDirectory.abisOf(entries),
            universal = false,
            hasXposedDeclaration = legacyDeclaration || facts.hasLibXposedProvider ||
                moduleProp.isNotEmpty(),
            minApiVersion = moduleProp["minApiVersion"]?.toIntOrNull(),
            scope = scope,
        )
    }

    private fun extractLocal(bytes: ByteArray, entry: ZipEntry): ByteArray? {
        val offset = entry.localHeaderOffset.toInt()
        if (offset < 0 || offset + 30 > bytes.size) return null
        val dataOffset = ZipDirectory.dataOffset(bytes.copyOfRange(offset, offset + 30)) ?: return null
        val start = offset + dataOffset.toInt()
        val end = (start + entry.compressedSize).toInt()
        if (start < 0 || end > bytes.size || end < start) return null
        return decompress(entry, bytes.copyOfRange(start, end))
    }

    private fun decompress(entry: ZipEntry, data: ByteArray): ByteArray? {
        if (entry.uncompressedSize <= 0L || entry.uncompressedSize > MAX_ENTRY_BYTES) return null
        return when (entry.method) {
            0 -> data
            8 -> runCatching {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(data)
                    val output = ByteArray(entry.uncompressedSize.toInt())
                    var total = 0
                    while (!inflater.finished() && total < output.size) {
                        val read = inflater.inflate(output, total, output.size - total)
                        if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        total += read
                    }
                    output.copyOf(total)
                } finally {
                    inflater.end()
                }
            }.getOrNull()

            else -> null
        }
    }

    private suspend fun cacheGet(key: String): ApkMeta? = mutex.withLock { loadCache()[key] }

    private suspend fun cachePut(key: String, meta: ApkMeta) {
        mutex.withLock {
            val map = loadCache()
            map[key] = meta
            val iterator = map.keys.iterator()
            while (map.size > MAX_CACHE_ENTRIES && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            persist(map)
        }
    }

    private suspend fun loadCache(): LinkedHashMap<String, ApkMeta> {
        cache?.let { return it }
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString(ProbeCache.serializer(), cacheFile.readText())
            }.getOrNull()
        } ?: ProbeCache()
        val map = LinkedHashMap<String, ApkMeta>(16, 0.75f, true)
        map.putAll(stored.entries)
        while (map.size > MAX_CACHE_ENTRIES) {
            val iterator = map.keys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        cache = map
        return map
    }

    private suspend fun persist(map: Map<String, ApkMeta>) {
        withContext(Dispatchers.IO) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(
                    json.encodeToString(ProbeCache.serializer(), ProbeCache(map.toMap())),
                )
            }
        }
    }

    private suspend fun failedRecently(key: String): Boolean = mutex.withLock {
        val at = failures[key] ?: return@withLock false
        if (clock() - at > FAILURE_TTL_MS) {
            failures.remove(key)
            false
        } else {
            true
        }
    }

    private fun cacheKey(asset: ReleaseAsset): String =
        asset.downloadUrl.substringBefore('?') + "|" + asset.size

    private fun parseProperties(bytes: ByteArray): Map<String, String> =
        String(bytes, Charsets.UTF_8).lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null
                else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()

    private companion object {
        const val CACHE_FILE = "variant-cache.json"
        const val ANDROID_MANIFEST = "AndroidManifest.xml"
        const val MODULE_PROP = "META-INF/xposed/module.prop"
        const val SCOPE_LIST = "META-INF/xposed/scope.list"
        const val TAIL_BYTES = 70_000L
        const val MAX_TRAFFIC = 1_500_000L
        const val MAX_FULL_BYTES = 8L * 1024 * 1024
        const val MAX_DIRECTORY_BYTES = 1_000_000L
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
        const val MAX_PROP_BYTES = 64L * 1024
        const val MAX_CACHE_ENTRIES = 500
        const val MAX_CONCURRENCY = 3
        const val TIMEOUT_MS = 20_000L
        const val FAILURE_TTL_MS = 10 * 60 * 1000L
    }
}

@Serializable
private data class ProbeCache(
    val entries: Map<String, ApkMeta> = emptyMap(),
)
