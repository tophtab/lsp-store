package io.github.tophtab.lspstore.data.install

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Cleans the download folder daily, even when the store UI is closed. */
object DownloadCleanupScheduler {
    private const val WORK_NAME = "lspstore_download_cleanup"

    fun sync(context: Context, weeks: Int) {
        val workManager = WorkManager.getInstance(context)
        if (DownloadRetention.normalize(weeks) == DownloadRetention.FOREVER) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DownloadCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
