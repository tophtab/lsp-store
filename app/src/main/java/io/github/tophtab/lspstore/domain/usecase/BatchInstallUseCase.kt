package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.data.install.InstallTaskRegistry
import io.github.tophtab.lspstore.data.install.InstallStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

/** Per-module progress of a batch update. */
enum class BatchInstallPhase {
    /** Waiting for a download slot. */
    QUEUED,

    /** Resolving the release/variant; no byte progress yet. */
    PREPARING,

    /** Transferring the APK. */
    DOWNLOADING,

    /** Downloaded; waiting for the serialized install slot. */
    WAITING_TO_INSTALL,

    INSTALLING,
    COMPLETED,
    PENDING_CONFIRMATION,
    ALREADY_RUNNING,
    FAILED,
    SKIPPED,

    /** The user cancelled this module before it reached the installer. */
    CANCELLED,
}

/** Why one module of a batch did not land. */
enum class BatchFailureKind {
    /** No APK could be resolved or transferred. */
    DOWNLOAD,

    /** The APK was there but the install was refused or crashed. */
    INSTALL,
}

/**
 * One module of a batch that did not install, with enough context for the UI to
 * explain it instead of only counting it. The UI renders [kind] with its own
 * strings and falls back to [detail] (already localized by the installer).
 */
data class BatchFailure(
    /** Repository package name, so the UI can open the module page. */
    val packageName: String,
    val title: String,
    val kind: BatchFailureKind,
    val detail: String? = null,
)

