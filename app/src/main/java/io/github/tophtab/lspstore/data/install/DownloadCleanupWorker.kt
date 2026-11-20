package io.github.tophtab.lspstore.data.install

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.tophtab.lspstore.LspStoreApp

class DownloadCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LspStoreApp).container
        container.apkExportStore.deleteExpired()
        return Result.success()
    }
}
