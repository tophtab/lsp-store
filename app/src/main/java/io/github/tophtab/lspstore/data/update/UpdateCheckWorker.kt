package io.github.tophtab.lspstore.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import io.github.tophtab.lspstore.LspStoreApp
import io.github.tophtab.lspstore.domain.usecase.BackgroundUpdateOutcome

/**
 * Runs one [io.github.tophtab.lspstore.domain.usecase.BackgroundUpdateCheck] on
 * the schedule [UpdateScheduler] enqueued.
 *
 * WorkManager instantiates this by class name from its own manifest-declared
 * components, so no `AndroidManifest.xml` entry is needed.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val container = (applicationContext as LspStoreApp).container
        when (container.backgroundUpdateCheck.run()) {
            // The index or the installed scan could not be refreshed (offline,
            // mirror down): retry with backoff instead of reporting success.
            BackgroundUpdateOutcome.Retry ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

            BackgroundUpdateOutcome.Done -> Result.success()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
