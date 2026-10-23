package io.github.tophtab.lspstore.data.install

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.parseTraits
import io.github.tophtab.lspstore.ui.variant.variantReasonText

/** Which half of a module install is currently running. */
enum class InstallStage { DOWNLOADING, INSTALLING }

/**
 * One in-flight install started from a module page. Progress is published so the
 * install button can fill up instead of a separate progress bar being shown.
 */
data class ModuleInstallRun(
    val repoPackageName: String,
    val asset: ReleaseAsset,
    val stage: InstallStage = InstallStage.DOWNLOADING,
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
)

/**
 * Download → gate → installer for one module page, run on an application scope.
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
    private val installer: ApkInstaller,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val systemInstallSerializer: SystemInstallSerializer,
    private val deviceProfile: () -> DeviceProfile,
) {
    private val _runs = MutableStateFlow<Map<String, ModuleInstallRun>>(emptyMap())

    /** Installs currently downloading or installing, keyed by repository package. */
    val runs = _runs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Outcome text of a finished run. Dropped when no page is open to show it. */
    val messages = _messages.asSharedFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Starts the chain; a second start for the same module is ignored. */
    fun start(request: ModuleInstallRequest) {
        if (jobs.containsKey(request.repoPackageName)) return
        // Publish the run before the job starts: every log/progress update below
        // transforms an existing entry instead of creating one.
        _runs.update { current ->
            current + (
                request.repoPackageName to ModuleInstallRun(
                    repoPackageName = request.repoPackageName,
                    asset = request.asset,
                    total = request.asset.size,
                )
            )
        }
        jobs[request.repoPackageName] = scope.launch {
            try {
                execute(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Never leave a run behind: a stuck entry would disable the
                // install button until the process restarts.
                finish(
                    request.repoPackageName,
                    error.message ?: context.getString(R.string.error_install_failed),
                )
            }
        }
    }

    /**
     * Aborts a running download. The install stage cannot be recalled once the
     * APK is with the device's installer, so it is left alone.
     */
    fun cancel(repoPackageName: String) {
        val run = _runs.value[repoPackageName] ?: return
        if (run.stage != InstallStage.DOWNLOADING) return
        // The awaiting coroutine observes CANCELLED and produces the message, so
        // there is exactly one place that finishes a run.
        downloadManager.cancel(downloadManager.keyOf(run.asset))
    }

    private suspend fun execute(request: ModuleInstallRequest) {
        val repo = request.repoPackageName
        val key = downloadManager.keyOf(request.asset)
        log(repo, "Downloading ${request.asset.name}")
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
        val task = try {
            downloadManager.tasks
                .mapNotNull { it[key] }
                .first {
                    it.status == DownloadStatus.COMPLETED ||
                        it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELLED
                }
        } finally {
            progressJob.cancel()
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
        val repo = request.repoPackageName
        val useRoot = settingsRepository.rootInstall.first()
        var installTarget = request.installedPackage ?: repo
        var linkAfterInstall: Pair<String, String>? = null

        // Local gate: zero network, runs before the APK reaches the installer.
        when (val gate = ApkGate.check(context, file, installTarget, deviceProfile())) {
            is GateResult.Reject -> {
                // One failed attempt, nothing persisted: the module keeps its
                // place in the update list and can be retried after a re-upload.
                val message = context.variantReasonText(gate.reason)
                log(repo, message)
                libraryRepository.recordInstallResult(repo, ok = false)
                finish(repo, message)
                return
            }

            is GateResult.Warn -> {
                log(repo, context.variantReasonText(gate.reason))
                // A repository listing may publish an APK whose package was
                // renamed (`dev.wtonec` -> `dev.wtonec.final`). The gate allows it
                // when the APK still declares itself as an Xposed module; install
                // it under its own package and remember the link so updates keep
                // matching afterwards.
                val archivePackage = gate.reason.args.firstOrNull()
                if (gate.reason.kind == Reason.Kind.PACKAGE_MISMATCH &&
                    request.installedPackage == null &&
                    !archivePackage.isNullOrBlank() &&
                    archivePackage != installTarget
                ) {
                    installTarget = archivePackage
                    linkAfterInstall = archivePackage to repo
                }
            }

            GateResult.Ok -> Unit
        }

        log(repo, "Installing ${request.asset.name}")
        val serializer = systemInstallSerializer
        val outcome = runCatching {
            // komi's orchestrator order: take the slot right before handing over.
            serializer.awaitFreeAndMarkPending(installTarget)
            installer.install(file, installTarget, useRoot)
        }.getOrElse { InstallOutcome.Failure(it.message ?: "install failed") }

        when (outcome) {
            InstallOutcome.Completed -> {
                serializer.markCompleted(installTarget)
                pendingRepository.clear(installTarget)
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
                libraryRepository.recordInstallResult(
                    repo,
                    ok = true,
                    signature = signatureOf(request.asset),
                )
                log(repo, "Installed")
                finish(
                    repo,
                    if (request.announceRemembered) {
                        context.getString(R.string.variant_remembered)
                    } else {
                        context.getString(R.string.detail_installed_apk, request.asset.name)
                    },
                )
            }

            InstallOutcome.DelegatedToSystem -> {
                // The installer app owns the APK from here on. Persist a pending
                // state until the package broadcast arrives instead of showing a
                // one-off message.
                pendingRepository.markPending(
                    PendingInstall(
                        packageName = installTarget,
                        repoPackageName = repo,
                        title = request.title,
                        versionCode = request.versionCode,
                        versionName = request.versionName.ifBlank { request.asset.name },
                        assetName = request.asset.name,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
                log(repo, "Handed off to the system installer")
                finish(repo, null)
            }

            is InstallOutcome.Failure -> {
                serializer.markCompleted(installTarget)
                libraryRepository.recordInstallResult(repo, ok = false)
                log(repo, outcome.message)
                finish(repo, outcome.message)
            }
        }
    }

    private fun signatureOf(asset: ReleaseAsset): VariantSignature =
        VariantSignature.of(parseTraits(asset.name))

    private fun log(repoPackageName: String, line: String) {
        update(repoPackageName) { it.copy(logs = it.logs + line) }
    }

    private fun update(
        repoPackageName: String,
        transform: (ModuleInstallRun) -> ModuleInstallRun,
    ) {
        _runs.update { current ->
            val run = current[repoPackageName] ?: return@update current
            current + (repoPackageName to transform(run))
        }
    }

    private fun finish(repoPackageName: String, message: String?) {
        jobs.remove(repoPackageName)
        _runs.update { it - repoPackageName }
        if (message != null) _messages.tryEmit(message)
    }
}
