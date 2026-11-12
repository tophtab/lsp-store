package io.github.tophtab.lspstore

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.tophtab.lspstore.data.install.installerCommandSucceeded

class InstallerCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commandSuccessAndFailureAreReported() = runBlocking {
        assertTrue(installerCommandSucceeded(listOf("sh", "-c", "exit 0"), temporaryFolder.root))
        assertFalse(installerCommandSucceeded(listOf("sh", "-c", "exit 1"), temporaryFolder.root))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun verboseOutputCannotFillAPipeAndDeadlockTheQueue() = runBlocking {
        assertTrue(
            installerCommandSucceeded(
                listOf("sh", "-c", "head -c 1048576 /dev/zero"), temporaryFolder.root,
                timeoutMs = 5_000L,
            ),
        )
    }

    @Test
    fun aHungCommandTimesOutAndTheNextAttemptCanRun() = runBlocking {
        withTimeout(5_000L) {
            assertFalse(
                installerCommandSucceeded(
                    listOf("sh", "-c", "exec sleep 30"), temporaryFolder.root, timeoutMs = 100L,
                ),
            )
            assertTrue(installerCommandSucceeded(listOf("sh", "-c", "exit 0"), temporaryFolder.root))
        }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationStopsTheCommandAndCleansItsLog() = runBlocking {
        val started = temporaryFolder.newFile("started")
        val command = async {
            installerCommandSucceeded(
                listOf("sh", "-c", "echo started > \"${started.absolutePath}\"; exec sleep 30"),
                temporaryFolder.root,
            )
        }
        withTimeout(5_000L) {
            while (Files.size(started.toPath()) == 0L) delay(10L)
            command.cancel()
            command.join()
        }
        assertTrue(command.isCancelled)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().all { it == started })
    }
}
