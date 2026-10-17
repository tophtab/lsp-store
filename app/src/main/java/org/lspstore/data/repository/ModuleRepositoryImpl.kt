package org.lspstore.data.repository

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
import org.lspstore.data.remote.LsposedRepoApi
import org.lspstore.data.remote.dto.ModuleListItemDto
import org.lspstore.data.remote.toDetail
import org.lspstore.data.remote.toModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.ModuleDetail
import java.io.File

class ModuleRepositoryImpl(
    context: Context,
    private val api: LsposedRepoApi,
    private val json: Json,
) {
    private val cacheFile = File(context.filesDir, "module_index.json")
    private val mutex = Mutex()

    @Volatile
    private var cached: List<Module>? = null

    @Volatile
    private var cachedAt: Long = 0

    suspend fun getIndex(force: Boolean = false): List<Module> = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAt < TTL_MILLIS) return it }

        if (!force) {
            readCache()?.let { (dtos, savedAt) ->
                val modules = dtos.map { it.toModule() }
                cached = modules
                cachedAt = savedAt
                if (now - savedAt < TTL_MILLIS) return modules
            }
        }

        val dtos = fetchAll()
        val modules = dtos.map { it.toModule() }.distinctBy { it.packageName }
        cached = modules
        cachedAt = now
        writeCache(dtos, now)
        modules
    }

    suspend fun getDetail(packageName: String): ModuleDetail =
        withContext(Dispatchers.IO) { api.moduleDetail(packageName).toDetail() }

    fun clearCache() {
        cached = null
        cachedAt = 0
        runCatching { cacheFile.delete() }
    }

    fun cacheSizeBytes(): Long = runCatching { if (cacheFile.exists()) cacheFile.length() else 0L }.getOrDefault(0L)

    private suspend fun fetchAll(): List<ModuleListItemDto> {
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

    private fun readCache(): Pair<List<ModuleListItemDto>, Long>? = runCatching {
        if (!cacheFile.exists()) return null
        val cache = json.decodeFromString(IndexCache.serializer(), cacheFile.readText())
        cache.modules to cache.savedAt
    }.getOrNull()

    private fun writeCache(modules: List<ModuleListItemDto>, savedAt: Long) {
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
