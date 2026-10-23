package io.github.tophtab.lspstore.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.data.device.ScannedPackage
import io.github.tophtab.lspstore.domain.model.InstalledModule
import java.io.File

interface InstalledModulesRepository {
    /** Last scan from this or a previous app session, if any. */
    suspend fun getCachedInstalledModules(): List<InstalledModule>?

    /** Full PackageManager scan; [force] is kept for API symmetry. */
    suspend fun getInstalledModules(force: Boolean = false): List<InstalledModule>

    suspend fun getInstalledModule(packageName: String): InstalledModule?
}

class InstalledModulesRepositoryImpl(
    private val context: Context,
    private val json: Json,
) : InstalledModulesRepository {
    private val cacheFile = File(context.filesDir, "installed_modules.json")

    /** Serializes PackageManager scans, not cache reads. */
    private val scanMutex = Mutex()

    /** Serializes the on-disk cache read/write. */
    private val cacheMutex = Mutex()

    @Volatile
    private var cached: List<InstalledModule>? = null

    /** When [cached] came from a PackageManager scan; 0 for a disk-cache read. */
    @Volatile
    private var scannedAt: Long = 0

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

    @Suppress("DEPRECATION")
    override suspend fun getInstalledModules(force: Boolean): List<InstalledModule> = scanMutex.withLock {
        val now = System.currentTimeMillis()
        // Screens, diagnostics and the worker all ask for the same scan; a short
        // window avoids walking PackageManager again for every caller. Anything
        // triggered by a package broadcast or pull-to-refresh passes force=true.
        cached?.let { if (!force && now - scannedAt < SCAN_TTL_MILLIS) return@withLock it }
        val modules = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apiLevel = Build.VERSION.SDK_INT
            // Lint only understands the SDK_INT check written inline.
            @Suppress("DEPRECATION")
            val supportsLongVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            val packages = pm.getInstalledPackages(
                PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
            )
            packages.mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                val meta = app.metaData
                // The framework half of the scan; the module/ABI rules live in
                // ScannedPackage so they can be tested on the JVM.
                ScannedPackage(
                    packageName = pkg.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }
                        .getOrDefault(pkg.packageName),
                    apiLevel = apiLevel,
                    longVersionCode = if (supportsLongVersionCode) pkg.longVersionCode else null,
                    legacyVersionCode = pkg.versionCode,
                    versionName = pkg.versionName,
                    hasLegacyXposedMeta = meta != null &&
                        (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion")),
                    // Modern libxposed modules expose a provider instead of the
                    // legacy xposedmodule meta-data.
                    hasLibXposedProvider = pkg.providers?.any {
                        it.name == "io.github.libxposed.service.XposedProvider"
                    } == true,
                    description = meta?.getString("xposeddescription"),
                    minSdk = app.minSdkVersion,
                    targetSdk = app.targetSdkVersion,
                    reflectedPrimaryCpuAbi = app.reflectedPrimaryCpuAbi(),
                    nativeLibraryDir = app.nativeLibraryDir,
                    debuggable = app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    lastUpdateTime = pkg.lastUpdateTime,
                ).toInstalledModule()
            }
        }
        cached = modules
        scannedAt = System.currentTimeMillis()
        cacheMutex.withLock { writeCache(modules) }
        modules
    }

    override suspend fun getInstalledModule(packageName: String): InstalledModule? =
        getInstalledModules().firstOrNull { it.packageName == packageName }

    /**
     * `ApplicationInfo.primaryCpuAbi` is not part of the public SDK, so read it
     * reflectively; the ABI name fallback lives in [ScannedPackage].
     */
    private fun android.content.pm.ApplicationInfo.reflectedPrimaryCpuAbi(): String? =
        runCatching { javaClass.getField("primaryCpuAbi").get(this) as? String }.getOrNull()

    private suspend fun readCache(): List<InstalledModule>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString(InstalledCache.serializer(), cacheFile.readText()).modules
        }.getOrNull()
    }

    private suspend fun writeCache(modules: List<InstalledModule>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.writeText(
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
