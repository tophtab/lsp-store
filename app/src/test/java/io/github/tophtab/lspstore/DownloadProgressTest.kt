package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.DownloadNotificationThrottle
import io.github.tophtab.lspstore.data.install.DownloadStatus
import io.github.tophtab.lspstore.data.install.DownloadTask
import io.github.tophtab.lspstore.data.install.DownloadTaskStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressTest {
    @get:Rule val directory = TemporaryFolder()
    private fun task(key: String = "apk", bytes: Long = 0) = DownloadTask(key, "app.apk", bytes, 1000)

    @Test
    fun `frequent reads publish at most every 100 ms`() = runTest {
        val store = DownloadTaskStore { testScheduler.currentTime }
        val published = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.tasks.collect { it["apk"]?.let { task -> published += testScheduler.currentTime to task.downloaded } }
        }
        val attempt = store.begin(task())
        repeat(25) { index ->
            advanceTimeBy(10)
            store.progress(attempt, (index + 1) * 10L, 1000)
        }
        assertEquals(listOf(0L to 0L, 100L to 100L, 200L to 200L), published)
    }

    @Test
    fun `pause resume failure and cancellation publish exact latest bytes immediately`() = runTest {
        val store = DownloadTaskStore { testScheduler.currentTime }
        val first = store.begin(task())
        advanceTimeBy(1)
        store.progress(first, 321, 1234)
        assertEquals(0L, store.tasks.value.getValue("apk").downloaded)
        store.pause("apk")
        assertEquals(DownloadStatus.PAUSED, store.tasks.value.getValue("apk").status)
        assertEquals(321L, store.tasks.value.getValue("apk").downloaded)
        val resumed = store.begin(task(bytes = 321))
        assertEquals(DownloadStatus.RUNNING, store.tasks.value.getValue("apk").status)
        store.progress(resumed, 432, 1234)
        store.fail(resumed, "offline")
        assertEquals(DownloadStatus.FAILED, store.tasks.value.getValue("apk").status)
        assertEquals(432L, store.tasks.value.getValue("apk").downloaded)
        val retried = store.begin(task(bytes = 432))
        store.progress(retried, 543, 1234)
        store.cancel("apk")
        assertEquals(DownloadStatus.CANCELLED, store.tasks.value.getValue("apk").status)
        assertEquals(543L, store.tasks.value.getValue("apk").downloaded)
    }

    @Test
    fun `completion publishes the actual file size without waiting for the interval`() = runTest {
        val store = DownloadTaskStore { testScheduler.currentTime }
        val attempt = store.begin(task())
        val file = directory.newFile().apply { writeBytes(ByteArray(257)) }
        store.progress(attempt, 256, 1000)
        assertTrue(store.complete(attempt, file))
        val finished = store.tasks.value.getValue("apk")
        assertEquals(DownloadStatus.COMPLETED, finished.status)
        assertEquals(257L, finished.downloaded)
        assertEquals(257L, finished.total)
    }

    @Test
    fun `callbacks from a cancelled or cleared attempt cannot overwrite a new one`() = runTest {
        val store = DownloadTaskStore { testScheduler.currentTime }
        val first = store.begin(task())
        store.cancel("apk")
        val second = store.begin(task(bytes = 123))
        advanceTimeBy(100)
        store.progress(first, 999, 999)
        store.fail(first, "old failure")
        assertFalse(store.complete(first, directory.newFile()))
        assertEquals(task(bytes = 123), store.tasks.value.getValue("apk"))
        store.clear()
        store.begin(task(bytes = 456))
        store.progress(second, 789, 999)
        store.fail(second, "another old failure")
        assertEquals(task(bytes = 456), store.tasks.value.getValue("apk"))
    }

    @Test
    fun `parallel tasks retain independent exact byte totals`() = runTest {
        val store = DownloadTaskStore { testScheduler.currentTime }
        val a = store.begin(task("a"))
        val b = store.begin(task("b"))
        store.progress(a, 101, 1000)
        store.progress(b, 202, 2000)
        store.pause("a")
        store.fail(b, "offline")
        assertEquals(303L, store.tasks.value.values.sumOf { it.downloaded })
        assertEquals(3000L, store.tasks.value.values.sumOf { it.total })
        store.clearFinished()
        assertEquals(setOf("a"), store.tasks.value.keys)
        store.progress(b, 999, 2000)
        assertEquals(setOf("a"), store.tasks.value.keys)
    }

    @Test
    fun `notification progress is limited to one update per second`() = runTest {
        val throttle = DownloadNotificationThrottle { testScheduler.currentTime }
        assertTrue(throttle.shouldPublish(listOf(task())))
        repeat(9) { index ->
            advanceTimeBy(100)
            assertFalse(throttle.shouldPublish(listOf(task(bytes = (index + 1) * 10L))))
        }
        advanceTimeBy(100)
        assertTrue(throttle.shouldPublish(listOf(task(bytes = 100))))
        assertFalse(throttle.shouldPublish(listOf(task(bytes = 101))))
    }

    @Test
    fun `notification starts pauses resumes and terminal membership changes are immediate`() = runTest {
        val throttle = DownloadNotificationThrottle { testScheduler.currentTime }
        val a = task("a")
        val b = task("b")
        assertTrue(throttle.shouldPublish(listOf(a)))
        assertTrue(throttle.shouldPublish(listOf(a, b)))
        assertFalse(throttle.shouldPublish(listOf(b, a)))
        assertTrue(throttle.shouldPublish(listOf(a.copy(status = DownloadStatus.PAUSED), b)))
        assertTrue(throttle.shouldPublish(listOf(a, b)))
        assertTrue(throttle.shouldPublish(listOf(a)))
        assertTrue(throttle.shouldPublish(emptyList()))
    }
}
