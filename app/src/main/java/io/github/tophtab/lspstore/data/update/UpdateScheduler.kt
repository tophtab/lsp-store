package io.github.tophtab.lspstore.data.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the single periodic background check.
 *
 * WorkManager is the only scheduler: it persists the request, re-enqueues it
 * after a reboot through its own boot receiver, and applies the network and
 * battery constraints. Nothing here needs a custom `BootReceiver` or a
 * `RECEIVE_BOOT_COMPLETED` permission.
 */
object UpdateScheduler {

    const val WORK_NAME = "lspstore_update_check"
    const val DEFAULT_INTERVAL_HOURS = 24

    /**
     * Intervals the settings picker offers, in hours (1, 2, 3, 5 and 7 days);
     * the stored value is one of these.
     */
    val INTERVAL_OPTIONS: List<Int> = listOf(24, 48, 72, 120, 168)

    /**
     * Enables, retunes or cancels the periodic check. [reset] false keeps the
     * existing schedule (startup), true restarts the cycle (settings change).
     */
    fun sync(
        context: Context,
        enabled: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        reset: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            intervalHours.coerceAtLeast(1).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (reset) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            },
            request,
        )
    }
}
