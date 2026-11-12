package io.github.tophtab.lspstore.data.device

import java.io.File
import java.util.zip.ZipFile

/**
 * libxposed modules declare themselves with files under `META-INF/xposed/` and
 * nothing in the manifest, so a PackageManager scan cannot see them.
 *
 * LSPosed itself looks for `java_init.list` (in the base APK or any split);
 * `native_init.list` covers native-only modules and `module.prop` is part of
 * the same format. In the LSPosed repository every modern module carries
 * `java_init.list` and `module.prop` together, but checking the whole set costs
 * nothing and cannot miss a variant.
 */
object ModuleMarker {
    val MODERN_MARKERS = setOf(
        "META-INF/xposed/java_init.list",
        "META-INF/xposed/native_init.list",
        "META-INF/xposed/module.prop",
    )

    /**
     * True when the installed base APK or one of its splits contains a libxposed
     * marker. A missing or unreadable APK must not fail the whole scan.
     */
    fun isModernModule(sourceDir: String?, splitSourceDirs: Array<String>?): Boolean =
        readModernModule(sourceDir, splitSourceDirs) == true

    /** null means an unreadable archive, so a scan must allow another attempt. */
    internal fun readModernModule(sourceDir: String?, splitSourceDirs: Array<String>?): Boolean? {
        val apks = buildList {
            if (!sourceDir.isNullOrBlank()) add(sourceDir)
            splitSourceDirs?.forEach { if (it.isNotBlank()) add(it) }
        }
        if (apks.isEmpty()) return null
        var unreadable = false
        for (path in apks) {
            when (readMarker(path)) {
                true -> return true
                null -> unreadable = true
                false -> Unit
            }
        }
        return if (unreadable) null else false
    }

    fun hasAnyMarker(apkPath: String): Boolean = readMarker(apkPath) == true

    private fun readMarker(apkPath: String): Boolean? {
        if (!File(apkPath).isFile) return null
        return runCatching {
            ZipFile(apkPath).use { zip ->
                (MODERN_MARKERS + "assets/xposed_init").any { zip.getEntry(it) != null }
            }
        }.getOrNull()
    }
}
