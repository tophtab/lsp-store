package org.lspstore.domain.usecase

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.lspstore.data.install.InstallOutcome
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.variant.ModuleVariantState

/** Per-module progress of a batch update. */
enum class BatchInstallPhase { QUEUED, DOWNLOADING, WAITING_TO_INSTALL, INSTALLING, COMPLETED, FAILED, SKIPPED }

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
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

/** Resolves and downloads the APK for one update (fakeable for tests). */
interface ModuleInstallPreparer {
    suspend fun prepare(
        info: UpdateInfo,
        channel: UpdateChannel,
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
 * Runs "update all": downloads run concurrently, installs stay serialized
 * (installer dialogs must not stack), and the install queue follows download
 * completion order rather than repository order. A slow download therefore no
 * longer holds up modules whose APKs are already on disk.
 *
 * The caller owns the UI: it receives item transitions through [onItem] and the
 * "currently installing" label through [onCurrent].
 */
class BatchInstallUseCase(
    private val prepareInstall: ModuleInstallPreparer,
    private val installPrepared: ModuleInstaller,
    private val awaitInstallSettled: suspend (packageName: String, timeoutMs: Long) -> Boolean,
    private val releaseInstallSlot: (packageName: String) -> Unit,
    private val abandonDownload: (asset: ReleaseAsset) -> Unit,
) {

    data class Result(
        /** The external installer never returned; the rest of the queue was dropped. */
        val installInterrupted: Boolean,
        /** Every module that did not install, in the order the failures surfaced. */
        val failures: List<BatchFailure> = emptyList(),
    )

    suspend fun run(
        targets: List<UpdateInfo>,
        channel: UpdateChannel,
        variantStates: Map<String, ModuleVariantState>,
        isActive: () -> Boolean,
        onItem: (packageName: String, transform: (BatchInstallState) -> BatchInstallState) -> Unit,
        onCurrent: (info: UpdateInfo, total: Int) -> Unit,
    ): Result {
        val total = targets.size
        var installInterrupted = false
        // Downloads and installs run in different coroutines, so the collector
        // is shared: the caller gets the list only after supervisorScope joins.
        val failures = java.util.Collections.synchronizedList(mutableListOf<BatchFailure>())
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
                val downloadSlots = Semaphore(MAX_CONCURRENT_BATCH_DOWNLOADS)

                // Single consumer: wait for the global install slot, hand the
                // APK over, then take the next finished download.
                val installWorker = launch {
                    for (prepared in installQueue) {
                        if (!isActive()) break
                        val packageName = prepared.info.installed.packageName
                        onItem(packageName) { it.copy(phase = BatchInstallPhase.INSTALLING) }
                        onCurrent(prepared.info, total)
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
                    launch {
                        val packageName = info.module.packageName
                        var asset: ReleaseAsset? = null
                        var preparedNormally = false
                        try {
                            downloadSlots.withPermit {
                                onItem(packageName) {
                                    it.copy(phase = BatchInstallPhase.DOWNLOADING)
                                }
                                val prepared = prepareInstall.prepare(
                                    info = info,
                                    channel = channel,
                                    state = variantStates[packageName]
                                        ?: variantStates[info.installed.packageName]
                                        ?: ModuleVariantState(),
                                    onAssetResolved = { asset = it },
                                    onProgress = { downloaded, bytes ->
                                        onItem(packageName) {
                                            it.copy(downloaded = downloaded, total = bytes)
                                        }
                                    },
                                )
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
                                        recordFailure(
                                            info,
                                            BatchFailureKind.DOWNLOAD,
                                            prepared.message,
                                        )
                                        onItem(packageName) {
                                            it.copy(phase = BatchInstallPhase.FAILED)
                                        }
                                    }

                                    is PreparedInstall.NeedsVariant -> onItem(packageName) {
                                        it.copy(phase = BatchInstallPhase.SKIPPED)
                                    }
                                }
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            recordFailure(info, BatchFailureKind.DOWNLOAD, error.message)
                            onItem(packageName) { it.copy(phase = BatchInstallPhase.FAILED) }
                        } finally {
                            // A cancelled batch must also stop the transfer; the
                            // actual download lives in DownloadManager's scope.
                            if (!preparedNormally) {
                                asset?.let(abandonDownload)
                            }
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
        )
    }

    companion object {
        /** Parallel APK transfers; installs remain one-at-a-time. */
        private const val MAX_CONCURRENT_BATCH_DOWNLOADS = 3

        /**
         * How long a batch waits for the external installer to finish one hand-off
         * before giving up on the rest of the queue.
         */
        const val INSTALL_SETTLE_TIMEOUT_MS: Long = 180_000L
    }
}
