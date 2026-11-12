package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import kotlin.coroutines.cancellation.CancellationException

internal data class PendingInstallCheck(
    val pending: PendingInstall,
    val settlement: PendingSettlement,
    val readable: Boolean,
)

/** An unreadable package remains pending and must not be abandoned on resume. */
internal suspend fun checkPendingInstalls(
    pending: Collection<PendingInstall>,
    readInstalled: suspend (String) -> InstalledModule?,
    now: Long,
    abandonAfterMs: Long,
    allowAbandonment: Boolean,
): List<PendingInstallCheck> {
    val observations = pending.map { it.packageName }.distinct().associateWith { name ->
        try {
            Result.success(readInstalled(name))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    return pending.map { entry ->
        val observation = observations.getValue(entry.packageName)
        PendingInstallCheck(
            entry,
            if (observation.isFailure) PendingSettlement.PENDING else settlePendingInstall(
                entry, observation.getOrNull(), now, abandonAfterMs, allowAbandonment,
            ),
            readable = observation.isSuccess,
        )
    }
}
