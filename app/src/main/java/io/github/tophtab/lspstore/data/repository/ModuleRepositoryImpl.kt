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
import io.github.tophtab.lspstore.data.cache.writeCacheAtomically
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
    suspend fun clearCache()
    fun cacheSizeBytes(): Long
}

class ModuleRepositoryImpl internal constructor(
    private val cacheFile: File,
    private val api: LsposedRepoApi,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModuleRepository {
    constructor(context: Context, api: LsposedRepoApi, json: Json) : this(
        File(context.filesDir, "module_index.json"), api, json,
    )

    /** Serializes network refreshes; never held while the cache is read for first paint. */
    private val refreshMutex = Mutex()

    /** Serializes the on-disk cache read/write. */
    private val cacheMutex = Mutex()

    private data class CompletedRefresh(val epoch: Long, val revision: Long, val modules: List<Module>)
    private data class CacheState(
        val modules: List<Module>? = null,
        val savedAt: Long = 0,
        val epoch: Long = 0,
        val revision: Long = 0,
        val refreshedSource: Boolean? = null,
        val completed: Map<Boolean, CompletedRefresh> = emptyMap(),
    )

    /** One immutable snapshot for request tickets, cache clear and successful refreshes. */
    @Volatile private var cacheState = CacheState()

    /**
     * Reads the last saved index without touching the network. Used by screens to
     * paint their cached content immediately, before a background refresh.
     */
    override suspend fun getCachedIndex(): List<Module>? {
        cacheState.modules?.let { return it }
        return cacheMutex.withLock {
            cacheState.modules?.let { return@withLock it }
            readCache()?.let { (dtos, savedAt) ->
                val modules = mapModules(dtos)
                cacheState = cacheState.copy(modules = modules, savedAt = savedAt)
                return@withLock modules
            }
            null
        }
    }

    override suspend fun getIndex(force: Boolean): List<Module> {
        val ticket = cacheState
        val source = api.prefersFullIndex()
        return refreshMutex.withLock {
            completedSince(ticket, source)?.let { return@withLock it }
            val now = clock()
            val current = cacheState
            current.modules?.let { if (!force && now - current.savedAt < TTL_MILLIS) return@withLock it }

            var stale: List<Module>? = if (!force) current.modules else null
            if (!force) {
                getCachedIndex()?.let { modules ->
                    if (now - cacheState.savedAt < TTL_MILLIS) return@withLock modules
                    stale = modules
                }
            }

            try {
                fetchAndCache(source, ticket.epoch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // An ordinary read keeps stale content; forced refreshes report
                // failures so the worker can retry. A stale fallback is never
                // counted as a successful refresh for waiting callers.
                stale ?: throw error
            }
        }
    }

    /**
     * Refreshes the index unless this process already refreshed it. Used after the
     * cached index has been painted, so the network never blocks first paint.
     */
    override suspend fun refreshIndexIfNeeded(force: Boolean): List<Module> {
        val ticket = cacheState
        val source = api.prefersFullIndex()
        return refreshMutex.withLock {
            completedSince(ticket, source)?.let { return@withLock it }
            if (!force && cacheState.refreshedSource == source) {
                cacheState.modules?.let { return@withLock it }
            }
            fetchAndCache(source, ticket.epoch)
        }
    }

    override fun hasFetchedIndexThisSession(): Boolean = cacheState.refreshedSource == api.prefersFullIndex()

    override suspend fun getDetail(packageName: String): ModuleDetail =
        withContext(Dispatchers.IO) { api.moduleDetail(packageName).toDetail() }

    override suspend fun clearCache() = cacheMutex.withLock {
        val current = cacheState
        cacheState = CacheState(epoch = current.epoch + 1, revision = current.revision, completed = current.completed)
        withContext(Dispatchers.IO) { runCatching { cacheFile.delete() } }
        Unit
    }

    override fun cacheSizeBytes(): Long = runCatching { if (cacheFile.exists()) cacheFile.length() else 0L }.getOrDefault(0L)

    /** Caller must hold [refreshMutex]. */
    private suspend fun fetchAndCache(source: Boolean, epoch: Long): List<Module> {
        val now = clock()
        val dtos = fetchAll(source)
        val modules = mapModules(dtos)
        cacheMutex.withLock {
            // A clear invalidates every request started before it, even one
            // that was still waiting for the refresh mutex.
            val valid = cacheState.epoch == epoch
            if (valid) {
                writeCache(dtos, now)
            }
            val current = cacheState
            val revision = current.revision + 1
            cacheState = current.copy(
                modules = if (valid) modules else current.modules,
                savedAt = if (valid) now else current.savedAt,
                refreshedSource = if (valid) source else current.refreshedSource,
                revision = revision,
                completed = current.completed + (source to CompletedRefresh(epoch, revision, modules)),
            )
        }
        return modules
    }

    /** Only callers that started before this source completed can share that result. */
    private fun completedSince(ticket: CacheState, source: Boolean): List<Module>? {
        val current = cacheState
        return current.completed[source]?.takeIf {
            it.epoch == ticket.epoch && it.revision > ticket.revision
        }?.modules
    }

    private suspend fun mapModules(dtos: List<ModuleListItemDto>): List<Module> = withContext(Dispatchers.Default) {
        dtos.map { it.toModule() }.distinctBy { it.packageName }
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
    private suspend fun fetchAll(prefersFullIndex: Boolean): List<ModuleListItemDto> =
        if (prefersFullIndex) {
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
                writeCacheAtomically(cacheFile, json.encodeToString(IndexCache.serializer(), cache))
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
