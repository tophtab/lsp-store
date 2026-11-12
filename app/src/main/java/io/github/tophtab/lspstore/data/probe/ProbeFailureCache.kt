package io.github.tophtab.lspstore.data.probe

/** Accessed under RangeProbe's cache mutex; expiry and size both bound retention. */
internal class ProbeFailureCache(
    private val clock: () -> Long,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val capacity: Int = 500,
) {
    private val failures = LinkedHashMap<String, Long>()
    val size: Int get() { prune(clock()); return failures.size }

    fun contains(key: String): Boolean {
        prune(clock())
        return key in failures
    }

    fun record(key: String) {
        val now = clock()
        prune(now)
        failures.remove(key)
        failures[key] = now
        while (failures.size > capacity) failures.remove(failures.keys.first())
    }

    fun remove(key: String) { failures.remove(key) }

    private fun prune(now: Long) {
        failures.entries.removeAll { (_, at) -> now < at || now - at >= ttlMs }
    }
}
