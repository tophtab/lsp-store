package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lspstore.domain.variant.VendorOs
import org.lspstore.domain.variant.parseTraits

class VariantTraitsTest {

    @Test
    fun parsesAbiTokensWithoutConfusingX86WithX86_64() {
        assertEquals(setOf("arm64-v8a"), parseTraits("app-arm64-v8a-release.apk").abis)
        assertEquals(setOf("arm64-v8a"), parseTraits("HyperTrust_1.2.2_arm64.apk").abis)
        assertEquals(setOf("armeabi-v7a"), parseTraits("app-armeabi-v7a-release.apk").abis)
        assertEquals(setOf("armeabi-v7a"), parseTraits("app-v7a-release.apk").abis)
        assertEquals(setOf("armeabi-v7a"), parseTraits("app-arm-release.apk").abis)

        val x86 = parseTraits("app-x86-release.apk")
        val x64 = parseTraits("app-x86_64-release.apk")
        assertEquals(setOf("x86"), x86.abis)
        assertEquals(setOf("x86_64"), x64.abis)
        assertFalse("x86_64" in x86.abis)
        assertFalse("x86" in x64.abis)
    }

    @Test
    fun parsesUniversalAndMinSdkTokens() {
        assertTrue(parseTraits("Wtonec-1.7.6-708-universal.apk").universal)
        assertTrue(parseTraits("app-all-abi.apk").universal)
        assertTrue(parseTraits("app-noarch.apk").universal)

        val minsdk = parseTraits("app-arm64Minsdk26-release.apk")
        assertEquals(26, minsdk.minSdkToken)
        assertEquals(setOf("arm64-v8a"), minsdk.abis)
        assertNull(parseTraits("app-arm64-release.apk").minSdkToken)
    }

    @Test
    fun parsesAndroidVersionsOnlyFromVersionTokens() {
        assertEquals(14, parseTraits("Android14-HyperCeiler_2.6.161_4257_release.apk").android)
        assertEquals(16, parseTraits("A16_OneUI8.x-release.apk").android)
        assertEquals(15, parseTraits("app-android.15+.apk").android)

        // `_14-panel` is the app version, not Android 14.
        val fiveG = parseTraits("FiveGSwitcher-2.0.4_14-panel.apk")
        assertNull(fiveG.android)
        assertEquals(setOf("panel"), fiveG.flavors)
    }

    @Test
    fun parsesVendorOsVariants() {
        val hyperOs2 = parseTraits("OS2_HyperCeiler-2.10.166-20260402-release.apk")
        assertEquals(VendorOs.Family.HYPEROS, hyperOs2.vendorFamily)
        assertEquals(2, hyperOs2.vendorMajor)

        val oneUi = parseTraits("A16_OneUI8.x-release.apk")
        assertEquals(VendorOs.Family.ONEUI, oneUi.vendorFamily)
        assertEquals(8, oneUi.vendorMajor)

        val miui = parseTraits("HyperCeiler_2.5.156_3866_release_miui.apk")
        assertEquals(VendorOs.Family.MIUI, miui.vendorFamily)
    }

    @Test
    fun parsesDebugOnlyOnWordBoundaries() {
        assertTrue(parseTraits("app-debug.apk").debugName)
        assertTrue(parseTraits("app-dev.apk").debugName)
        assertFalse(parseTraits("debuggerx.apk").debugName)
        // A package name that happens to start with `dev.` is not a dev build.
        assertFalse(parseTraits("dev.wtonec.final-release.apk").debugName)
    }

    @Test
    fun parsesLibXposedApiAndLegacy() {
        assertEquals(101, parseTraits("app-release-api101.apk").libxposedApi)
        assertEquals(102, parseTraits("app-api-102.apk").libxposedApi)
        assertEquals(
            101,
            parseTraits("KillAppNow_Ext_3.0_A16_OneUI8.x_API-101.0.1.apk").libxposedApi,
        )

        val legacy = parseTraits("KillAppNow_Ext_3.0_A13_15_OneUI5.x_7.x_API-Legacy89.apk")
        assertNull(legacy.libxposedApi)
        assertTrue(legacy.legacyWord)

        assertTrue(parseTraits("app-modern.apk").modernWord)
        assertFalse(parseTraits("app-modern.apk").legacyWord)
    }

    @Test
    fun parsesEditionsFlavorsAndTargetApp() {
        val knox = parseTraits("KnoxPatch-v0.8.3-sep-lite.apk")
        assertTrue("lite" in knox.edition)

        val nonNative = parseTraits("app-non-native.apk")
        assertTrue("non-native" in nonNative.edition)
        assertFalse("native" in nonNative.edition)

        assertEquals(setOf("tile"), parseTraits("FiveGSwitcher-2.0.4_14-tile.apk").flavors)
        assertEquals(setOf("offline"), parseTraits("app-offline-release.apk").flavors)

        assertEquals(
            "com.google.android.apps.translate",
            parseTraits("module_com.google.android.apps.translate.apk").targetApp,
        )
        assertNull(parseTraits("hupu_8.0.74.04035.apk").targetApp)
    }

    @Test
    fun parsesRealProbeFileNames() {
        val riscv = parseTraits("V24.08.02_riscv64_release.apk")
        assertTrue("riscv64 is not an ARM or x86 token", riscv.abis.isEmpty())
        assertFalse(riscv.universal)

        assertTrue(parseTraits("V24.07.27_universal_release.apk").universal)
        assertEquals(setOf("x86_64"), parseTraits("V23.01.21_x86_64_release.apk").abis)
        assertEquals(
            setOf("arm64-v8a"),
            parseTraits("qdreader-QDReadHook-v3.5.0-2278-arm64.apk").abis,
        )
    }
}
