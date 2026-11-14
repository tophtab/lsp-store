package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.data.install.InstallTaskRegistry
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.ModuleInstallPreparer
import io.github.tophtab.lspstore.domain.usecase.ModuleInstaller
import io.github.tophtab.lspstore.domain.usecase.PreparedInstall
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.usecase.batchCompletion
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.ui.screens.installed.InstalledUiState
import io.github.tophtab.lspstore.ui.screens.installed.InstalledUpdateController
import io.github.tophtab.lspstore.ui.screens.installed.InstalledUpdateText
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class InstalledUpdateControllerTest {
    @Test
    fun rapidSingleTapsAndUpdateAllShareOwnershipDuringResolution() = runTest {
        val fixture = Fixture(this, listOf(uiUpdate("single"), uiUpdate("batch")))
        val detail = CompletableDeferred<ModuleDetail>()
        fixture.detail = { detail.await() }
        fixture.controller.update(fixture.state.value.updates.first())
        fixture.controller.update(fixture.state.value.updates.first())
        fixture.controller.updateAll()
        runCurrent()
        assertEquals(listOf("single"), fixture.detailCalls)
        assertEquals(listOf("batch"), fixture.preparations)
        detail.complete(uiDetail(uiModule("single")))
        advanceUntilIdle()
        assertEquals(1, fixture.requests.size)
        assertEquals("single", fixture.requests.single().repoPackageName)
        assertEquals(2L, fixture.requests.single().versionCode)
        assertTrue(fixture.registry.owns(fixture.requests.single().taskId!!))
    }

    @Test
    fun cancelledSingleResolutionCannotSubmitLateAndItsClaimCanBeRetried() = runTest {
        val fixture = Fixture(this)
        var attempts = 0
        fixture.detail = {
            if (++attempts == 1) withContext(NonCancellable) { delay(100) }
            uiDetail()
        }
        fixture.controller.update(uiUpdate())
        runCurrent()
        fixture.controller.cancelRow("module")
        advanceTimeBy(100)
        runCurrent()
        assertTrue(fixture.requests.isEmpty())
        assertFalse(fixture.registry.isRunning("module"))
        assertNull(fixture.state.value.message)
        fixture.controller.update(uiUpdate())
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals(2, attempts)
    }

    @Test
    fun failedAndStaleSingleResolutionReleaseOwnershipForRetry() = runTest {
        for (stale in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.detail = { if (stale) uiDetail(releases = emptyList()) else throw IOException("offline") }
            fixture.controller.update(uiUpdate())
            runCurrent()
            assertFalse(fixture.registry.isRunning("module"))
            assertEquals(if (stale) "stale" else "check failed", fixture.state.value.message)
            fixture.detail = { uiDetail() }
            fixture.controller.update(uiUpdate())
            runCurrent()
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun restoredPendingOwnershipBlocksSingleAndBatchUntilSettled() = runTest {
        val fixture = Fixture(this)
        fixture.registry.restore(listOf(PendingInstall("installed.alias", "module", taskId = "pending")))
        fixture.controller.update(uiUpdate("module", "installed.alias"))
        fixture.controller.updateAll()
        runCurrent()
        assertTrue(fixture.detailCalls.isEmpty())
        assertTrue(fixture.preparations.isEmpty())
        assertEquals(BatchInstallPhase.PENDING_CONFIRMATION, fixture.state.value.detailRuns["module"]?.phase)
        fixture.registry.release("pending")
        runCurrent()
        fixture.controller.update(uiUpdate("module", "installed.alias"))
        runCurrent()
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun aStoreUpdateUsingAnInstalledAliasIsInstalledLast() = runTest {
        val fixture = Fixture(this, listOf(uiUpdate("store.repo", "store"), uiUpdate("one"), uiUpdate("two")))
        fixture.prepare = { info ->
            delay(if (info.module.packageName == "one") 10 else 20)
            fixture.ready(info)
        }
        fixture.controller.updateAll()
        advanceUntilIdle()
        assertEquals(listOf("one", "two", "store.repo"), fixture.installs)
        assertEquals("store.repo", fixture.preparations.last())
        assertEquals("COMPLETED", fixture.state.value.message)
        assertEquals(1, fixture.reloads)
    }

    @Test
    fun completedAndFailedNoticesKeepTheirDurationsThenClear() = runTest {
        for ((failed, duration) in listOf(false to 1_200L, true to 3_000L)) {
            val fixture = Fixture(this)
            if (failed) fixture.prepare = { PreparedInstall.Failed(it, "download failed") }
            fixture.controller.updateAll()
            runCurrent()
            assertFalse(fixture.state.value.batchRunning)
            assertTrue(fixture.state.value.rowStates.getValue("module").noticeVisible)
            assertEquals(if (failed) "FAILURES" else "COMPLETED", fixture.state.value.message)
            advanceTimeBy(duration - 1)
            runCurrent()
            assertTrue(fixture.state.value.rowStates.getValue("module").noticeVisible)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(fixture.state.value.rowStates.isEmpty())
        }
    }

    @Test
    fun cancelAndImmediateRestartRetainTheNewBatchAndItsCancelControl() = runTest {
        val fixture = Fixture(this)
        fixture.prepare = { delay(10_000); fixture.ready(it) }
        fixture.controller.updateAll()
        runCurrent()
        fixture.controller.cancelBatch()
        runCurrent()
        fixture.controller.updateAll()
        runCurrent()
        assertTrue(fixture.state.value.batchRunning)
        assertEquals(1, fixture.state.value.batchTotal)
        assertNull(fixture.state.value.message)
        assertEquals(2, fixture.preparations.size)
        fixture.controller.cancelBatch()
        runCurrent()
        assertFalse(fixture.state.value.batchRunning)
        assertTrue(fixture.state.value.rowStates.isEmpty())
        assertFalse(fixture.registry.isRunning("module"))
    }

    @Test
    fun anOldNoticeCannotHideTheSameRowsNewCompletedNotice() = runTest {
        val fixture = Fixture(this, listOf(uiUpdate("fast"), uiUpdate("slow")))
        var fastAttempts = 0
        fixture.prepare = { info ->
            if (info.module.packageName == "slow") delay(10_000)
            else if (++fastAttempts > 1) delay(1_000)
            fixture.ready(info)
        }
        fixture.controller.updateAll()
        runCurrent()
        assertEquals(BatchInstallPhase.COMPLETED, fixture.state.value.rowStates["fast"]?.phase)
        fixture.controller.cancelBatch()
        runCurrent()
        fixture.controller.updateAll()
        runCurrent()
        advanceTimeBy(1_200)
        runCurrent()
        assertTrue(fixture.state.value.batchRunning)
        assertEquals(BatchInstallPhase.COMPLETED, fixture.state.value.rowStates["fast"]?.phase)
        assertTrue(fixture.state.value.rowStates.getValue("fast").noticeVisible)
        fixture.controller.cancelBatch()
        runCurrent()
    }

    private class Fixture(scope: TestScope, updates: List<UpdateInfo> = listOf(uiUpdate())) {
        val state = MutableStateFlow(InstalledUiState(isLoading = false, updates = updates))
        val registry = InstallTaskRegistry()
        val requests = mutableListOf<ModuleInstallRequest>()
        val detailCalls = mutableListOf<String>()
        val preparations = mutableListOf<String>()
        val installs = mutableListOf<String>()
        var reloads = 0
        var detail: suspend (String) -> ModuleDetail = { uiDetail(uiModule(it)) }
        var prepare: suspend (UpdateInfo) -> PreparedInstall = { ready(it) }
        private val asset = uiRelease().apkAssets.single()
        private val resolver = ResolveVariantUseCase(ApkProbe { null })
        private val device = DeviceProfile(36, listOf("arm64-v8a"))
        private val batch = BatchInstallUseCase(
            prepareInstall = object : ModuleInstallPreparer {
                override suspend fun prepare(
                    info: UpdateInfo,
                    includePreReleases: Boolean,
                    state: ModuleVariantState,
                    onAssetResolved: (ReleaseAsset) -> Unit,
                    onProgress: (Long, Long) -> Unit,
                ): PreparedInstall {
                    preparations += info.module.packageName
                    onAssetResolved(asset)
                    onProgress(1, asset.size)
                    return this@Fixture.prepare(info)
                }
            },
            installPrepared = object : ModuleInstaller {
                override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
                    installs += prepared.info.module.packageName
                    return InstallOutcome.Completed
                }
            },
            abandonDownload = {},
            downloadSlots = DownloadSlots { 3 },
            registry = registry,
        )
        val controller = InstalledUpdateController(
            scope = scope,
            readState = { state.value },
            updateState = { state.update(it) },
            batchInstall = batch,
            registry = registry,
            getDetail = { detailCalls += it; detail(it) },
            resolveVariant = { release, variant, installed ->
                resolver.resolve(release, variant, device, installed.packageName, installed = InstalledVariantHint.of(installed))
            },
            recordFailure = {},
            startInstall = { requests += it },
            cancelInstall = registry::cancel,
            reloadInstalled = { reloads++ },
            selfPackage = "store",
            text = InstalledUpdateText({ "stale" }, { "check failed" }, { "variant failed" },
                { result, items -> batchCompletion(result, items).name }, { "cancelled" }),
        )

        init {
            scope.backgroundScope.launch { registry.runs.collect(controller::onRunsChanged) }
        }

        fun ready(info: UpdateInfo) = PreparedInstall.Ready(info, File("unused.apk"), asset, ModuleVariantState())
    }
}
