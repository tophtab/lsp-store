package io.github.tophtab.lspstore.data.install

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Runs su/pm without letting a stuck permission prompt own the install queue forever. */
internal suspend fun installerCommandSucceeded(
    command: List<String>,
    logDirectory: File,
    timeoutMs: Long = 120_000L,
): Boolean = withContext(Dispatchers.IO) {
    val log = File.createTempFile("installer-", ".log", logDirectory)
    try {
        // A file drains stdout even when pm is verbose; reading a pipe before
        // waitFor would make its timeout ineffective, and waiting first could
        // deadlock on a full pipe.
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            val finished = runInterruptible {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!finished) return@withContext false
            process.exitValue() == 0 || log.useLines { lines ->
                lines.any { it.trim().equals("Success", ignoreCase = true) }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    } finally {
        log.delete()
    }
}
