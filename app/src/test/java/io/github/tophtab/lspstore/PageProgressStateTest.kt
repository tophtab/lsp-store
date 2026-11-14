package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.ui.screens.detail.ModuleDetailUiState
import io.github.tophtab.lspstore.ui.screens.detail.withoutProgress
import io.github.tophtab.lspstore.ui.screens.installed.InstalledUiState
import io.github.tophtab.lspstore.ui.screens.installed.withoutProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageProgressStateTest {
    @Test
    fun byteUpdatesOnlyReachTheirOwnRowAndStageNoticeAndCancellationReachThePage() = runTest {
        val downloading = BatchInstallState(BatchInstallPhase.DOWNLOADING, total = 1_000)
        val full = MutableStateFlow(InstalledUiState(rowStates = mapOf("a" to downloading, "b" to downloading)))
        val pages = mutableListOf<InstalledUiState>()
        val rowA = mutableListOf<BatchInstallState?>()
        val rowB = mutableListOf<BatchInstallState?>()
        backgroundScope.launch { full.map(InstalledUiState::withoutProgress).distinctUntilChanged().collect { pages += it } }
        backgroundScope.launch { full.map { it.rowStates["a"] }.distinctUntilChanged().collect { rowA += it } }
        backgroundScope.launch { full.map { it.rowStates["b"] }.distinctUntilChanged().collect { rowB += it } }
        runCurrent()
        repeat(100) { n ->
            full.value = full.value.copy(rowStates = full.value.rowStates + ("a" to downloading.copy(downloaded = n + 1L)))
            runCurrent()
        }
        assertEquals(1, pages.size)
        assertEquals(101, rowA.size)
        assertEquals(1, rowB.size)
        full.value = full.value.copy(rowStates = full.value.rowStates + ("a" to downloading.copy(phase = BatchInstallPhase.COMPLETED)))
        runCurrent()
        full.value = full.value.copy(rowStates = full.value.rowStates + ("a" to full.value.rowStates.getValue("a").copy(noticeVisible = false)))
        runCurrent()
        full.value = full.value.copy(rowStates = emptyMap(), message = "cancelled")
        runCurrent()
        assertEquals(4, pages.size)
        assertEquals("cancelled", pages.last().message)
    }

    @Test
    fun detailBytesLeaveTheBodyAloneWhileLogsStagesAndCompletionRemainVisible() = runTest {
        val run = ModuleInstallRun("module", null, stage = InstallStage.DOWNLOADING, total = 1_000)
        val full = MutableStateFlow(ModuleDetailUiState(activeInstall = run))
        val pages = mutableListOf<ModuleDetailUiState>()
        val buttons = mutableListOf<ModuleInstallRun?>()
        backgroundScope.launch { full.map(ModuleDetailUiState::withoutProgress).distinctUntilChanged().collect { pages += it } }
        backgroundScope.launch { full.map { it.activeInstall }.distinctUntilChanged().collect { buttons += it } }
        runCurrent()
        repeat(100) { n ->
            full.value = full.value.copy(activeInstall = run.copy(downloaded = n + 1L))
            runCurrent()
        }
        assertEquals(1, pages.size)
        assertEquals(101, buttons.size)
        full.value = full.value.copy(activeInstall = run.copy(stage = InstallStage.INSTALLING, logs = listOf("ready")), installLogs = listOf("ready"))
        runCurrent()
        full.value = full.value.copy(activeInstall = null, message = "finished")
        runCurrent()
        assertEquals(3, pages.size)
        assertEquals("finished", pages.last().message)
        assertEquals(listOf("ready"), pages[1].installLogs)
    }
}
