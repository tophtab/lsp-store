package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.probe.KeyedMutex
import io.github.tophtab.lspstore.data.probe.ProbeFailureCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeRegistriesTest {
    @Test
    fun `key locks retain waiters and release the entry after the last caller`() = runTest {
        val locks = KeyedMutex<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("apk") { releaseFirst.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("apk") { fail("cancelled waiter entered") } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("apk") { secondEntered.complete(Unit); releaseSecond.await() } }
        cancelled.cancelAndJoin()
        assertEquals(1, locks.size)
        releaseFirst.complete(Unit)
        first.join()
        secondEntered.await()
        assertEquals(1, locks.size)
        var thirdEntered = false
        val third = async(start = CoroutineStart.UNDISPATCHED) { locks.withLock("apk") { thirdEntered = true } }
        assertFalse(thirdEntered)
        locks.withLock("other") { assertEquals(2, locks.size) }
        releaseSecond.complete(Unit)
        second.join()
        third.await()
        assertTrue(thirdEntered)
        assertEquals(0, locks.size)
    }

    @Test
    fun `key entries are recycled after failures and many distinct requests`() = runTest {
        val locks = KeyedMutex<Int>()
        repeat(1000) { key ->
            val result = runCatching { locks.withLock(key) { if (key % 2 == 0) error("failed") } }
            assertEquals(key % 2 == 0, result.isFailure)
            assertEquals(0, locks.size)
        }
    }

    @Test
    fun `negative cache caps retention at 500 and expires every key after ten minutes`() = runTest {
        val cache = ProbeFailureCache({ testScheduler.currentTime })
        repeat(600) { cache.record("apk-$it") }
        assertEquals(500, cache.size)
        assertFalse(cache.contains("apk-0"))
        assertTrue(cache.contains("apk-599"))
        advanceTimeBy(600_000)
        assertFalse(cache.contains("apk-599"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `recording another failure cleans expired entries without querying them`() = runTest {
        val cache = ProbeFailureCache({ testScheduler.currentTime })
        cache.record("old")
        advanceTimeBy(600_001)
        cache.record("new")
        assertEquals(1, cache.size)
        assertFalse(cache.contains("old"))
        assertTrue(cache.contains("new"))
    }
}
