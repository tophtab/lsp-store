package io.github.tophtab.lspstore.data.probe

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
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
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.data.device.ModuleMarker
import io.github.tophtab.lspstore.data.cache.writeCacheAtomically
import kotlin.coroutines.cancellation.CancellationException

/** A transport that can return an exact byte range, or the whole file from 0. */
fun interface ByteRangeSource {
    suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray?
}

private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

class HttpRangeSource(private val client: HttpClient) : ByteRangeSource {
    override suspend fun read(url: String, start: Long, endInclusive: Long): ByteArray? {
        if (start < 0 || endInclusive < start || endInclusive - start >= MAX_RESPONSE_BYTES) return null
        val length = endInclusive - start + 1
        return try {
            client.prepareGet(url) {
                header(HttpHeaders.Range, "bytes=$start-$endInclusive")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                val advertised = response.contentLength()
                if (advertised != null && advertised != length) return@execute null
                val encoding = response.headers[HttpHeaders.ContentEncoding]
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return@execute null
                val valid = when (response.status.value) {
                    206 -> validContentRange(response.headers[HttpHeaders.ContentRange], start, endInclusive)
                    // An ignored Range is accepted only for the complete bounded file.
                    200 -> {
                        start == 0L && advertised == length
                    }
                    else -> false
                }
                if (!valid) null else readExactRange(response.bodyAsChannel(), length.toInt())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun validContentRange(header: String?, start: Long, end: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(header?.trim().orEmpty()) ?: return false
        val actualStart = match.groupValues[1].toLongOrNull() ?: return false
        val actualEnd = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3]
        return actualStart == start && actualEnd == end &&
            (total == "*" || total.toLongOrNull()?.let { it > end } == true)
    }

    private suspend fun readExactRange(channel: ByteReadChannel, length: Int): ByteArray? {
        try {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = channel.readAvailable(bytes, offset, length - offset)
                if (read < 0) return null
                offset += read
            }
            // Unknown/chunked lengths must reach EOF. Read at most one extra byte.
            if (channel.readAvailable(ByteArray(1), 0, 1) >= 0) return null
            return bytes
        } finally {
            channel.cancel(null)
        }
    }

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
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
    private val failures = ProbeFailureCache(clock)
    private var cache: LinkedHashMap<String, ApkMeta>? = null

    private val inFlight = KeyedMutex<String>()

    override suspend fun cached(asset: ReleaseAsset): ApkMeta? = cacheGet(cacheKey(asset))

    override suspend fun fetch(asset: ReleaseAsset): ApkMeta? =
        inFlight.withLock(cacheKey(asset)) { fetchOnce(asset) }

    private suspend fun fetchOnce(asset: ReleaseAsset): ApkMeta? {
        val key = cacheKey(asset)
        cacheGet(key)?.let { return it }
        if (failedRecently(key)) return null
        val meta = semaphore.withPermit {
            withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    probe(asset.downloadUrl, asset.size)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (meta == null) {
            mutex.withLock { failures.record(key) }
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
            if (entry.uncompressedSize == 0L) return@buildMeta byteArrayOf()
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
        if (bytes.size.toLong() != size) return null
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
            val bytes = readEntry(propEntry) ?: return null
            parseProperties(bytes)
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
                entries.any { it.name in ModuleMarker.MODERN_MARKERS || it.name == LEGACY_INIT },
            minApiVersion = moduleProp["minApiVersion"]?.toIntOrNull(),
            targetApiVersion = moduleProp["targetApiVersion"]?.toIntOrNull(),
            hasLegacyInit = entries.any { it.name == LEGACY_INIT },
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
        if (entry.uncompressedSize == 0L) return byteArrayOf()
        if (entry.uncompressedSize < 0L || entry.uncompressedSize > MAX_ENTRY_BYTES) return null
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
            failures.remove(key)
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
                writeCacheAtomically(
                    cacheFile,
                    json.encodeToString(ProbeCache.serializer(), ProbeCache(map.toMap())),
                )
            }
        }
    }

    private suspend fun failedRecently(key: String): Boolean = mutex.withLock {
        failures.contains(key)
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
        const val LEGACY_INIT = "assets/xposed_init"
        const val TAIL_BYTES = 70_000L
        const val MAX_TRAFFIC = 1_500_000L
        const val MAX_FULL_BYTES = 8L * 1024 * 1024
        const val MAX_DIRECTORY_BYTES = 1_000_000L
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
        const val MAX_PROP_BYTES = 64L * 1024
        const val MAX_CACHE_ENTRIES = 500
        const val MAX_CONCURRENCY = 3
        const val TIMEOUT_MS = 20_000L
    }
}

@Serializable
private data class ProbeCache(
    val entries: Map<String, ApkMeta> = emptyMap(),
)
