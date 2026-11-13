package io.github.tophtab.lspstore.ui.screens.installed

import android.content.Context
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.usecase.BatchCompletion
import io.github.tophtab.lspstore.domain.usecase.BatchFailure
import io.github.tophtab.lspstore.domain.usecase.BatchFailureKind
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.domain.usecase.BatchInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.batchCompletion
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.ui.variant.variantReasonText

internal data class InstalledUpdateText(
    val repositoryStale: () -> String,
    val checkFailed: (Throwable) -> String,
    val variantReason: (Reason) -> String,
    val completion: (BatchInstallUseCase.Result, Collection<BatchInstallState>) -> String,
    val cancelled: () -> String,
)

/** Resolve messages at the time of the outcome, including after a locale change. */
internal fun installedUpdateText(context: Context) = InstalledUpdateText(
    repositoryStale = { context.getString(R.string.error_repository_stale) },
    checkFailed = { it.toUserMessage(context, R.string.installed_check_failed) },
    variantReason = { context.variantReasonText(it) },
    cancelled = { context.getString(R.string.installed_batch_cancelled) },
    completion = { result, items ->
        when (batchCompletion(result, items)) {
            BatchCompletion.FAILURES -> failureMessage(
                context, result.failures, items.count { it.phase == BatchInstallPhase.FAILED },
            )
            BatchCompletion.SKIPPED -> items.count { it.phase == BatchInstallPhase.SKIPPED }.let {
                context.resources.getQuantityString(R.plurals.installed_finished_skipped, it, it)
            }
            BatchCompletion.CANCELLED -> items.count { it.phase == BatchInstallPhase.CANCELLED }.let {
                context.resources.getQuantityString(R.plurals.installed_finished_cancelled, it, it)
            }
            BatchCompletion.PROCESSED -> context.getString(R.string.installed_batch_processed)
            BatchCompletion.COMPLETED -> context.getString(R.string.installed_all_updated)
        }
    },
)

/** Name at most three failures; the header always reports the full count. */
private fun failureMessage(context: Context, failures: List<BatchFailure>, failedCount: Int): String {
    val header = context.resources.getQuantityString(R.plurals.installed_finished_failures, failedCount, failedCount)
    return buildString {
        append(header)
        failures.take(3).forEach { failure ->
            val reason = failure.detail ?: context.getString(
                when (failure.kind) {
                    BatchFailureKind.DOWNLOAD -> R.string.installed_batch_failure_download
                    BatchFailureKind.INSTALL -> R.string.error_install_failed
                },
            )
            append('\n')
            append(failure.title)
            append(": ")
            append(reason)
        }
    }
}
