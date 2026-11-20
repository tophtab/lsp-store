package io.github.tophtab.lspstore.data.install

/** Retention of exported APKs; the private installer copy is managed separately. */
object DownloadRetention {
    const val FOREVER = -1
    const val DEFAULT_WEEKS = FOREVER
    val WEEK_OPTIONS: List<Int> = listOf(0, 1, 2, 4, FOREVER)

    fun normalize(weeks: Int): Int = weeks.takeIf { it in WEEK_OPTIONS } ?: DEFAULT_WEEKS

    /** Providers with no timestamp keep their files unless retention is zero. */
    internal fun isExpired(storedAtMillis: Long, weeks: Int, nowMillis: Long): Boolean =
        when (val retention = normalize(weeks)) {
            FOREVER -> false
            0 -> true
            else -> storedAtMillis > 0L && nowMillis >= storedAtMillis &&
                nowMillis - storedAtMillis >= retention * WEEK_MILLIS
        }

    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1_000
}