data class BatchInstallState(
    val phase: BatchInstallPhase,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    /**
     * Whether a terminal notice (the check after an update, the failure mark)
     * is still being shown. The row falls back to its idle Update button once
     * this is false; the entry itself stays until the run is cleared so the
     * batch summary can still count it.
     */
    val noticeVisible: Boolean = true,
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

enum class BatchCompletion { FAILURES, SKIPPED, CANCELLED, PROCESSED, COMPLETED }

fun batchCompletion(result: BatchInstallUseCase.Result, states: Collection<BatchInstallState>): BatchCompletion = when {
    result.failures.isNotEmpty() || states.any { it.phase == BatchInstallPhase.FAILED } -> BatchCompletion.FAILURES
    states.any { it.phase == BatchInstallPhase.SKIPPED } -> BatchCompletion.SKIPPED
    states.any { it.phase == BatchInstallPhase.CANCELLED } -> BatchCompletion.CANCELLED
    result.delegated > 0 || states.any { it.phase == BatchInstallPhase.ALREADY_RUNNING } -> BatchCompletion.PROCESSED
    else -> BatchCompletion.COMPLETED
}

/** Resolves and downloads the APK for one update (fakeable for tests). */
interface ModuleInstallPreparer {
    suspend fun prepare(
        info: UpdateInfo,
        includePreReleases: Boolean,
        state: ModuleVariantState,
        onAssetResolved: (ReleaseAsset) -> Unit = {},
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): PreparedInstall
}

/** Installs one already-downloaded APK (fakeable for tests). */
interface ModuleInstaller {
    suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome
}

/**
 * Per-run cancellation and file cleanup for one batch.
 *
 * The row that shows a download is also its cancel button, so cancelling has to
 * reach the coroutine waiting for a slot, the transfer inside DownloadManager
 * and the file it already wrote. A cancelled target is reported as
 * [BatchInstallPhase.CANCELLED] rather than a failure, so it never lands in the
 * failure summary.
 */
class BatchInstallControl(
    private val abandonDownload: (ReleaseAsset) -> Unit,
) {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val assets = ConcurrentHashMap<String, ReleaseAsset>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun cancel(packageName: String) {
        cancelled += packageName
        assets[packageName]?.let(abandonDownload)
        jobs[packageName]?.cancel()
    }

    fun cancelAll() {
        jobs.keys.toList().forEach(::cancel)
    }

    fun isCancelled(packageName: String): Boolean = packageName in cancelled

    internal fun attach(packageName: String, job: Job) {
        jobs[packageName] = job
        if (isCancelled(packageName)) job.cancel()
    }

    internal fun detach(packageName: String, job: Job) {
        jobs.remove(packageName, job)
    }

    internal fun assetResolved(packageName: String, asset: ReleaseAsset) {
        assets[packageName] = asset
        if (isCancelled(packageName)) abandonDownload(asset)
    }
}

/**
 * Runs "update all": downloads run concurrently, installs stay serialized
 * (installer dialogs must not stack), and the install queue follows download
 * completion order rather than repository order. A slow download therefore no
 * longer holds up modules whose APKs are already on disk.
 *
 * Downloads take their slots from the shared [DownloadSlots], which module page
 * installs use for their transfer too, so the maximum concurrent download
 * setting applies app-wide. The caller owns the UI: it receives item transitions
 * through [onItem].
 *
 * Targets matched by [run]'s `installLast` predicate start their download only
 * after every other target finished, so they are installed last.
 */
class BatchInstallUseCase(
    private val prepareInstall: ModuleInstallPreparer,
    private val installPrepared: ModuleInstaller,
    private val abandonDownload: (asset: ReleaseAsset) -> Unit,
    private val downloadSlots: DownloadSlots,
    private val registry: InstallTaskRegistry = InstallTaskRegistry(),
) {

    data class Result(
        /** Every module that did not install, in the order the failures surfaced. */
        val failures: List<BatchFailure> = emptyList(),
        /** Installs handed to an external installer; completion arrives later. */
        val delegated: Int = 0,
    )

    /** One control per run; the UI keeps it so a row can cancel itself. */
    fun newControl(): BatchInstallControl = BatchInstallControl(abandonDownload)

    suspend fun run(
        targets: List<UpdateInfo>,
        control: BatchInstallControl,
        includePreReleases: (target: UpdateInfo) -> Boolean,
        variantStates: Map<String, ModuleVariantState>,
        isActive: () -> Boolean,
        /**
         * Targets this predicate accepts are downloaded after every other target
         * finished its download. Their APKs therefore enter the FIFO install
         * queue last, so they are installed last too. The store uses this for its
         * own update: replacing the running APK commonly kills this process, and
         * that must not abort the rest of the batch.
         */
        installLast: (UpdateInfo) -> Boolean = { false },
        onItem: (packageName: String, transform: (BatchInstallState) -> BatchInstallState) -> Unit,
    ): Result {
        // Downloads and installs run in different coroutines, so the collector
        // is shared: the caller gets the list only after supervisorScope joins.
        val failures = java.util.Collections.synchronizedList(mutableListOf<BatchFailure>())
        val delegated = AtomicInteger()
        fun recordFailure(info: UpdateInfo, kind: BatchFailureKind, detail: String? = null) {
            failures += BatchFailure(
                packageName = info.module.packageName,
                title = info.module.title,
                kind = kind,
                detail = detail?.takeIf { it.isNotBlank() },
            )
        }
        val claims = ConcurrentHashMap<String, String>()
        val handedOff = ConcurrentHashMap.newKeySet<String>()
        val installQueue = Channel<PreparedInstall.Ready>(Channel.UNLIMITED)
        try {
            supervisorScope {
                // Single consumer: wait for the global install slot, hand the
                // APK over, then take the next finished download.
                val installWorker = launch {
                    for (prepared in installQueue) {
                        if (!isActive()) break
                        // Rows, the control map and the UI are all keyed by the
                        // repository package (the download side attaches with the
                        // same key). Keying the install half by the *installed*
                        // package left every linked or renamed module stuck at
                        // WAITING_TO_INSTALL, hid its failure from the summary and
                        // made a row cancel at the install stage a no-op.
                        val packageName = prepared.info.module.packageName
                        val cancelled = control.isCancelled(packageName) ||
                            control.isCancelled(prepared.info.installed.packageName)
                        // A cancel can land after the download finished and the
                        // APK was queued: drop it instead of installing.
                        if (cancelled) {
                            onItem(packageName) {
                                it.copy(phase = BatchInstallPhase.CANCELLED)
                            }
                            abandonDownload(prepared.asset)
                            prepared.taskId?.let(registry::release)
                            continue
                        }
                        prepared.taskId?.let { registry.stage(it, InstallStage.WAITING_TO_INSTALL) }
                        onItem(packageName) { it.copy(phase = BatchInstallPhase.INSTALLING) }
                        val installJob = async(start = CoroutineStart.LAZY) { installPrepared.install(prepared) }
                        control.attach(packageName, installJob)
                        val outcome = try {
                            installJob.await()
                        } catch (cancellation: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            if (!control.isCancelled(packageName)) throw cancellation
                            onItem(packageName) { it.copy(phase = BatchInstallPhase.CANCELLED) }
                            prepared.taskId?.let(registry::release)
                            abandonDownload(prepared.asset)
                            continue
                        } catch (error: Throwable) {
                            InstallOutcome.Failure(error.message.orEmpty())
                        } finally {
                            control.detach(packageName, installJob)
                        }
                        val phase = when (outcome) {
                            is InstallOutcome.Failure -> {
                                recordFailure(
                                    prepared.info,
                                    BatchFailureKind.INSTALL,
                                    outcome.message,
                                )
                                BatchInstallPhase.FAILED
                            }

                            InstallOutcome.DelegatedToSystem -> BatchInstallPhase.PENDING_CONFIRMATION
                            else -> BatchInstallPhase.COMPLETED
                        }
                        onItem(packageName) { it.copy(phase = phase) }
                        // A delegated install keeps the global slot until the
                        // package lands. The next install waits at that slot
                        // (released by the package broadcast or by the resume
                        // reconcile), so this queue can move on immediately:
                        // waiting here used to freeze "update all" for 180 s
                        // after one cancelled installer.
                        if (outcome is InstallOutcome.DelegatedToSystem) {
                            handedOff += requireNotNull(prepared.taskId)
                            prepared.taskId.let { registry.stage(it, InstallStage.PENDING_CONFIRMATION) }
                            delegated.incrementAndGet()
                        } else prepared.taskId?.let(registry::release)
                    }
                }

                /**
                 * Starts one target's download and returns its job. Extracted so
                 * the deferred targets can be started after the regular ones
                 * without duplicating the download/queue body.
                 */
                fun startDownload(scope: CoroutineScope, info: UpdateInfo): Job {
                    val packageName = info.module.packageName
                    return scope.launch {
                        val self = coroutineContext[Job]
                        if (self != null) control.attach(packageName, self)
                        var id: String? = null
                        var asset: ReleaseAsset? = null
                        var preparedNormally = false
                        try {
                            if (control.isCancelled(packageName)) {
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.CANCELLED)
                                }
                                return@launch
                            }
                            id = registry.claim(packageName, info.installed.packageName)
                            val taskId = id
                            if (taskId == null) {
                                onItem(packageName) { it.copy(phase = BatchInstallPhase.ALREADY_RUNNING) }
                                preparedNormally = true
                                return@launch
                            }
                            claims[packageName] = taskId
                            registry.onCancel(taskId) { control.cancel(packageName) }
                            downloadSlots.withSlot {
                                // A queued target may have been cancelled while
                                // it waited for the slot; never start it then.
                                if (control.isCancelled(packageName)) {
                                    onItem(packageName) {
                                        it.copy(phase = BatchInstallPhase.CANCELLED)
                                    }
                                    return@withSlot
                                }
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.PREPARING)
                                }
                                registry.stage(taskId, InstallStage.PREPARING)
                                val prepared = prepareInstall.prepare(
                                    info = info,
                                    includePreReleases = includePreReleases(info),
                                    state = variantStates[packageName]
                                        ?: variantStates[info.installed.packageName]
                                        ?: ModuleVariantState(),
                                    onAssetResolved = { resolved ->
                                        asset = resolved
                                        registry.asset(taskId, resolved)
                                        control.assetResolved(packageName, resolved)
                                    },
                                    onProgress = { downloaded, bytes ->
                                        registry.update(taskId) { it.copy(stage = InstallStage.DOWNLOADING, downloaded = downloaded, total = bytes) }
                                        onItem(packageName) {
                                            it.copy(
                                                phase = BatchInstallPhase.DOWNLOADING,
                                                downloaded = downloaded,
                                                total = bytes,
                                            )
                                        }
                                    },
                                )
                                // The cancel may have landed after the last
                                // byte: do not queue an APK the user stopped.
                                if (control.isCancelled(packageName)) {
                                    onItem(packageName) {
                                        it.copy(phase = BatchInstallPhase.CANCELLED)
                                    }
                                    return@withSlot
                                }
                                preparedNormally = true
                                when (prepared) {
                                    is PreparedInstall.Ready -> {
                                        onItem(packageName) {
                                            it.copy(
                                                phase = BatchInstallPhase.WAITING_TO_INSTALL,
                                                downloaded = it.total,
                                            )
                                        }
                                        registry.stage(taskId, InstallStage.WAITING_TO_INSTALL)
                                        installQueue.send(prepared.copy(taskId = taskId))
                                    }

                                    is PreparedInstall.Failed -> {
                                        registry.release(taskId)
                                        if (control.isCancelled(packageName)) {
                                            onItem(packageName) {
                                                it.copy(phase = BatchInstallPhase.CANCELLED)
                                            }
                                        } else {
                                            recordFailure(
                                                info,
                                                BatchFailureKind.DOWNLOAD,
                                                prepared.message,
                                            )
                                            onItem(packageName) {
                                                it.copy(phase = BatchInstallPhase.FAILED)
                                            }
                                        }
                                    }

                                    is PreparedInstall.NeedsVariant -> {
                                        registry.release(taskId)
                                        onItem(packageName) { it.copy(phase = BatchInstallPhase.SKIPPED) }
                                    }
                                }
                            }
                        } catch (cancellation: CancellationException) {
                            if (control.isCancelled(packageName)) {
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.CANCELLED)
                                }
                            }
                            throw cancellation
                        } catch (error: Throwable) {
                            if (control.isCancelled(packageName)) {
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.CANCELLED)
                                }
                            } else {
                                recordFailure(info, BatchFailureKind.DOWNLOAD, error.message)
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.FAILED)
                                }
                            }
                        } finally {
                            // A cancelled batch must also stop the transfer; the
                            // actual download lives in DownloadManager's scope.
                            if (!preparedNormally) {
                                asset?.let(abandonDownload)
                                id?.let(registry::release)
                            }
                            if (self != null) control.detach(packageName, self)
                        }
                    }
                }

                val (lastTargets, regularTargets) = targets.partition(installLast)
                regularTargets.map { info -> startDownload(this, info) }.joinAll()
                // Only now start the deferred downloads: their APKs enter the
                // FIFO install queue behind every regular one, so the store's own
                // update is installed last even if it downloads instantly.
                lastTargets.map { info -> startDownload(this, info) }.joinAll()
                installQueue.close()
                installWorker.join()
            }
        } finally {
            installQueue.cancel()
            claims.values.filterNot { it in handedOff }.forEach(registry::release)
        }
        return Result(
            failures = synchronized(failures) { failures.toList() },
            delegated = delegated.get(),
        )
    }
}
