package org.lspstore.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.lspstore.data.repository.SettingsRepositoryImpl
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val WORK_NAME = "lspstore_update_check"

    fun schedule(
        context: Context,
        intervalHours: Long = SettingsRepositoryImpl.DEFAULT_INTERVAL_HOURS,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            intervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
