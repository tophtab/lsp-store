package io.github.tophtab.lspstore.data.install

import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.domain.model.ReleaseAsset

/** Which part of a module install is currently running. */
enum class InstallStage { QUEUED, PREPARING, DOWNLOADING, WAITING_TO_INSTALL, INSTALLING, PENDING_CONFIRMATION }

/**
 * One in-flight install started from a module page. Progress is published so the
 * install button can fill up instead of a separate progress bar being shown.
 */
data class ModuleInstallRun(
    val repoPackageName: String,
    val asset: ReleaseAsset?,
    val taskId: String = "",
    val stage: InstallStage = InstallStage.QUEUED,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    /** Same mono log the module page shows under "Logs". */
    val logs: List<String> = emptyList(),
) {
    val progress: Float
        get() = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Everything the coordinator needs to install one already-chosen asset. */
data class ModuleInstallRequest(
    val repoPackageName: String,
    val title: String,
    val asset: ReleaseAsset,
    /** Installed package the APK replaces; the repository package name by default. */
    val installedPackage: String? = null,
    val versionCode: Long = 0L,
    val versionName: String = "",
    /** The user just picked this variant, so remembering it is news worth a toast. */
    val announceRemembered: Boolean = false,
    val target: ResolvedInstallTarget? = null,
    val taskId: String? = null,
)

/**
 * Download → installer for one module page, run on an application scope.
 *
 * komi keeps an install alive when its page is swiped away, and this app should
 * too: the old flow lived in the detail ViewModel, so pressing back mid-download
 * cancelled the install even though the transfer itself kept running. The chain
 * therefore outlives the screen and only publishes [runs] (live progress) and
 * [messages] (outcome text, best effort) for whatever instance of the page is
 * composed. All bookkeeping (variant memory, pending install, install result) is
 * written here, so it happens even when no screen is listening.
 */
class ModuleInstallCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloadManager: DownloadManager,
    private val downloadSlots: DownloadSlots,
    private val libraryRepository: LibraryRepository,
    private val registry: InstallTaskRegistry,
    private val execution: InstallExecution,
) {
    val runs = registry.runs

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Outcome text of a finished run. Dropped when no page is open to show it. */
    val messages = _messages.asSharedFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Whether a chain already owns this package, synchronously for callers. */
    fun isRunning(repoPackageName: String): Boolean = registry.isRunning(repoPackageName)

    /** Packages currently owned by a chain, for "update all" selection. */
    fun runningPackages(): Set<String> = registry.runningPackages()

    /** Starts the chain; a second start for the same module is ignored. */
    fun start(request: ModuleInstallRequest) {
        scope.launch {
            val id = request.taskId ?: registry.claim(request.repoPackageName, request.installedPackage) ?: return@launch
            if (!registry.owns(id)) return@launch
            val fixed = request.copy(taskId = id)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (fixed.target?.explicitSelection == true) {
                        libraryRepository.setExplicitPreference(fixed.repoPackageName, fixed.target.signature)
                    }
                    execute(fixed)
                } catch (cancellation: CancellationException) {
                    finish(id, context.getString(R.string.download_cancelled))
                    throw cancellation
                } catch (error: Throwable) {
                    finish(id, error.message ?: context.getString(R.string.error_install_failed))
                } finally {
                    if (registry.runs.value[fixed.repoPackageName]?.stage != InstallStage.PENDING_CONFIRMATION) registry.release(id)
                }
            }
            jobs[id] = job
            registry.asset(id, fixed.asset)
            registry.onCancel(id) {
                downloadManager.cancel(downloadManager.keyOf(fixed.asset))
                job.cancel()
            }
            job.invokeOnCompletion {
                jobs.remove(id, job)
                if (registry.runs.value[fixed.repoPackageName]?.stage != InstallStage.PENDING_CONFIRMATION) registry.release(id)
            }
            job.start()
        }
    }

    fun cancel(repoPackageName: String) = registry.cancel(repoPackageName)

    private suspend fun execute(request: ModuleInstallRequest) {
        val repo = requireNotNull(request.taskId)
        log(repo, "Waiting for a download slot")
        // The transfer, not the install, holds the slot: once the APK is on
        // disk another module may start downloading while this one installs.
        val task = downloadSlots.withSlot {
            val key = downloadManager.keyOf(request.asset)
            log(repo, "Downloading ${request.asset.name}")
            update(repo) { it.copy(stage = InstallStage.DOWNLOADING) }
            downloadManager.start(request.asset)
            val progressJob = scope.launch {
                downloadManager.tasks.collect { tasks ->
                    tasks[key]?.let { task ->
                        update(repo) { run ->
                            if (run.stage == InstallStage.INSTALLING) {
                                run
                            } else {
                                run.copy(
                                    stage = InstallStage.DOWNLOADING,
                                    downloaded = task.downloaded,
                                    total = if (task.total > 0L) task.total else run.total,
                                )
                            }
                        }
                    }
                }
            }
            try {
                // A cleared task (Settings > clear downloaded APKs) never reaches
                // a terminal status; waiting for one would park this run forever.
                downloadManager.tasks
                    .map { it[key] }
                    .first { it == null || it.status.isTerminal }
            } finally {
                progressJob.cancel()
            }
        }

        if (task == null) {
            finish(repo, context.getString(R.string.download_cancelled))
            return
        }

        when (task.status) {
            DownloadStatus.COMPLETED -> {
                val file = task.file
                if (file == null) {
                    finish(repo, null)
                    return
                }
                log(repo, "Download complete")
                update(repo) { it.copy(stage = InstallStage.INSTALLING) }
                install(request, file)
            }

            DownloadStatus.CANCELLED -> finish(repo, context.getString(R.string.download_cancelled))
            else -> finish(
                repo,
                task.error ?: context.getString(R.string.detail_download_failed),
            )
        }
    }

    private suspend fun install(request: ModuleInstallRequest, file: File) {
        val id = requireNotNull(request.taskId)
        val outcome = execution.install(
            request.repoPackageName, request.title, request.asset, file,
            request.installedPackage, request.target, id, request.versionCode, request.versionName,
        )
        when (outcome) {
            InstallOutcome.Completed -> finish(id, if (request.announceRemembered) context.getString(R.string.variant_remembered) else context.getString(R.string.detail_installed_apk, request.asset.name))
            InstallOutcome.DelegatedToSystem -> log(id, "Waiting for installation confirmation")
            is InstallOutcome.Failure -> {
                downloadManager.invalidate(downloadManager.keyOf(request.asset))
                finish(id, outcome.message)
            }
        }
    }

    private fun log(repoPackageName: String, line: String) {
        update(repoPackageName) { it.copy(logs = it.logs + line) }
    }

    private fun update(id: String, transform: (ModuleInstallRun) -> ModuleInstallRun) = registry.update(id, transform)

    private fun finish(id: String, message: String?) {
        registry.release(id)
        if (message != null) _messages.tryEmit(message)
    }
}
