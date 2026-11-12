package io.github.tophtab.lspstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.github.tophtab.lspstore.data.device.ModuleMarker

/**
 * Wren (`top.haif.xposed`) is a libxposed module: its manifest carries no
 * `xposedmodule` meta-data and no provider, so only the archive markers make it
 * show up in Installed.
 */
class ModuleMarkerTest {

    @Test
    fun javaInitListMarksTheArchive() {
        // What LSPosed itself looks for.
        assertMarkers(tempApk("META-INF/xposed/java_init.list"))
    }

    @Test
    fun modulePropMarksTheArchive() {
        assertMarkers(tempApk("META-INF/xposed/module.prop"))
    }

    @Test
    fun nativeInitListMarksTheArchive() {
        assertMarkers(tempApk("META-INF/xposed/native_init.list"))
    }

    @Test
    fun markerInASplitApkMarksTheModule() {
        val base = tempApk("META-INF/androidx.core_core.version")
        val split = tempApk("META-INF/xposed/java_init.list")
        try {
            assertTrue(ModuleMarker.isModernModule(base.absolutePath, arrayOf(split.absolutePath)))
        } finally {
            base.delete()
            split.delete()
        }
    }

    @Test
    fun unrelatedArchiveIsNotAModule() {
        val apk = tempApk("META-INF/androidx.core_core.version")
        try {
            assertFalse(ModuleMarker.isModernModule(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingOrBlankPathIsNotAModule() {
        assertFalse(ModuleMarker.isModernModule(null, null))
        assertFalse(ModuleMarker.isModernModule("  ", emptyArray()))
        assertFalse(ModuleMarker.isModernModule("/does/not/exist.apk", arrayOf("/also/missing.apk")))
    }

    @Test
    fun unreadableArchivesAreDistinguishedFromValidNonModules() {
        val apk = tempApk("META-INF/androidx.core_core.version")
        try {
            assertFalse(ModuleMarker.readModernModule(apk.absolutePath, null)!!)
            assertNull(ModuleMarker.readModernModule(apk.absolutePath, arrayOf("/missing.apk")))
            apk.writeText("not a zip")
            assertNull(ModuleMarker.readModernModule(apk.absolutePath, null))
        } finally { apk.delete() }
    }

    private fun assertMarkers(apk: File) {
        try {
            assertTrue(ModuleMarker.isModernModule(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    private fun tempApk(entry: String): File {
        val file = File.createTempFile("module", ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write("minApiVersion=101\n".toByteArray())
            zip.closeEntry()
        }
        return file
    }
}
