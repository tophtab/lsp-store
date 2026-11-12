package io.github.tophtab.lspstore.data.install

import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Shared execution and bookkeeping for detail, single, batch and silent updates. */
class InstallExecution(
    private val installer: ApkInstallDriver,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val serializer: SystemInstallSerializer,
    private val registry: InstallTaskRegistry,
    private val confirmation: InstallConfirmation,
) {
    suspend fun install(
        repo: String, title: String, asset: ReleaseAsset, file: File,
        installedPackage: String?, target: ResolvedInstallTarget?, taskId: String,
        versionCode: Long = 0L, versionName: String = "", allowUi: Boolean = true,
    ): InstallOutcome {
        val identity = readInstallIdentity(file)
        val actual = identity?.packageName ?: installedPackage ?: repo
        if (!registry.associate(taskId, actual)) {
            registry.release(taskId)
            return InstallOutcome.Failure("An installation for $actual is already in progress")
        }
        val resolved = target?.copy(
            apkPackageName = identity?.packageName,
            apkVersionCode = identity?.versionCode,
            apkVersionName = identity?.versionName,
            signature = target.signature.copy(packageName = identity?.packageName),
        )
        registry.stage(taskId, InstallStage.WAITING_TO_INSTALL)
        var pending: PendingInstall? = null
        var delegated = false
        try {
            val outcome = installWithSlot(
                installer, serializer, file, actual, settings.rootInstall.first(), allowUi,
                taskId = taskId,
                beforeInstall = {
                    if (!registry.owns(taskId)) throw kotlinx.coroutines.CancellationException("Installation task was released")
                    // Persist before invoking the installer: package broadcasts and process
                    // death can occur before its call returns.
                    registry.stage(taskId, InstallStage.INSTALLING)
                    val entry = PendingInstall(
                        packageName = actual, repoPackageName = repo, title = title,
                        versionCode = identity?.versionCode ?: versionCode,
                        versionName = identity?.versionName ?: versionName,
                        assetName = asset.name, createdAt = System.currentTimeMillis(),
                        downloadKey = assetCacheKey(asset), taskId = taskId, target = resolved,
                        previousLastUpdateTime = confirmation.installed(actual)?.lastUpdateTime ?: 0L,
                    )
                    pending = entry
                    pendingRepository.markPending(entry)
                },
            )
            when (outcome) {
                InstallOutcome.Completed -> {
                    val entry = pending
                    var confirmed = entry != null && confirmation.confirm(entry)
                    repeat(3) {
                        if (!confirmed && entry != null) {
                            kotlinx.coroutines.delay(250L)
                            confirmed = confirmation.confirm(entry)
                        }
                    }
                    if (!confirmed && registry.owns(taskId)) {
                        delegated = true
                        registry.stage(taskId, InstallStage.PENDING_CONFIRMATION)
                        return InstallOutcome.DelegatedToSystem
                    }
                }
                InstallOutcome.DelegatedToSystem -> {
                    delegated = true
                    registry.stage(taskId, InstallStage.PENDING_CONFIRMATION)
                }
                is InstallOutcome.Failure -> {
                    installer.invalidateDownload(asset)
                    library.recordInstallResult(repo, false)
                }
            }
            return outcome
        } finally {
            if (!delegated) withContext(NonCancellable) {
                pending?.let { confirmation.cancel(it) }
                registry.release(taskId)
                serializer.markCompleted(actual, taskId)
            }
        }
    }
}
