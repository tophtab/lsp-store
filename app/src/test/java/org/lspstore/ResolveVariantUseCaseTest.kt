package org.lspstore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.usecase.ResolveVariantUseCase
import org.lspstore.domain.variant.ApkMeta
import org.lspstore.domain.variant.ApkProbe
import org.lspstore.domain.variant.DeviceProfile
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.Reason
import org.lspstore.domain.variant.VariantDecision
import org.lspstore.domain.variant.VariantSignature

class ResolveVariantUseCaseTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun trustedModulesNeverProbe() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-universal.apk")
        val probe = FakeProbe(emptyMap())
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(abi = "arm64-v8a"),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(0, probe.calls)
        assertTrue(resolution.probes.isEmpty())
    }

    @Test
    fun firstInstallProbesAndRejectsIncompatibleRealAbi() = runBlocking {
        val release = releaseOf("app.apk")
        val probe = FakeProbe(
            mapOf(
                "app.apk" to ApkMeta(
                    packageName = "com.example.module",
                    abis = setOf("riscv64"),
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.NotApplicable)
        assertEquals(
            Reason.Kind.ABI_INCOMPATIBLE,
            (resolution.decision as VariantDecision.NotApplicable).reason.kind,
        )
        assertEquals(1, probe.calls)
        assertEquals(1, resolution.probes.size)
    }

    @Test
    fun ambiguousReleaseProbesTheTopCandidatesAndReResolves() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(
                    packageName = "com.example.module",
                    minSdk = 33,
                    hasXposedDeclaration = true,
                ),
                "OS3_Module.apk" to ApkMeta(
                    packageName = "com.example.module",
                    minSdk = 35,
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("OS3_Module.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(2, probe.calls)
    }

    @Test
    fun notApplicableIsOnlyRecheckedOnTheNameLayer() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(minSdk = 33),
                "OS3_Module.apk" to ApkMeta(minSdk = 35),
            ),
        )
        val state = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE)
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Choose)
        assertEquals(0, probe.calls)
    }

    @Test
    fun userOpeningThePickerMayProbeANotApplicableModule() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(minSdk = 33, hasXposedDeclaration = true),
                "OS3_Module.apk" to ApkMeta(minSdk = 35, hasXposedDeclaration = true),
            ),
        )
        val state = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE)
        val resolution = ResolveVariantUseCase(probe)
            .resolve(
                release,
                state,
                device,
                expectedPackage = "com.example.module",
                forceProbe = true,
            )

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals(2, probe.calls)
    }

    @Test
    fun suspectModulesProbeAgain() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk")
        val probe = FakeProbe(
            mapOf(
                "app-arm64-v8a.apk" to ApkMeta(
                    packageName = "com.example.module",
                    abis = setOf("arm64-v8a"),
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.SUSPECT,
            signature = VariantSignature(abi = "arm64-v8a"),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(1, probe.calls)
    }

    @Test
    fun probeFailureKeepsTheNameLayerDecision() = runBlocking {
        val release = releaseOf("app.apk")
        val probe = FakeProbe(emptyMap())
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("app.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(1, probe.calls)
        assertTrue(resolution.probes.isEmpty())
    }

    private class FakeProbe(private val metas: Map<String, ApkMeta>) : ApkProbe {
        var calls = 0
            private set

        override suspend fun fetch(asset: ReleaseAsset): ApkMeta? {
            calls++
            return metas[asset.name]
        }
    }

    private fun releaseOf(vararg names: String): Release = Release(
        tagName = "1-1.0",
        name = null,
        url = null,
        isPrerelease = false,
        publishedAt = null,
        descriptionHtml = null,
        version = ModuleVersion.parse("1-1.0"),
        assets = names.map { name ->
            ReleaseAsset(
                name = name,
                downloadUrl = "https://example.invalid/$name",
                fallbackUrl = null,
                size = 1024,
                downloadCount = 0,
                contentType = null,
            )
        },
    )
}
