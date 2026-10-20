package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lspstore.data.install.ApkGate
import org.lspstore.data.install.ArchiveInfo
import org.lspstore.data.install.GateResult
import org.lspstore.domain.variant.DeviceProfile
import org.lspstore.domain.variant.Reason

class ApkGateTest {

    private val device = DeviceProfile(
        sdkInt = 36,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        lsposedApi = 102,
    )

    private fun info(
        packageName: String = "com.example.module",
        minSdk: Int? = 26,
        libAbis: Set<String> = emptySet(),
        hasLegacy: Boolean = false,
        hasProvider: Boolean = false,
        hasModuleProp: Boolean = false,
        minApiVersion: Int? = null,
        debuggable: Boolean = false,
    ) = ArchiveInfo(
        packageName = packageName,
        minSdk = minSdk,
        debuggable = debuggable,
        libAbis = libAbis,
        hasLegacyXposedMeta = hasLegacy,
        hasLibXposedProvider = hasProvider,
        hasModuleProp = hasModuleProp,
        minApiVersion = minApiVersion,
    )

    @Test
    fun minSdkAboveDeviceIsRejected() {
        val result = ApkGate.evaluate(info(minSdk = 37), "com.example.module", device)
        assertTrue(result is GateResult.Reject)
        assertEquals(Reason.Kind.MIN_SDK_TOO_HIGH, (result as GateResult.Reject).reason.kind)
    }

    @Test
    fun foreignLibAbiIsRejectedButCompatibleOrEmptyIsFine() {
        val incompatible = ApkGate.evaluate(
            info(libAbis = setOf("riscv64")),
            "com.example.module",
            device,
        )
        assertTrue(incompatible is GateResult.Reject)

        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(info(libAbis = setOf("arm64-v8a", "x86_64")), "com.example.module", device),
        )
        // Pure-Java modules have no lib/ at all.
        assertEquals(GateResult.Ok, ApkGate.evaluate(info(), "com.example.module", device))
    }

    @Test
    fun packageMismatchWithoutDeclarationIsACompanionApp() {
        val result = ApkGate.evaluate(
            info(packageName = "com.example.wearable"),
            "com.example.module",
            device,
        )
        assertTrue(result is GateResult.Reject)
        result as GateResult.Reject
        assertEquals(Reason.Kind.PACKAGE_MISMATCH, result.reason.kind)
    }

    @Test
    fun packageMismatchWithAnyDeclarationOnlyWarns() {
        listOf(
            info(packageName = "dev.example.renamed", hasProvider = true),
            info(packageName = "dev.example.renamed", hasModuleProp = true),
            info(packageName = "dev.example.renamed", hasLegacy = true),
        ).forEach { candidate ->
            val result = ApkGate.evaluate(candidate, "com.example.module", device)
            assertTrue(result is GateResult.Warn)
            assertEquals(Reason.Kind.PACKAGE_MISMATCH, (result as GateResult.Warn).reason.kind)
        }
    }

    @Test
    fun matchingPackageNeedsNoDeclaration() {
        assertEquals(GateResult.Ok, ApkGate.evaluate(info(), "com.example.module", device))
    }

    @Test
    fun newerLibXposedApiIsRejected() {
        val result = ApkGate.evaluate(
            info(minApiVersion = 103),
            "com.example.module",
            device,
        )
        assertTrue(result is GateResult.Reject)
        result as GateResult.Reject
        assertEquals(Reason.Kind.API_TOO_NEW, result.reason.kind)

        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(info(minApiVersion = 102), "com.example.module", device),
        )
    }

    @Test
    fun debuggableIsADisplayBadgeNotARejection() {
        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(info(debuggable = true), "com.example.module", device),
        )
    }
}
