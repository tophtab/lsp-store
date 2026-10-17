package org.lspstore.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.lspstore.LspStoreApp
import org.lspstore.data.install.InstallOutcome
import org.lspstore.domain.usecase.UpdateChecker

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LspStoreApp).container
        return try {
            val channel = container.settingsRepository.updateChannel.first()
            val index = container.moduleRepository.getIndex(force = true)
            val installed = container.installedRepository.getInstalledModules()
            val skipped = container.libraryRepository.skippedUpdates.first()
            val hidden = container.libraryRepository.hidden.first()
            val updates = UpdateChecker.findUpdates(installed, index, channel, skipped, hidden)

            if (updates.isEmpty()) {
                return Result.success()
            }

            val autoUpdate = container.settingsRepository.autoUpdate.first()
            val useRoot = container.settingsRepository.rootInstall.first()

            if (autoUpdate && useRoot) {
                var failed = 0
                var succeeded = 0
                updates.forEach { update ->
                    val outcome = runCatching {
                        val detail = container.moduleRepository.getDetail(update.module.packageName)
                        val asset = detail.latestAssetFor(channel)
                            ?: return@runCatching InstallOutcome.Failure("No APK asset")
                        val file = container.installer.download(asset)
                        container.installer.install(file, update.module.packageName, useRoot = true)
                    }.getOrElse { InstallOutcome.Failure(it.message ?: "failed") }

                    if (outcome is InstallOutcome.SilentSuccess) succeeded++ else failed++
                }
                UpdateNotifier.notifyAutoUpdateResult(applicationContext, succeeded, failed)
            } else {
                UpdateNotifier.notifyUpdates(applicationContext, updates)
            }

            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
