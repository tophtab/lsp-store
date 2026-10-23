package io.github.tophtab.lspstore.data.install

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason
import java.io.File
import java.util.zip.ZipFile

/**
 * Everything the gate needs from an APK archive. Kept separate from Android so
 * the decision logic is a pure JVM function (see `ApkGateTest`).
 */
data class ArchiveInfo(
    val packageName: String?,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val debuggable: Boolean = false,
    /** Concrete `lib/<abi>/` directories inside the APK. */
    val libAbis: Set<String> = emptySet(),
    val hasLegacyXposedMeta: Boolean = false,
    val hasLibXposedProvider: Boolean = false,
    val hasModuleProp: Boolean = false,
    val minApiVersion: Int? = null,
) {
    val hasXposedDeclaration: Boolean
        get() = hasLegacyXposedMeta || hasLibXposedProvider || hasModuleProp
}

sealed interface GateResult {
    data object Ok : GateResult

    data class Warn(val reason: Reason) : GateResult

    /**
     * The APK was not handed to the installer. This is deliberately not a
     * verdict about the module: the caller records one failed attempt, nothing
     * is persisted, and the module stays in the update list.
     */
    data class Reject(val reason: Reason) : GateResult
}

/**
 * Last line of defence before a downloaded APK is handed to the installer.
 * Zero network: only PackageManager and the zip's directory listing.
 */
object ApkGate {

    fun evaluate(info: ArchiveInfo, expectedPackage: String, device: DeviceProfile): GateResult {
        info.minSdk?.let { minSdk ->
            if (minSdk > device.sdkInt) {
                return GateResult.Reject(
                    Reason(
                        Reason.Kind.MIN_SDK_TOO_HIGH,
                        listOf(minSdk.toString(), device.sdkInt.toString()),
                    ),
                )
            }
        }
        if (info.libAbis.isNotEmpty() && info.libAbis.none { it in device.abis }) {
            return GateResult.Reject(
                Reason(Reason.Kind.ABI_INCOMPATIBLE, listOf(info.libAbis.joinToString(", "))),
            )
        }
        val supportedApi = device.lsposedApi
        if (info.minApiVersion != null && supportedApi != null && info.minApiVersion > supportedApi) {
            // Another variant (legacy API) may still work, so this is retryable.
            return GateResult.Reject(
                Reason(Reason.Kind.API_TOO_NEW, listOf(info.minApiVersion.toString())),
            )
        }
        val packageMismatch = info.packageName != null && info.packageName != expectedPackage
        if (packageMismatch) {
            val reason = Reason(
                Reason.Kind.PACKAGE_MISMATCH,
                listOf(info.packageName.orEmpty(), expectedPackage),
            )
            // A renamed module is legitimate; a renamed app without any Xposed
            // declaration is a companion app shipped in the same release.
            return if (info.hasXposedDeclaration) {
                GateResult.Warn(reason)
            } else {
                GateResult.Reject(reason)
            }
        }
        return GateResult.Ok
    }

    suspend fun check(
        context: Context,
        file: File,
        expectedPackage: String,
        device: DeviceProfile,
    ): GateResult {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return GateResult.Reject(Reason(Reason.Kind.NO_APK))
        }
        // getPackageArchiveInfo + the zip directory scan are blocking disk IO;
        // every caller is on the main dispatcher (viewModelScope), so move it.
        val info = withContext(Dispatchers.IO) { readArchiveInfo(context, file) }
            ?: return GateResult.Reject(Reason(Reason.Kind.NOT_A_MODULE))
        return evaluate(info, expectedPackage, device)
    }

    private fun readArchiveInfo(context: Context, file: File): ArchiveInfo? {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS
        val pkg = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }.getOrNull() ?: return null
        val app = pkg.applicationInfo ?: return null
        val meta = app.metaData
        val moduleProp = readModuleProp(file)
        return ArchiveInfo(
            packageName = pkg.packageName,
            minSdk = app.minSdkVersion.takeIf { it > 0 },
            targetSdk = app.targetSdkVersion.takeIf { it > 0 },
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            libAbis = readLibAbis(file),
            hasLegacyXposedMeta = meta != null &&
                (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion")),
            hasLibXposedProvider = pkg.providers?.any {
                it.name == XPOSED_PROVIDER
            } == true,
            hasModuleProp = moduleProp != null,
            minApiVersion = moduleProp?.get("minApiVersion")?.toIntOrNull(),
        )
    }

    private fun readLibAbis(file: File): Set<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .mapNotNull { entry ->
                    val parts = entry.name.split('/')
                    if (parts.size >= 3 && parts[0] == "lib") parts[1].takeIf { it.isNotBlank() } else null
                }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun readModuleProp(file: File): Map<String, String>? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(MODULE_PROP) ?: return@use null
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null
                    else line.substring(0, index).trim() to line.substring(index + 1).trim()
                }.toMap()
            }
        }
    }.getOrNull()

    private const val MODULE_PROP = "META-INF/xposed/module.prop"
    private const val XPOSED_PROVIDER = "io.github.libxposed.service.XposedProvider"
}
