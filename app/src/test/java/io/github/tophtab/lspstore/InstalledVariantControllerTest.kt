package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.ui.screens.installed.InstalledUiState
import io.github.tophtab.lspstore.ui.screens.installed.InstalledVariantController
import io.github.tophtab.lspstore.ui.screens.installed.InstalledVariantText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledVariantControllerTest {
    @Test
    fun dismissAndReopenTheSamePackageDoesNotAcceptItsOldResponse() = runTest {
        val fixture = Fixture(this)
        var calls = 0
        fixture.detail = {
            val name = if (++calls == 1) {
                withContext(NonCancellable) { delay(100) }
                "old.apk"
            } else "new.apk"
            uiDetail(releases = listOf(uiRelease(names = listOf(name))))
        }
        fixture.controller.openVariantPicker("module")
        runCurrent()
        fixture.controller.dismissVariantPicker()
        fixture.controller.openVariantPicker("module")
        advanceUntilIdle()
        assertEquals("new.apk", fixture.state.value.variantPicker?.selectedAssetName)
        assertNull(fixture.state.value.variantPicker?.error)
    }

    @Test
    fun anExplicitPreferenceContinuesTheCallersUpdateThroughTheCoordinator() = runTest {
        val fixture = Fixture(this)
        fixture.controller.openVariantPicker("module", uiUpdate())
        runCurrent()
        fixture.controller.selectVariant("module.apk")
        runCurrent()
        assertEquals(listOf("module" to VariantSignature.ofAsset("module.apk")), fixture.preferences)
        assertNull(fixture.state.value.variantPicker)
        assertEquals("saved", fixture.state.value.message)
        assertTrue(fixture.requests.single().target!!.explicitSelection)
        assertEquals("module", fixture.requests.single().installedPackage)
        fixture.controller.openVariantPicker("module", uiUpdate())
        runCurrent()
        fixture.controller.selectVariant(null)
        runCurrent()
        assertEquals(listOf("module"), fixture.cleared)
        assertEquals("automatic", fixture.state.value.message)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun aLoadingOrFailedPickerCannotChangePreferences() = runTest {
        val fixture = Fixture(this)
        val detail = CompletableDeferred<ModuleDetail>()
        fixture.detail = { detail.await() }
        fixture.controller.openVariantPicker("module")
        runCurrent()
        fixture.controller.selectVariant(null)
        assertTrue(fixture.cleared.isEmpty())
        detail.completeExceptionally(IOException("offline"))
        runCurrent()
        assertEquals("load failed", fixture.state.value.variantPicker?.error)
        fixture.controller.selectVariant("module.apk")
        runCurrent()
        assertTrue(fixture.preferences.isEmpty())
    }

    @Test
    fun switchingModulesRejectsThePreviousPickersResponse() = runTest {
        val fixture = Fixture(this)
        fixture.detail = { pkg ->
            if (pkg == "module") withContext(NonCancellable) { delay(100) }
            uiDetail(module = uiModule(pkg), releases = listOf(uiRelease(names = listOf("$pkg.apk"))))
        }
        fixture.controller.openVariantPicker("module")
        runCurrent()
        fixture.controller.dismissVariantPicker()
        fixture.controller.openVariantPicker("next")
        advanceUntilIdle()
        assertEquals("next", fixture.state.value.variantPicker?.repoPackageName)
        assertEquals("next.apk", fixture.state.value.variantPicker?.selectedAssetName)
    }

    @Test
    fun aLateFailureDoesNotOverwriteTheReopenedPickersSuccess() = runTest {
        val fixture = Fixture(this)
        var calls = 0
        fixture.detail = {
            if (++calls == 1) withContext(NonCancellable) { delay(100); throw IOException("old failure") }
            uiDetail()
        }
        fixture.controller.openVariantPicker("module")
        runCurrent()
        fixture.controller.dismissVariantPicker()
        fixture.controller.openVariantPicker("module")
        advanceUntilIdle()
        assertEquals("module.apk", fixture.state.value.variantPicker?.selectedAssetName)
        assertNull(fixture.state.value.variantPicker?.error)
    }

    private class Fixture(scope: TestScope) {
        val state = MutableStateFlow(InstalledUiState())
        val preferences = mutableListOf<Pair<String, VariantSignature>>()
        val cleared = mutableListOf<String>()
        val requests = mutableListOf<ModuleInstallRequest>()
        var detail: suspend (String) -> ModuleDetail = { uiDetail() }
        val controller = InstalledVariantController(
            scope, { state.value }, { state.update(it) }, { detail(it) }, { it }, { uiInstalled(it) },
            ResolveVariantUseCase(ApkProbe { null }), DeviceProfile(36, listOf("arm64-v8a")),
            { cleared += it }, { pkg, signature -> preferences += pkg to signature }, { requests += it },
            InstalledVariantText({ "empty" }, { "load failed" }, { if (it) "automatic" else "saved" }),
        )
    }
}
