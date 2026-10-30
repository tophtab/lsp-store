package io.github.tophtab.lspstore.domain.usecase

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
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
 */
class BatchInstallUseCase(
    private val prepareInstall: ModuleInstallPreparer,
    private val installPrepared: ModuleInstaller,
    private val awaitInstallSettled: suspend (packageName: String, timeoutMs: Long) -> Boolean,
    private val releaseInstallSlot: (packageName: String) -> Unit,
    private val abandonDownload: (asset: ReleaseAsset) -> Unit,
    private val downloadSlots: DownloadSlots,
) {

    data class Result(
        /** The external installer never returned; the rest of the queue was dropped. */
        val installInterrupted: Boolean,
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
        includePreReleases: (packageName: String) -> Boolean,
        variantStates: Map<String, ModuleVariantState>,
        isActive: () -> Boolean,
        onItem: (packageName: String, transform: (BatchInstallState) -> BatchInstallState) -> Unit,
    ): Result {
        var installInterrupted = false
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
        val installQueue = Channel<PreparedInstall.Ready>(Channel.UNLIMITED)
        try {
            supervisorScope {
                // Single consumer: wait for the global install slot, hand the
                // APK over, then take the next finished download.
                val installWorker = launch {
                    for (prepared in installQueue) {
                        if (!isActive()) break
                        val packageName = prepared.info.installed.packageName
                        // A cancel can land after the download finished and the
                        // APK was queued: drop it instead of installing.
                        if (control.isCancelled(packageName)) {
                            onItem(packageName) {
                                it.copy(phase = BatchInstallPhase.CANCELLED)
                            }
                            abandonDownload(prepared.asset)
                            continue
                        }
                        onItem(packageName) { it.copy(phase = BatchInstallPhase.INSTALLING) }
                        val outcome = try {
                            installPrepared.install(prepared)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            InstallOutcome.Failure(error.message.orEmpty())
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

                            else -> BatchInstallPhase.COMPLETED
                        }
                        onItem(packageName) { it.copy(phase = phase) }
                        // A delegated install keeps the global slot until the
                        // package actually lands. Firing the next installer
                        // intent now would cancel the dialog that is still on
                        // screen (and silently drop that update), so wait for
                        // the hand-off to resolve before dequeuing.
                        if (outcome is InstallOutcome.DelegatedToSystem) {
                            delegated.incrementAndGet()
                            val settled = awaitInstallSettled(packageName, INSTALL_SETTLE_TIMEOUT_MS)
                            if (!settled) {
                                // Give the slot back even though the hand-off
                                // never resolved: the pending entry stays and
                                // the resume reconcile will finish it later.
                                releaseInstallSlot(packageName)
                                if (isActive()) {
                                    // Nothing failed inside the app: the dialog
                                    // is simply still waiting for the user, so
                                    // the item counts as skipped, not failed.
                                    onItem(packageName) {
                                        it.copy(phase = BatchInstallPhase.SKIPPED)
                                    }
                                    installInterrupted = true
                                    break
                                }
                            }
                        }
                    }
                }

                val downloads = targets.map { info ->
                    val packageName = info.module.packageName
                    launch {
                        val self = coroutineContext[Job]
                        if (self != null) control.attach(packageName, self)
                        var asset: ReleaseAsset? = null
                        var preparedNormally = false
                        try {
                            if (control.isCancelled(packageName)) {
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.CANCELLED)
                                }
                                return@launch
                            }
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
                                val prepared = prepareInstall.prepare(
                                    info = info,
                                    includePreReleases = includePreReleases(packageName),
                                    state = variantStates[packageName]
                                        ?: variantStates[info.installed.packageName]
                                        ?: ModuleVariantState(),
                                    onAssetResolved = { resolved ->
                                        asset = resolved
                                        control.assetResolved(packageName, resolved)
                                    },
                                    onProgress = { downloaded, bytes ->
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
                                        installQueue.send(prepared)
                                    }

                                    is PreparedInstall.Failed -> {
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

                                    is PreparedInstall.NeedsVariant -> onItem(packageName) {
                                        it.copy(phase = BatchInstallPhase.SKIPPED)
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
                            }
                            if (self != null) control.detach(packageName, self)
                        }
                    }
                }

                downloads.joinAll()
                installQueue.close()
                installWorker.join()
            }
        } finally {
            installQueue.cancel()
        }
        return Result(
            installInterrupted = installInterrupted,
            failures = synchronized(failures) { failures.toList() },
            delegated = delegated.get(),
        )
    }

    companion object {
        /**
         * How long a batch waits for the external installer to finish one hand-off
         * before giving up on the rest of the queue.
         */
        const val INSTALL_SETTLE_TIMEOUT_MS: Long = 180_000L
    }
}
