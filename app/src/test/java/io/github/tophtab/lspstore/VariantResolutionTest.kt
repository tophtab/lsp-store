package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.*
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VariantResolutionTest {
    private val device = DeviceProfile(36, listOf("arm64-v8a"))

    @Test
    fun initialRowsAndSingleApkResultsNeverReadTheProbeCache() = runBlocking {
        var cacheReads = 0
        var fetches = 0
        val useCase = ResolveVariantUseCase(object : ApkProbe {
            override suspend fun cached(asset: ReleaseAsset): ApkMeta? { cacheReads++; return null }
            override suspend fun fetch(asset: ReleaseAsset): ApkMeta? { fetches++; return null }
        })
        val release = uiRelease(names = listOf("module-arm64.apk", "module-universal.apk"))
        assertEquals(2, useCase.initial(release, ModuleVariantState(), device).candidates.size)
        val single = useCase.resolve(uiRelease(), ModuleVariantState(), device, forceProbe = true)
        assertEquals("module.apk", single.candidates.single().asset.name)
        assertFalse(single.candidates.single().recommended)
        assertEquals(0, cacheReads)
        assertEquals(0, fetches)
    }

    @Test
    fun aRememberedAlternativeIsFirstAndEveryOtherApkRemainsSelectable() {
        val release = uiRelease(names = listOf("app-release.apk", "app-release-lite.apk"))
        val state = ModuleVariantState(explicitPreference = VariantSignature.ofAsset("app-release-lite.apk"))
        val resolution = ResolveVariantUseCase(ApkProbe { null }).initial(release, state, device)
        assertEquals("app-release-lite.apk", resolution.candidates.first().asset.name)
        assertTrue(resolution.candidates.first().recommended)
        assertEquals(1, resolution.candidates.count { it.recommended })
        assertTrue(resolution.candidates.all { it.blocked == null })
        assertEquals((resolution.decision as VariantDecision.Pick).asset, resolution.candidates.first().asset)
    }

    @Test
    fun probesRefineTheDecisionAndRowsTogether() = runBlocking {
        val release = uiRelease(names = listOf("module-arm64.apk", "module-universal.apk"))
        val useCase = ResolveVariantUseCase(ApkProbe { asset ->
            ApkMeta(packageName = if (asset.name.contains("universal")) "module" else "other", hasXposedDeclaration = true)
        })
        val resolution = useCase.resolve(release, ModuleVariantState(), device, "module", forceProbe = true, installed = InstalledVariantHint("module"))
        assertEquals("module-universal.apk", resolution.candidates.first().asset.name)
        assertEquals((resolution.decision as VariantDecision.Pick).asset, resolution.candidates.first().asset)
        assertTrue(resolution.candidates.first().recommended)
        assertTrue(resolution.candidates.all { it.meta == resolution.probes[it.asset.name] })
    }

    @Test
    fun thrownProbeFailuresKeepTheImmediateDecisionAndRows() = runBlocking {
        val useCase = ResolveVariantUseCase(ApkProbe { throw IOException("offline") })
        val release = uiRelease(names = listOf("module-arm64.apk", "module-universal.apk"))
        val initial = useCase.initial(release, ModuleVariantState(), device)
        val resolved = useCase.resolve(release, ModuleVariantState(), device, forceProbe = true)
        assertEquals(initial.decision, resolved.decision)
        assertEquals(initial.candidates, resolved.candidates)
        assertTrue(resolved.probes.isEmpty())
    }
}
