package io.github.tophtab.lspstore

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.install.DownloadRetry
import io.github.tophtab.lspstore.data.install.HttpStatusException
import io.github.tophtab.lspstore.data.install.NetworkMonitor
import io.github.tophtab.lspstore.data.install.isTransientDownloadError

/**
 * The retry policy behind resumable downloads: transient failures retry a few
 * times, and a device that lost its network waits for it to come back instead of
 * burning through the attempts in a second.
 */
class DownloadRetryTest {

    private class FakeNetwork(
        private var online: Boolean,
        private val comesBack: Boolean,
    ) : NetworkMonitor {
        val waits = mutableListOf<Long>()

        override fun isAvailable(): Boolean = online

        override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
            waits += timeoutMs
            online = comesBack
            return comesBack
        }
    }

    @Test
    fun transportAndServerErrorsAreTransient() {
        assertTrue(isTransientDownloadError(UnknownHostException("no dns")))
        assertTrue(isTransientDownloadError(IOException("unexpected end of stream")))
        assertTrue(isTransientDownloadError(HttpStatusException(408)))
        assertTrue(isTransientDownloadError(HttpStatusException(429)))
        assertTrue(isTransientDownloadError(HttpStatusException(500)))
        assertTrue(isTransientDownloadError(HttpStatusException(503)))
    }

    @Test
    fun clientErrorsAndRejectedRangesAreNotTransient() {
        assertFalse(isTransientDownloadError(HttpStatusException(403)))
        assertFalse(isTransientDownloadError(HttpStatusException(404)))
        // A refused range is not a connection problem; fetchResumable handles it
        // by starting over once.
        assertFalse(isTransientDownloadError(HttpStatusException(416)))
        assertFalse(isTransientDownloadError(IllegalStateException("bug")))
    }

    @Test
    fun theAttemptLimitCountsTheInitialTry() {
        val retry = DownloadRetry(maxAttempts = 4)

        assertTrue(retry.canRetry(attempt = 1, error = IOException()))
        assertTrue(retry.canRetry(attempt = 3, error = IOException()))
        assertFalse(retry.canRetry(attempt = 4, error = IOException()))
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        val retry = DownloadRetry()

        assertEquals(1_000L, retry.backoffMs(nextAttempt = 2))
        assertEquals(2_000L, retry.backoffMs(nextAttempt = 3))
        assertEquals(4_000L, retry.backoffMs(nextAttempt = 4))
        // baseDelay shl 3 = 8s, which is the cap.
        assertEquals(8_000L, retry.backoffMs(nextAttempt = 5))
        assertEquals(8_000L, retry.backoffMs(nextAttempt = 40))
    }

    @Test
    fun onlineRetryBacksOff() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val retry = DownloadRetry(
            network = FakeNetwork(online = true, comesBack = true),
            sleep = { sleeps += it },
        )

        assertTrue(retry.awaitNextAttempt(nextAttempt = 3))
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun offlineRetryWaitsForTheNetworkInsteadOfBackingOff() = runBlocking {
        val network = FakeNetwork(online = false, comesBack = true)
        val sleeps = mutableListOf<Long>()
        val retry = DownloadRetry(
            offlineWaitMs = 20_000L,
            network = network,
            sleep = { sleeps += it },
        )

        assertTrue(retry.awaitNextAttempt(nextAttempt = 2))
        assertEquals(listOf(20_000L), network.waits)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun offlineRetryGivesUpWhenTheNetworkNeverReturns() = runBlocking {
        val network = FakeNetwork(online = false, comesBack = false)
        val retry = DownloadRetry(network = network, sleep = {})

        assertFalse(retry.awaitNextAttempt(nextAttempt = 2))
    }
}
