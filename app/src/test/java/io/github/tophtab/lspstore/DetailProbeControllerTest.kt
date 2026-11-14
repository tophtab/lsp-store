package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.ui.screens.detail.DetailProbeController
import io.github.tophtab.lspstore.ui.screens.detail.ModuleDetailUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailProbeControllerTest {
    @Test
    fun aSingleApkClearsOldPickerMetadataWithoutProbing() = runTest {
        val state = MutableStateFlow(ModuleDetailUiState(isProbing = true, probedTag = "old", probes = mapOf("old" to ApkMeta())))
        var calls = 0
        val controller = controller(this, state, ApkProbe { calls++; null })
        controller.probeRelease(uiRelease(), forceProbe = true)
        runCurrent()
        assertEquals(0, calls)
        assertFalse(state.value.isProbing)
        assertTrue(state.value.probes.isEmpty())
        assertNull(state.value.probedTag)
        assertNull(state.value.variantResolution)
    }

    @Test
    fun aReloadWithTheSameTagRejectsTheOldRequestsMetadata() = runTest {
        val state = MutableStateFlow(ModuleDetailUiState())
        val old = uiRelease(names = listOf("old-a.apk", "old-b.apk"))
        val fresh = uiRelease(names = listOf("new-a.apk", "new-b.apk"))
        val controller = controller(this, state, ApkProbe { asset ->
            if (asset.name.startsWith("old")) withContext(NonCancellable) { delay(100) }
            ApkMeta(packageName = asset.name, versionCode = 2)
        })
        controller.probeRelease(old, forceProbe = true)
        runCurrent()
        assertTrue(state.value.isProbing)
        controller.probeRelease(fresh, forceProbe = true)
        advanceUntilIdle()
        assertEquals(fresh, state.value.probedRelease)
        assertEquals(setOf("new-a.apk", "new-b.apk"), state.value.probes.keys)
        assertFalse(state.value.isProbing)
    }

    @Test
    fun loadCancellationDoesNotRepopulateClearedMetadata() = runTest {
        val state = MutableStateFlow(ModuleDetailUiState())
        val controller = controller(this, state, ApkProbe {
            withContext(NonCancellable) { delay(100) }
            ApkMeta(packageName = "late")
        })
        controller.probeRelease(uiRelease(names = listOf("a.apk", "b.apk")), forceProbe = true)
        runCurrent()
        controller.cancel()
        state.update { it.copy(isProbing = false, probes = emptyMap()) }
        advanceUntilIdle()
        assertFalse(state.value.isProbing)
        assertTrue(state.value.probes.isEmpty())
        assertNull(state.value.variantResolution)
    }

    private fun controller(scope: TestScope, state: MutableStateFlow<ModuleDetailUiState>, probe: ApkProbe) =
        DetailProbeController(scope, { state.value }, { state.update(it) }, "module",
            ResolveVariantUseCase(probe), DeviceProfile(36, listOf("arm64-v8a")), { ModuleVariantState() })
}
