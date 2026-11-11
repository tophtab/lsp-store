package io.github.tophtab.lspstore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature

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
    fun firstInstallProbesAndOnlyWarnsAboutAForeignRealAbi() = runBlocking {
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

        // The native library directory is advisory; the installer decides.
        assertTrue(resolution.decision is VariantDecision.Pick)
        val candidate = VariantResolver(device)
            .candidates(release, probes = resolution.probes)
            .single()
        assertEquals(Reason.Kind.ABI_INCOMPATIBLE, candidate.warning?.kind)
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
    fun aTrustedModuleIsStillProbedWhenOnlyTheFileNameLooksWrong() = runBlocking {
        // Every APK name contains a companion-app word, so the name layer alone
        // would report "not applicable". The memory says this module installs
        // here, and the archive can prove which file is the module (#71, #73).
        val release = releaseOf("Module_Client.apk", "Module_Client_Lite.apk")
        val probe = FakeProbe(
            mapOf(
                "Module_Client.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
                "Module_Client_Lite.apk" to ApkMeta(
                    packageName = "com.other.app",
                    hasXposedDeclaration = false,
                ),
            ),
        )
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(abi = "arm64-v8a"),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("Module_Client.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        // Both folded candidates are read: the archive, not the name, decides.
        assertEquals(2, probe.calls)
    }

    @Test
    fun userOpeningThePickerProbesEvenATrustedModule() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(minSdk = 33, hasXposedDeclaration = true),
                "OS3_Module.apk" to ApkMeta(minSdk = 35, hasXposedDeclaration = true),
            ),
        )
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(os = "hyperos2"),
        )
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

    @Test
    fun aNameThatLooksLikeACompanionAppIsProbedInsteadOfHidden() = runBlocking {
        val release = releaseOf("Module_Client.apk", "Module_Provider.apk")
        val probe = FakeProbe(
            mapOf(
                "Module_Client.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
                "Module_Provider.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
            ),
        )
        // Every name looks like a companion app, so the name layer alone would
        // write a permanent "not applicable" verdict. One probe settles it.
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Choose)
        assertEquals(
            setOf("Module_Client.apk", "Module_Provider.apk"),
            (resolution.decision as VariantDecision.Choose).candidates.map { it.asset.name }.toSet(),
        )
        assertEquals(2, probe.calls)
    }

    @Test
    fun aMissingApkIsAVerdictWithoutProbing() = runBlocking {
        val release = Release(
            tagName = "1-1.0",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("1-1.0"),
            assets = emptyList(),
        )
        val probe = FakeProbe(emptyMap())
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertEquals(
            Reason.Kind.NO_APK,
            (resolution.decision as VariantDecision.NotApplicable).reason.kind,
        )
        assertEquals(0, probe.calls)
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
