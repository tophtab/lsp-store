package io.github.tophtab.lspstore.data.device

import io.github.tophtab.lspstore.domain.model.InstalledModule
import kotlin.coroutines.cancellation.CancellationException

/** Cheap PackageManager fields that invalidate labels and module metadata. */
internal data class PackageFingerprint(
    val versionCode: Long,
    val versionName: String?,
    val lastUpdateTime: Long,
    val sourceDir: String?,
    val splitSourceDirs: List<String>,
    val environment: String,
    val minSdk: Int,
    val targetSdk: Int,
    val flags: Int,
    val nativeLibraryDir: String?,
)

internal data class InstalledPackageRead(val module: InstalledModule, val reusable: Boolean)

internal class InstalledPackageCandidate(
    val packageName: String,
    val fingerprint: PackageFingerprint,
    val fallback: InstalledModule,
    val read: () -> InstalledPackageRead,
)

internal interface InstalledPackageSource {
    val environmentKey: String
    fun allPackages(): List<InstalledPackageCandidate>
    fun packageNamed(packageName: String): InstalledPackageCandidate?
}

/** Owned by the repository's scan mutex; failed reads are never memoized. */
internal class InstalledPackageScanner(private val source: InstalledPackageSource) {
    private data class Entry(val fingerprint: PackageFingerprint, val module: InstalledModule)
    private val entries = mutableMapOf<String, Entry>()

    fun scan(): List<InstalledModule> {
        val packages = source.allPackages()
        val seen = HashSet<String>(packages.size)
        val modules = packages.mapNotNull { candidate ->
            if (seen.add(candidate.packageName)) read(candidate) else null
        }
        entries.keys.retainAll(seen)
        return modules
    }

    /** Always checks the current package, independently of the full-list TTL. */
    fun find(packageName: String): InstalledModule? {
        val candidate = source.packageNamed(packageName)
        if (candidate == null) {
            entries.remove(packageName)
            return null
        }
        return read(candidate)
    }

    private fun read(candidate: InstalledPackageCandidate): InstalledModule {
        entries[candidate.packageName]?.let {
            if (it.fingerprint == candidate.fingerprint) return it.module
        }
        entries.remove(candidate.packageName)
        val result = try {
            candidate.read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            InstalledPackageRead(candidate.fallback, reusable = false)
        }
        if (result.reusable) {
            entries[candidate.packageName] = Entry(candidate.fingerprint, result.module)
        }
        return result.module
    }
}
