package io.github.tophtab.lspstore.data.install

/** Progress is limited to 1 Hz; membership/status changes are immediate. */
internal class DownloadNotificationThrottle(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var phases: Map<String, DownloadStatus>? = null
    private var publishedAt = 0L

    fun shouldPublish(tasks: List<DownloadTask>): Boolean {
        val currentPhases = tasks.associate { it.key to it.status }
        val now = clock()
        if (currentPhases == phases && now - publishedAt < 1000L) return false
        phases = currentPhases
        publishedAt = now
        return true
    }
}
