package io.github.tophtab.lspstore.data.install

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DownloadAttempt(val key: String, val generation: Long)

/** Keep exact bytes privately; publish progress at most once per 100 ms per task. */
internal class DownloadTaskStore(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val attempt: DownloadAttempt, var latest: DownloadTask, var publishedAt: Long)
    private val entries = mutableMapOf<String, Entry>()
    private val state = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks = state.asStateFlow()
    private var generation = 0L

    @Synchronized
    fun begin(task: DownloadTask): DownloadAttempt {
        val attempt = DownloadAttempt(task.key, ++generation)
        val entry = Entry(attempt, task, clock())
        entries[task.key] = entry
        publish(entry)
        return attempt
    }

    @Synchronized
    fun progress(attempt: DownloadAttempt, downloaded: Long, total: Long) {
        val entry = running(attempt) ?: return
        entry.latest = entry.latest.copy(downloaded = downloaded, total = total)
        if (clock() - entry.publishedAt >= 100L) publish(entry)
    }

    @Synchronized
    fun pause(key: String): DownloadTask? {
        val entry = entries[key]?.takeIf { it.latest.status == DownloadStatus.RUNNING } ?: return null
        entry.latest = entry.latest.copy(status = DownloadStatus.PAUSED)
        publish(entry)
        return entry.latest
    }

    @Synchronized
    fun cancel(key: String): DownloadTask? {
        val entry = entries[key]?.takeIf { it.latest.status != DownloadStatus.COMPLETED } ?: return null
        entry.latest = entry.latest.copy(status = DownloadStatus.CANCELLED)
        publish(entry)
        return entry.latest
    }

    @Synchronized
    fun complete(attempt: DownloadAttempt, file: File): Boolean {
        val entry = running(attempt) ?: return false
        entry.latest = entry.latest.copy(
            status = DownloadStatus.COMPLETED, file = file, downloaded = file.length(), total = file.length(),
        )
        publish(entry)
        return true
    }

    @Synchronized
    fun fail(attempt: DownloadAttempt, error: String) {
        val entry = running(attempt) ?: return
        entry.latest = entry.latest.copy(status = DownloadStatus.FAILED, error = error)
        publish(entry)
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
        state.value = state.value - key
    }

    @Synchronized
    fun clearFinished() {
        val finished = entries.filterValues { it.latest.status.isTerminal }.keys
        entries.keys.removeAll(finished)
        state.value = state.value - finished
    }

    @Synchronized
    fun clear() {
        entries.clear()
        state.value = emptyMap()
    }

    private fun running(attempt: DownloadAttempt): Entry? = entries[attempt.key]?.takeIf {
        it.attempt == attempt && it.latest.status == DownloadStatus.RUNNING
    }

    private fun publish(entry: Entry) {
        entry.publishedAt = clock()
        state.value = state.value + (entry.attempt.key to entry.latest)
    }
}
