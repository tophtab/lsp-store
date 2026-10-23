package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tophtab.lspstore.data.device.lsposedApiFor
import io.github.tophtab.lspstore.data.device.parseGetprop
import io.github.tophtab.lspstore.data.device.parseVendorOs
import io.github.tophtab.lspstore.domain.variant.VendorOs

class DeviceProfileParserTest {

    @Test
    fun parsesGetpropDump() {
        val raw = """
            [ro.mi.os.version.name]: [OS3.0]
            [ro.build.version.sdk]: [36]
            [ro.product.cpu.abi]: [arm64-v8a]
        """.trimIndent()
        val props = parseGetprop(raw)
        assertEquals("OS3.0", props["ro.mi.os.version.name"])
        assertEquals("arm64-v8a", props["ro.product.cpu.abi"])
    }

    @Test
    fun hyperOsFromMiPropertyWins() {
        val vendor = parseVendorOs(
            mapOf("ro.mi.os.version.name" to "OS3.0", "ro.miui.ui.version.name" to "V14"),
            incremental = null,
        )
        assertEquals(VendorOs.Family.HYPEROS, vendor?.family)
        assertEquals(3, vendor?.major)
    }

    @Test
    fun hyperOsFromIncrementalFallback() {
        val vendor = parseVendorOs(emptyMap(), incremental = "OS3.0.2.0.WOCCNXM")
        assertEquals(VendorOs.Family.HYPEROS, vendor?.family)
        assertEquals(3, vendor?.major)
    }

    @Test
    fun miuiAndColorOs() {
        assertEquals(
            VendorOs.Family.MIUI,
            parseVendorOs(mapOf("ro.miui.ui.version.name" to "V14"), null)?.family,
        )
        assertEquals(
            14,
            parseVendorOs(mapOf("ro.miui.ui.version.name" to "V14"), null)?.major,
        )
        assertEquals(
            VendorOs.Family.COLOROS,
            parseVendorOs(mapOf("ro.build.version.oplusrom" to "V15.0.1"), null)?.family,
        )
        assertEquals(
            15,
            parseVendorOs(mapOf("ro.build.version.oplusrom" to "V15.0.1"), null)?.major,
        )
    }

    @Test
    fun oneUiBuildNumbers() {
        val eight = parseVendorOs(mapOf("ro.build.version.oneui" to "80000"), null)
        assertEquals(VendorOs.Family.ONEUI, eight?.family)
        assertEquals(8, eight?.major)

        val sixOne = parseVendorOs(mapOf("ro.build.version.oneui" to "60100"), null)
        assertEquals(6, sixOne?.major)
    }

    @Test
    fun unknownVendorStaysNull() {
        assertNull(parseVendorOs(emptyMap(), incremental = "RQ3A.211001.001"))
    }

    @Test
    fun lsposedApiMapping() {
        assertNull(lsposedApiFor(null))
        assertNull(lsposedApiFor("manager"))
        assertEquals(93, lsposedApiFor("1.8.6"))
        assertEquals(93, lsposedApiFor("0.9.0"))
        assertEquals(100, lsposedApiFor("1.9.2"))
        assertEquals(102, lsposedApiFor("1.10.0"))
        assertEquals(102, lsposedApiFor("2.0.1-beta"))
    }
}
