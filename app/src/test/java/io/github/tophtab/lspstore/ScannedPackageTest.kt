package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.device.ScannedPackage

/**
 * Which packages count as Xposed modules, and which ABI they claim, decides
 * whether a module shows up in the Installed list at all. Both rules used to be
 * buried in the PackageManager scan, so they were untestable.
 */
class ScannedPackageTest {

    private fun pkg(
        hasLegacyXposedMeta: Boolean = false,
        hasLibXposedProvider: Boolean = false,
        reflectedPrimaryCpuAbi: String? = null,
        nativeLibraryDir: String? = null,
        apiLevel: Int = 30,
        longVersionCode: Long? = 42L,
        legacyVersionCode: Int = 42,
        debuggable: Boolean = false,
    ) = ScannedPackage(
        packageName = "com.example.module",
        label = "Module",
        apiLevel = apiLevel,
        longVersionCode = longVersionCode,
        legacyVersionCode = legacyVersionCode,
        versionName = "1.0",
        hasLegacyXposedMeta = hasLegacyXposedMeta,
        hasLibXposedProvider = hasLibXposedProvider,
        description = "does things",
        minSdk = 26,
        targetSdk = 34,
        reflectedPrimaryCpuAbi = reflectedPrimaryCpuAbi,
        nativeLibraryDir = nativeLibraryDir,
        debuggable = debuggable,
        lastUpdateTime = 1_700_000_000_000L,
    )

    @Test
    fun legacyMetaDataMarksAModule() {
        assertTrue(pkg(hasLegacyXposedMeta = true).isXposedModule)
    }

    @Test
    fun libXposedProviderMarksAModule() {
        assertTrue(pkg(hasLibXposedProvider = true).isXposedModule)
    }

    @Test
    fun plainAppIsNotAModule() {
        assertFalse(pkg().isXposedModule)
    }

    @Test
    fun reflectedAbiWins() {
        assertEquals(
            "arm64-v8a",
            pkg(reflectedPrimaryCpuAbi = "arm64-v8a", nativeLibraryDir = "/data/app/lib/x86").primaryCpuAbi,
        )
    }

    @Test
    fun blankReflectionFallsBackToNativeLibraryDir() {
        assertEquals("arm64-v8a", pkg(nativeLibraryDir = "/data/app/~~abc/lib/arm64").primaryCpuAbi)
        assertEquals("armeabi-v7a", pkg(nativeLibraryDir = "/data/app/~~abc/lib/arm").primaryCpuAbi)
        assertEquals("x86_64", pkg(nativeLibraryDir = "/data/app/~~abc/lib/x86_64").primaryCpuAbi)
        assertEquals("x86", pkg(nativeLibraryDir = "/data/app/~~abc/lib/x86").primaryCpuAbi)
        assertEquals("x86_64", pkg(nativeLibraryDir = "/data/app/~~abc/lib/X64").primaryCpuAbi)
    }

    @Test
    fun unknownAbiDirectoryMeansNoAbi() {
        assertNull(pkg(nativeLibraryDir = "/data/app/~~abc/lib/riscv64").primaryCpuAbi)
        assertNull(pkg(nativeLibraryDir = null).primaryCpuAbi)
        assertNull(pkg(reflectedPrimaryCpuAbi = "  ", nativeLibraryDir = null).primaryCpuAbi)
    }

    @Test
    fun longVersionCodeIsUsedFromApi28On() {
        assertEquals(4_294_967_296L, pkg(apiLevel = 30, longVersionCode = 4_294_967_296L).versionCode)
        // Below API 28 the field does not exist; the deprecated int is all we have.
        assertEquals(
            42L,
            pkg(apiLevel = 27, longVersionCode = null, legacyVersionCode = 42).versionCode,
        )
    }

    @Test
    fun missingLongVersionCodeFallsBackToTheLegacyInt() {
        assertEquals(7L, pkg(apiLevel = 30, longVersionCode = null, legacyVersionCode = 7).versionCode)
    }

    @Test
    fun scannedPackageMapsToInstalledModule() {
        val module = pkg(
            hasLibXposedProvider = true,
            nativeLibraryDir = "/data/app/~~abc/lib/arm64",
            debuggable = true,
        ).toInstalledModule()

        assertEquals("com.example.module", module.packageName)
        assertEquals(42L, module.versionCode)
        assertEquals("Module", module.label)
        assertTrue(module.isXposedModule)
        assertTrue(module.hasLibXposedProvider)
        assertTrue(module.debuggable)
        assertEquals("arm64-v8a", module.primaryCpuAbi)
        assertEquals(26, module.minSdk)
        assertEquals(34, module.targetSdk)
        assertEquals(1_700_000_000_000L, module.lastUpdateTime)
    }
}
