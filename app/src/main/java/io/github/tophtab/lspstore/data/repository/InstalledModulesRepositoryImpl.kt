package io.github.tophtab.lspstore.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.data.device.AndroidInstalledPackageSource
import io.github.tophtab.lspstore.data.device.InstalledPackageScanner
import io.github.tophtab.lspstore.data.device.InstalledPackageSource
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.data.cache.writeCacheAtomically
import java.io.File

interface InstalledModulesRepository {
    /** Last scan from this or a previous app session, if any. */
    suspend fun getCachedInstalledModules(): List<InstalledModule>?

    /** Enumerates packages; unchanged packages reuse their module fields. */
    suspend fun getInstalledModules(force: Boolean = false): List<InstalledModule>

    /** Live single-package lookup; never enumerates the installed list. */
    suspend fun getInstalledModule(packageName: String): InstalledModule?
}

class InstalledModulesRepositoryImpl internal constructor(
    private val cacheFile: File,
    private val json: Json,
    private val source: InstalledPackageSource,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : InstalledModulesRepository {
    constructor(context: Context, json: Json) : this(
        File(context.filesDir, "installed_modules.json"), json, AndroidInstalledPackageSource(context),
    )

    private val scanner = InstalledPackageScanner(source)

    /** Serializes PackageManager scans, not cache reads. */
    private val scanMutex = Mutex()

    /** Serializes the on-disk cache read/write. */
    private val cacheMutex = Mutex()

    @Volatile
    private var cached: List<InstalledModule>? = null

    /** Monotonic scan time; null for a disk-cache read. */
    @Volatile
    private var scannedAt: Long? = null
    private var scannedEnvironment: String? = null

    /**
     * Last scan from this or a previous app session. Screens paint this first and
     * then rescan PackageManager in the background.
     */
    override suspend fun getCachedInstalledModules(): List<InstalledModule>? {
        cached?.let { return it }
        return cacheMutex.withLock {
            cached?.let { return@withLock it }
            readCache()?.let { modules ->
                cached = modules
                return@withLock modules
            }
            null
        }
    }

    override suspend fun getInstalledModules(force: Boolean): List<InstalledModule> = scanMutex.withLock {
        val now = clock()
        // Screens, diagnostics and the worker all ask for the same scan; a short
        // window avoids walking PackageManager again for every caller. Anything
        // triggered by a package broadcast or pull-to-refresh passes force=true.
        val environment = source.environmentKey
        val lastScan = scannedAt
        cached?.let {
            if (!force && lastScan != null && now - lastScan in 0 until SCAN_TTL_MILLIS &&
                scannedEnvironment == environment
            ) return@withLock it
        }
        val modules = withContext(Dispatchers.IO) { scanner.scan() }
        cacheMutex.withLock {
            cached = modules
            scannedAt = clock()
            scannedEnvironment = environment
            writeCache(modules)
        }
        modules
    }

    override suspend fun getInstalledModule(packageName: String): InstalledModule? = scanMutex.withLock {
        val module = withContext(Dispatchers.IO) { scanner.find(packageName) }
        cacheMutex.withLock {
            cached?.let { current ->
                if (current.firstOrNull { it.packageName == packageName } != module) {
                    cached = current.filterNot { it.packageName == packageName } + listOfNotNull(module)
                    scannedAt = null
                }
            }
        }
        module
    }

    private suspend fun readCache(): List<InstalledModule>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString(InstalledCache.serializer(), cacheFile.readText()).modules
        }.getOrNull()
    }

    private suspend fun writeCache(modules: List<InstalledModule>) = withContext(Dispatchers.IO) {
        runCatching {
            writeCacheAtomically(
                cacheFile,
                json.encodeToString(InstalledCache.serializer(), InstalledCache(modules)),
            )
        }
    }
}

@Serializable
private data class InstalledCache(
    val modules: List<InstalledModule> = emptyList(),
)

/** How long a PackageManager scan is reused for callers that did not force one. */
private const val SCAN_TTL_MILLIS = 30_000L
