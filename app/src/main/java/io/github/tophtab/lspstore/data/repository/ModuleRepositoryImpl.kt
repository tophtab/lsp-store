package io.github.tophtab.lspstore.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.data.remote.LsposedRepoApi
import io.github.tophtab.lspstore.data.remote.dto.ModuleListItemDto
import io.github.tophtab.lspstore.data.remote.toDetail
import io.github.tophtab.lspstore.data.remote.toModule
import io.github.tophtab.lspstore.data.remote.toModuleListItem
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

interface ModuleRepository {
    suspend fun getCachedIndex(): List<Module>?
    suspend fun getIndex(force: Boolean = false): List<Module>
    suspend fun refreshIndexIfNeeded(force: Boolean): List<Module>

    /**
     * True once this process fetched the catalogue. A screen that finds it true
     * reuses the in-memory index instead of fetching, so it shows no indicator.
     */
    fun hasFetchedIndexThisSession(): Boolean

    suspend fun getDetail(packageName: String): ModuleDetail
    fun clearCache()
    fun cacheSizeBytes(): Long
}

class ModuleRepositoryImpl(
    context: Context,
    private val api: LsposedRepoApi,
    private val json: Json,
) : ModuleRepository {
    private val cacheFile = File(context.filesDir, "module_index.json")

    /** Serializes network refreshes; never held while the cache is read for first paint. */
    private val refreshMutex = Mutex()

    /** Serializes the on-disk cache read/write. */
    private val cacheMutex = Mutex()

    @Volatile
    private var cached: List<Module>? = null

    @Volatile
    private var cachedAt: Long = 0

    /**
     * Process-session flag. The first screen that asks for a refresh fetches the
     * repository; later screens reuse the refreshed in-memory index. Pull-to-refresh
     * and the worker still force a fetch.
     */
    @Volatile
    private var refreshedThisSession = false

    /**
     * Reads the last saved index without touching the network. Used by screens to
     * paint their cached content immediately, before a background refresh.
     */
    override suspend fun getCachedIndex(): List<Module>? {
        cached?.let { return it }
        return cacheMutex.withLock {
            cached?.let { return@withLock it }
            readCache()?.let { (dtos, savedAt) ->
                val modules = dtos.map { it.toModule() }
                cached = modules
                cachedAt = savedAt
                return@withLock modules
            }
            null
        }
    }

    override suspend fun getIndex(force: Boolean): List<Module> = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAt < TTL_MILLIS) return@withLock it }

        var stale: List<Module>? = if (!force) cached else null
        if (!force) {
            cacheMutex.withLock { readCache() }?.let { (dtos, savedAt) ->
                val modules = dtos.map { it.toModule() }
                cached = modules
                cachedAt = savedAt
                if (now - savedAt < TTL_MILLIS) return@withLock modules
                stale = modules
            }
        }

        try {
            fetchAndCache()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Offline / repository outage: keep browsing the last known index
            // instead of failing the whole screen. A forced refresh (worker)
            // still reports the failure so WorkManager can retry it.
            stale ?: throw error
        }
    }

    /**
     * Refreshes the index unless this process already refreshed it. Used after the
     * cached index has been painted, so the network never blocks first paint.
     */
    override suspend fun refreshIndexIfNeeded(force: Boolean): List<Module> = refreshMutex.withLock {
        if (!force && refreshedThisSession) {
            cached?.let { return@withLock it }
        }
        fetchAndCache()
    }

    override fun hasFetchedIndexThisSession(): Boolean = refreshedThisSession

    override suspend fun getDetail(packageName: String): ModuleDetail =
        withContext(Dispatchers.IO) { api.moduleDetail(packageName).toDetail() }

    override fun clearCache() {
        cached = null
        cachedAt = 0
        refreshedThisSession = false
        runCatching { cacheFile.delete() }
    }

    override fun cacheSizeBytes(): Long = runCatching { if (cacheFile.exists()) cacheFile.length() else 0L }.getOrDefault(0L)

    /** Caller must hold [refreshMutex]. */
    private suspend fun fetchAndCache(): List<Module> {
        val now = System.currentTimeMillis()
        val dtos = fetchAll()
        val modules = dtos.map { it.toModule() }.distinctBy { it.packageName }
        cached = modules
        cachedAt = now
        refreshedThisSession = true
        cacheMutex.withLock { writeCache(dtos, now) }
        return modules
    }

    /**
     * The paginated module-list endpoint is the default: the official host serves it in
     * real time, and updates are computed from this index, so a stale catalogue would
     * hide fresh releases. The single-file `modules.json` is the fallback — one request
     * instead of ~40, but only the backup host serves it and its publishers mirror a
     * deployment that is several days old.
     *
     * A user who pinned the backup host overrules that default: pinning it means the
     * official CDN does not reach them, and the backup has no paginated list at all, so
     * the choice is only meaningful as "take the older single file".
     *
     * The first page doubles as the cheap probe: when the paginated endpoint is
     * unreachable the failure costs one request, not one per page.
     */
    private suspend fun fetchAll(): List<ModuleListItemDto> =
        if (api.prefersFullIndex()) {
            firstOf(::fetchFullIndex, ::fetchPaginated)
        } else {
            firstOf(::fetchPaginated, ::fetchFullIndex)
        }

    /** Runs [attempts] in order and returns the first success, keeping every failure. */
    private suspend fun firstOf(
        vararg attempts: suspend () -> List<ModuleListItemDto>,
    ): List<ModuleListItemDto> {
        val failures = mutableListOf<Throwable>()
        for (attempt in attempts) {
            try {
                return attempt()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failures += error
            }
        }
        val last = failures.lastOrNull() ?: IllegalStateException("No index source")
        failures.dropLast(1).forEach(last::addSuppressed)
        throw last
    }

    private suspend fun fetchFullIndex(): List<ModuleListItemDto> =
        api.fullModuleIndex()
            .filter { it.name.isNotBlank() }
            .map { it.toModuleListItem() }
            .distinctBy { it.name }

    private suspend fun fetchPaginated(): List<ModuleListItemDto> {
        val first = api.moduleList(1)
        val items = mutableListOf<ModuleListItemDto>().apply { addAll(first.modules) }
        if (first.pageCount > 1) {
            coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENT_PAGES)
                (2..first.pageCount)
                    .map { page ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit { api.moduleList(page).modules }
                        }
                    }
                    .awaitAll()
                    .forEach { items.addAll(it) }
            }
        }
        return items.filter { it.name.isNotBlank() }.distinctBy { it.name }
    }

    private suspend fun readCache(): Pair<List<ModuleListItemDto>, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            val cache = json.decodeFromString(IndexCache.serializer(), cacheFile.readText())
            cache.modules to cache.savedAt
        }.getOrNull()
    }

    private suspend fun writeCache(modules: List<ModuleListItemDto>, savedAt: Long) =
        withContext(Dispatchers.IO) {
            runCatching {
                val cache = IndexCache(savedAt = savedAt, modules = modules)
                cacheFile.writeText(json.encodeToString(IndexCache.serializer(), cache))
            }
        }

    private companion object {
        const val TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val MAX_CONCURRENT_PAGES = 6
    }
}

@Serializable
private data class IndexCache(
    val savedAt: Long = 0,
    val modules: List<ModuleListItemDto> = emptyList(),
)
