package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.install.ApkGate
import io.github.tophtab.lspstore.data.install.ArchiveInfo
import io.github.tophtab.lspstore.data.install.GateResult
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason

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
        targetApiVersion: Int? = null,
        hasLegacyInit: Boolean = false,
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
        targetApiVersion = targetApiVersion,
        hasLegacyInit = hasLegacyInit,
    )

    @Test
    fun minSdkAboveDeviceOnlyWarns() {
        val result = ApkGate.evaluate(info(minSdk = 37), "com.example.module", device)
        // The system installer refuses the package, not the gate: the user asked
        // for the attempt and only sees the reason in the install log (#75).
        assertTrue(result is GateResult.Warn)
        assertEquals(Reason.Kind.MIN_SDK_TOO_HIGH, (result as GateResult.Warn).reason.kind)
    }

    @Test
    fun aForeignLibAbiOnlyWarns() {
        val incompatible = ApkGate.evaluate(
            info(libAbis = setOf("riscv64")),
            "com.example.module",
            device,
        )
        // A foreign lib/ directory never fails an install, and the module may not
        // load its native part at all, so it is advisory like the API number.
        assertTrue(incompatible is GateResult.Warn)
        assertEquals(
            Reason.Kind.ABI_INCOMPATIBLE,
            (incompatible as GateResult.Warn).reason.kind,
        )

        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(info(libAbis = setOf("arm64-v8a", "x86_64")), "com.example.module", device),
        )
        // Pure-Java modules have no lib/ at all.
        assertEquals(GateResult.Ok, ApkGate.evaluate(info(), "com.example.module", device))
    }

    @Test
    fun aCompanionAppIsRejectedEvenWhenItsApiAndAbiOnlyWarn() {
        val result = ApkGate.evaluate(
            info(packageName = "com.example.wearable", minApiVersion = 103, libAbis = setOf("riscv64")),
            "com.example.module",
            device,
        )
        assertTrue(result is GateResult.Reject)
        assertEquals(Reason.Kind.PACKAGE_MISMATCH, (result as GateResult.Reject).reason.kind)
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
            info(packageName = "dev.example.renamed", hasLegacyInit = true),
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
    fun newerLibXposedApiOnlyWarns() {
        val result = ApkGate.evaluate(
            info(minApiVersion = 103),
            "com.example.module",
            device,
        )
        assertTrue(result is GateResult.Warn)
        result as GateResult.Warn
        assertEquals(Reason.Kind.API_TOO_NEW, result.reason.kind)

        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(info(minApiVersion = 102), "com.example.module", device),
        )
    }

    @Test
    fun legacyEntryAndTargetMakeTheDeclaredFloorAdvisory() {
        // A legacy module can carry module.prop for the repository listing; the
        // framework loads it through assets/xposed_init, so the floor is moot.
        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(
                info(minApiVersion = 103, hasLegacyInit = true),
                "com.example.module",
                device,
            ),
        )
        // The loaders select on targetApiVersion first, not on minApiVersion.
        assertEquals(
            GateResult.Ok,
            ApkGate.evaluate(
                info(minApiVersion = 103, targetApiVersion = 101),
                "com.example.module",
                device,
            ),
        )
        val newerTarget = ApkGate.evaluate(
            info(minApiVersion = 100, targetApiVersion = 103),
            "com.example.module",
            device,
        )
        assertTrue(newerTarget is GateResult.Warn)
        assertEquals(
            listOf("103"),
            (newerTarget as GateResult.Warn).reason.args,
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
