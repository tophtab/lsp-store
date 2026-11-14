package io.github.tophtab.lspstore.ui.screens.installed

import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.data.install.InstallTaskRegistry
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.usecase.BatchInstallControl
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.domain.usecase.BatchInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.VariantResolution
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Terminal row notices use the same durations as before the extraction. */
private const val COMPLETED_NOTICE_MS = 1_200L
private const val FAILED_NOTICE_MS = 3_000L
private val ACTIVE_PHASES = setOf(
    BatchInstallPhase.QUEUED,
    BatchInstallPhase.PREPARING,
    BatchInstallPhase.DOWNLOADING,
    BatchInstallPhase.WAITING_TO_INSTALL,
    BatchInstallPhase.INSTALLING,
)

/** Owns update runs and their notices; installation still belongs to the app coordinator. */
internal class InstalledUpdateController(
    private val scope: CoroutineScope,
    private val readState: () -> InstalledUiState,
    private val updateState: ((InstalledUiState) -> InstalledUiState) -> Unit,
    private val batchInstall: BatchInstallUseCase,
    private val registry: InstallTaskRegistry,
    private val getDetail: suspend (String) -> ModuleDetail,
    private val resolveVariant: suspend (Release, ModuleVariantState, InstalledModule) -> VariantResolution,
    private val recordFailure: suspend (String) -> Unit,
    private val startInstall: (ModuleInstallRequest) -> Unit,
    private val cancelInstall: (String) -> Unit,
    private val reloadInstalled: suspend () -> Unit,
    private val selfPackage: String,
    private val text: InstalledUpdateText,
) {
    private var batchJob: Job? = null
    private var batchControl: BatchInstallControl? = null
    private val startingUpdates = mutableSetOf<String>()

    /** Invalidates progress, completion and delayed notices from a replaced batch. */
    @Volatile
    private var batchGeneration = 0

    fun onRunsChanged(runs: Map<String, ModuleInstallRun>) {
        updateState { it.copy(detailRuns = runs.mapValues { (_, run) -> run.asRowState() }) }
    }

    fun update(info: UpdateInfo) {
        val packageName = info.module.packageName
        // A module page or an earlier tap may already own this package; the
        // coordinator check is synchronous, so a double tap cannot slip between
        // the row check and the chain start.
        if (readState().detailRuns.containsKey(packageName)) return
        if (registry.isRunning(packageName) || packageName in startingUpdates) return
        // A busy row of a running batch keeps its pill; retrying a row that
        // already reached a terminal phase (failed, skipped, cancelled) is fine.
        val row = readState().rowStates[packageName]
        if (row != null && row.phase in ACTIVE_PHASES) return
        startSingleRun(info)
    }

    /** Starts a batch for the available updates whose packages are not already busy. */
    fun updateAll() {
        // A module page install, an in-flight single update or a busy batch row
        // already owns its package; starting a second chain for it here would
        // download and install it twice.
        val busy = readState().detailRuns.keys +
            readState().rowStates.filterValues { it.phase in ACTIVE_PHASES }.keys +
            registry.runningPackages() +
            startingUpdates
        val targets = readState().updates.filterNot {
            it.module.packageName in busy
        }
        if (targets.isEmpty() || readState().batchRunning) return
        startRun(targets = targets)
    }

    /**
     * Single updates run through the app-scoped install coordinator (the same
     * chain a module page uses) instead of the batch: one module downloading no
     * longer disables the other rows, and a dismissed installer never parks the
     * screen in a waiting state — the row simply falls back to Update, and the
     * pending reconciliation restores it once the hand-off is abandoned.
     */
    private fun startSingleRun(info: UpdateInfo) {
        val repoPackage = info.module.packageName
        startingUpdates += repoPackage
        scope.launch {
            var taskId: String? = null
            var submitted = false
            try {
                val claimed = registry.claim(repoPackage, info.installed.packageName) ?: return@launch
                taskId = claimed
                registry.stage(claimed, InstallStage.PREPARING)
                val job = coroutineContext[Job]
                registry.onCancel(claimed) { job?.cancel() }
                val detail = getDetail(repoPackage)
                currentCoroutineContext().ensureActive()
                val includePreReleases = readState().preReleases.tracksPreReleases(
                    info.installed.packageName, repoPackage,
                )
                val release = detail.releaseForUpdate(info.latest, includePreReleases)
                if (release == null) {
                    updateState { it.copy(message = text.repositoryStale()) }
                    return@launch
                }
                val state = readState().variantStates[repoPackage]
                    ?: readState().variantStates[info.installed.packageName]
                    ?: ModuleVariantState()
                val resolution = resolveVariant(release, state.forInstalled(info.installed), info.installed)
                currentCoroutineContext().ensureActive()
                when (val decision = resolution.decision) {
                    is VariantDecision.Pick -> {
                        val version = release.version
                        submitted = true
                        startInstall(
                            ModuleInstallRequest(
                                repoPackageName = repoPackage,
                                title = info.module.title,
                                asset = decision.asset,
                                taskId = claimed,
                                target = ResolvedInstallTarget.of(release, decision.asset),
                                installedPackage = info.installed.packageName,
                                versionCode = version?.versionCode ?: info.latest.versionCode,
                                versionName = version?.versionName ?: info.latest.versionName,
                            ),
                        )
                    }
                    is VariantDecision.NotApplicable -> {
                        recordFailure(repoPackage)
                        currentCoroutineContext().ensureActive()
                        updateState { it.copy(message = text.variantReason(decision.reason)) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                updateState { it.copy(message = text.checkFailed(failure)) }
            } finally {
                startingUpdates -= repoPackage
                if (!submitted) taskId?.let(registry::release)
            }
        }
    }

    private fun startRun(targets: List<UpdateInfo>) {
        val generation = ++batchGeneration
        val total = targets.size
        val control = batchInstall.newControl()
        batchControl = control
        val initialItems = targets.associate {
            it.module.packageName to BatchInstallState(phase = BatchInstallPhase.QUEUED)
        }
        // Publish the run before launching so a second tap cannot start it again.
        updateState {
            it.copy(
                rowStates = initialItems,
                batchRunning = true,
                batchTotal = total,
                message = null,
            )
        }
        batchJob = scope.launch(start = CoroutineStart.LAZY) {
            val result = batchInstall.run(
                targets = targets,
                control = control,
                includePreReleases = { info ->
                    readState().preReleases.tracksPreReleases(
                        installedPackage = info.installed.packageName,
                        repoPackage = info.module.packageName,
                    )
                },
                variantStates = readState().variantStates,
                isActive = { isCurrentBatch(generation) },
                installLast = { info ->
                    info.module.packageName == selfPackage ||
                        info.installed.packageName == selfPackage
                },
                onItem = { packageName, transform ->
                    applyItem(generation, packageName, transform)
                },
            )
            reloadInstalled()
            if (!isCurrentBatch(generation)) return@launch
            val items = readState().rowStates.values
            val message = text.completion(result, items)
            updateState {
                it.copy(
                    message = message,
                    batchRunning = false,
                    batchTotal = 0,
                    rowStates = lingeringStates(it.rowStates),
                )
            }
            maybeClearRowStates()
            if (isCurrentBatch(generation)) {
                batchControl = null
                batchJob = null
            }
        }
        batchJob?.start()
    }

    /** Cancels one module of the running update; the row returns to Update. */
    fun cancelRow(packageName: String) {
        batchControl?.cancel(packageName)
        // The same pill also fronts an install started from a module page, and
        // that chain lives in the coordinator rather than in this batch.
        cancelInstall(packageName)
    }

    fun cancelBatch() {
        val job = batchJob ?: return
        // Invalidate the old job before cancelling: its cleanup must not wipe the
        // progress of a batch the user starts right after this one.
        batchGeneration++
        batchJob = null
        batchControl?.cancelAll()
        batchControl = null
        job.cancel()
        updateState {
            it.copy(
                rowStates = emptyMap(),
                batchRunning = false,
                batchTotal = 0,
                message = text.cancelled(),
            )
        }
    }

    private fun isCurrentBatch(generation: Int): Boolean = batchGeneration == generation

    private fun applyItem(
        generation: Int,
        packageName: String,
        transform: (BatchInstallState) -> BatchInstallState,
    ) {
        if (!isCurrentBatch(generation)) return
        val before = readState().rowStates[packageName]?.phase
        updateState { current ->
            if (!isCurrentBatch(generation)) return@updateState current
            val item = current.rowStates[packageName] ?: return@updateState current
            val next = transform(item)
            val duplicate = next.phase == BatchInstallPhase.ALREADY_RUNNING && item.phase != BatchInstallPhase.ALREADY_RUNNING
            current.copy(
                batchTotal = (current.batchTotal - if (duplicate) 1 else 0).coerceAtLeast(0),
                rowStates = current.rowStates + (packageName to next),
            )
        }
        val after = readState().rowStates[packageName]?.phase
        if (after == null || after == before) return
        val noticeMs = when (after) {
            BatchInstallPhase.COMPLETED -> COMPLETED_NOTICE_MS
            BatchInstallPhase.FAILED -> FAILED_NOTICE_MS
            else -> return
        }
        scope.launch {
            delay(noticeMs)
            if (!isCurrentBatch(generation)) return@launch
            updateState { current ->
                if (!isCurrentBatch(generation)) return@updateState current
                val item = current.rowStates[packageName] ?: return@updateState current
                if (item.phase != after) return@updateState current
                current.copy(
                    rowStates = current.rowStates + (
                        packageName to item.copy(noticeVisible = false)
                        ),
                )
            }
            maybeClearRowStates()
        }
    }

    /** Keeps only the terminal marks the user still needs to see. */
    private fun lingeringStates(
        states: Map<String, BatchInstallState>,
    ): Map<String, BatchInstallState> = states.filterValues {
        it.noticeVisible &&
            (it.phase == BatchInstallPhase.COMPLETED || it.phase == BatchInstallPhase.FAILED)
    }

    private fun maybeClearRowStates() {
        val current = readState()
        if (current.batchRunning) return
        if (current.rowStates.values.any { it.noticeVisible }) return
        if (current.rowStates.isNotEmpty()) {
            updateState { it.copy(rowStates = emptyMap()) }
        }
    }

    private fun ModuleInstallRun.asRowState(): BatchInstallState = BatchInstallState(
        phase = when (stage) {
            InstallStage.QUEUED -> BatchInstallPhase.QUEUED
            InstallStage.PREPARING -> BatchInstallPhase.PREPARING
            InstallStage.WAITING_TO_INSTALL -> BatchInstallPhase.WAITING_TO_INSTALL
            InstallStage.PENDING_CONFIRMATION -> BatchInstallPhase.PENDING_CONFIRMATION
            InstallStage.DOWNLOADING -> BatchInstallPhase.DOWNLOADING
            InstallStage.INSTALLING -> BatchInstallPhase.INSTALLING
        },
        downloaded = downloaded,
        total = total,
    )

}
