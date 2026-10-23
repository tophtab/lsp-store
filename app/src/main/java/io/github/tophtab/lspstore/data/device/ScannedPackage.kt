package io.github.tophtab.lspstore.data.device

import io.github.tophtab.lspstore.domain.model.InstalledModule

/**
 * The fields of a `PackageInfo`/`ApplicationInfo` pair that decide whether a
 * package is an Xposed module and how it is shown.
 *
 * Reading them from the framework needs `PackageManager`; everything after that —
 * module detection, ABI fallback, version-code compatibility — is pure and lives
 * in [toInstalledModule] so it can be tested on the JVM.
 */
data class ScannedPackage(
    val packageName: String,
    val label: String,
    /** `Build.VERSION.SDK_INT` of the device that produced this scan. */
    val apiLevel: Int,
    /** `PackageInfo.longVersionCode`, null below API 28 where it does not exist. */
    val longVersionCode: Long?,
    /** `PackageInfo.versionCode` (deprecated but still the only value below 28). */
    val legacyVersionCode: Int,
    val versionName: String?,
    /** Legacy `xposedmodule` / `xposedminversion` meta-data. */
    val hasLegacyXposedMeta: Boolean,
    /** Modern libxposed modules declare a provider instead of meta-data. */
    val hasLibXposedProvider: Boolean,
    val description: String?,
    val minSdk: Int,
    val targetSdk: Int,
    /** `ApplicationInfo.primaryCpuAbi` read reflectively; null when unavailable. */
    val reflectedPrimaryCpuAbi: String?,
    /** `ApplicationInfo.nativeLibraryDir`, used as the ABI fallback. */
    val nativeLibraryDir: String?,
    val debuggable: Boolean,
    val lastUpdateTime: Long,
) {

    val isXposedModule: Boolean
        get() = hasLegacyXposedMeta || hasLibXposedProvider

    val versionCode: Long
        get() = if (apiLevel >= 28) {
            longVersionCode ?: legacyVersionCode.toLong()
        } else {
            legacyVersionCode.toLong()
        }

    /**
     * `primaryCpuAbi` is not part of the public SDK, so it is read reflectively
     * and falls back to the native library directory name (`.../lib/arm64`).
     */
    val primaryCpuAbi: String?
        get() {
            if (!reflectedPrimaryCpuAbi.isNullOrBlank()) return reflectedPrimaryCpuAbi
            return when (nativeLibraryDir?.substringAfterLast('/')?.lowercase()) {
                "arm64" -> "arm64-v8a"
                "arm" -> "armeabi-v7a"
                "x86_64", "x64" -> "x86_64"
                "x86" -> "x86"
                else -> null
            }
        }

    fun toInstalledModule(): InstalledModule = InstalledModule(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        label = label,
        isXposedModule = isXposedModule,
        description = description,
        minSdk = minSdk,
        targetSdk = targetSdk,
        primaryCpuAbi = primaryCpuAbi,
        debuggable = debuggable,
        hasLibXposedProvider = hasLibXposedProvider,
        lastUpdateTime = lastUpdateTime,
    )
}
