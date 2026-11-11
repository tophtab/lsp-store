package io.github.tophtab.lspstore.data.install

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tophtab.lspstore.data.probe.AxmlParser
import io.github.tophtab.lspstore.data.probe.ManifestFacts
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.libXposedApiRequirement
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
    val targetApiVersion: Int? = null,
    /** `assets/xposed_init` present: the framework loads this as a legacy module. */
    val hasLegacyInit: Boolean = false,
) {
    val hasXposedDeclaration: Boolean
        get() = hasLegacyXposedMeta || hasLibXposedProvider || hasModuleProp
}

sealed interface GateResult {
    data object Ok : GateResult

    data class Warn(val reason: Reason) : GateResult

    /**
     * The APK was not handed to the installer because the archive itself says it
     * cannot work: an unreadable download, or a foreign package without any
     * Xposed declaration (a companion app shipped in the same release). This is
     * not a verdict about the module - the caller records one failed attempt,
     * which makes the module re-probe its variants and keeps it in the update
     * list (see [io.github.tophtab.lspstore.domain.variant.onInstallFailed]).
     */
    data class Reject(val reason: Reason) : GateResult
}

/**
 * Last line of defence before a downloaded APK is handed to the installer.
 * Zero network: only PackageManager and the zip's directory listing.
 */
object ApkGate {

    fun evaluate(info: ArchiveInfo, expectedPackage: String, device: DeviceProfile): GateResult {
        val packageMismatch = info.packageName != null && info.packageName != expectedPackage
        // A renamed module is legitimate; a renamed app without any Xposed
        // declaration is a companion app shipped in the same release. This is a
        // rejection, not a warning, so it is decided before any advisory line
        // can mask it.
        if (packageMismatch && !info.hasXposedDeclaration) {
            return GateResult.Reject(
                Reason(
                    Reason.Kind.PACKAGE_MISMATCH,
                    listOf(info.packageName.orEmpty(), expectedPackage),
                ),
            )
        }
        if (packageMismatch) {
            // The caller installs a renamed module under its own package and
            // remembers the link, so this warning must stay first.
            return GateResult.Warn(
                Reason(
                    Reason.Kind.PACKAGE_MISMATCH,
                    listOf(info.packageName.orEmpty(), expectedPackage),
                ),
            )
        }
        if (info.minSdk != null && info.minSdk > device.sdkInt) {
            // Advisory like the ABI and the API number: the system installer is
            // what refuses this package, and the user asked for the attempt
            // (#47, #75).
            return GateResult.Warn(
                Reason(
                    Reason.Kind.MIN_SDK_TOO_HIGH,
                    listOf(info.minSdk.toString(), device.sdkInt.toString()),
                ),
            )
        }
        if (info.libAbis.isNotEmpty() && info.libAbis.none { it in device.abis }) {
            // Installing an APK never fails on a foreign `lib/<abi>/` directory,
            // the module may not load its native part at all, and an ARM
            // translation layer can still run it - advisory like the API number (#47).
            return GateResult.Warn(
                Reason(Reason.Kind.ABI_INCOMPATIBLE, listOf(info.libAbis.joinToString(", "))),
            )
        }
        val supportedApi = device.lsposedApi
        val requiredApi = libXposedApiRequirement(
            targetApiVersion = info.targetApiVersion,
            hasLegacyInit = info.hasLegacyInit,
            minApiVersion = info.minApiVersion,
        )
        if (requiredApi != null && supportedApi != null && requiredApi > supportedApi) {
            // The framework selects a loading strategy and loads the module
            // anyway, so this is a warning, never a refusal (#47). Another
            // variant may still fit better, but the user decides.
            return GateResult.Warn(Reason(Reason.Kind.API_TOO_NEW, listOf(requiredApi.toString())))
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

    /**
     * Everything the gate needs, read from the downloaded file.
     *
     * PackageManager is the cheap source, but it refuses to describe an archive
     * whose `minSdkVersion` is above the platform - the same check that fails the
     * install - and simply returns null for it. That is exactly the case the gate
     * must not mistake for "not an APK", so the manifest inside the archive (the
     * reader the remote probe uses) is the fallback.
     */
    private fun readArchiveInfo(context: Context, file: File): ArchiveInfo? {
        val zipFacts = readZipFacts(file)
        val moduleProp = readModuleProp(file)
        val pkg = runCatching {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
            )
        }.getOrNull()
        val app = pkg?.applicationInfo
        val meta = app?.metaData
        val facts = if (pkg == null) readManifestFacts(file) else null
        if (pkg == null && facts == null) return null
        val legacyMeta = meta != null &&
            (meta.containsKey(XPOSED_MODULE) || meta.containsKey(XPOSED_MIN_VERSION)) ||
            facts?.metaData?.any { (key, _) -> key == XPOSED_MODULE || key == XPOSED_MIN_VERSION } == true
        return ArchiveInfo(
            packageName = pkg?.packageName ?: facts?.packageName,
            minSdk = app?.minSdkVersion?.takeIf { it > 0 } ?: facts?.minSdk,
            targetSdk = app?.targetSdkVersion?.takeIf { it > 0 } ?: facts?.targetSdk,
            debuggable = (app != null && app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ||
                facts?.debuggable == true,
            libAbis = zipFacts.abis,
            hasLegacyXposedMeta = legacyMeta,
            hasLibXposedProvider = pkg?.providers?.any {
                it.name == XPOSED_PROVIDER
            } == true || facts?.hasLibXposedProvider == true,
            hasModuleProp = moduleProp != null,
            minApiVersion = moduleProp?.get("minApiVersion")?.toIntOrNull(),
            targetApiVersion = moduleProp?.get("targetApiVersion")?.toIntOrNull(),
            hasLegacyInit = zipFacts.hasLegacyInit,
        )
    }

    private class ZipFacts(val abis: Set<String>, val hasLegacyInit: Boolean)

    /** Manifest facts straight from the archive, for when PackageManager refuses. */
    private fun readManifestFacts(file: File): ManifestFacts? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(ANDROID_MANIFEST) ?: return@use null
            AxmlParser.parse(zip.getInputStream(entry).readBytes())
        }
    }.getOrNull()

    private fun readZipFacts(file: File): ZipFacts = runCatching {
        ZipFile(file).use { zip ->
            val abis = mutableSetOf<String>()
            var hasLegacyInit = false
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (entry.name == LEGACY_INIT) hasLegacyInit = true
                val parts = entry.name.split('/')
                if (parts.size >= 3 && parts[0] == "lib") {
                    parts[1].takeIf { it.isNotBlank() }?.let(abis::add)
                }
            }
            ZipFacts(abis, hasLegacyInit)
        }
    }.getOrDefault(ZipFacts(emptySet(), false))

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
    private const val LEGACY_INIT = "assets/xposed_init"
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val XPOSED_MODULE = "xposedmodule"
    private const val XPOSED_MIN_VERSION = "xposedminversion"
    private const val XPOSED_PROVIDER = "io.github.libxposed.service.XposedProvider"
}
