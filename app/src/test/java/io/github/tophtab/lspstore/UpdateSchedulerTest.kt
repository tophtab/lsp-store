package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tophtab.lspstore.data.update.UpdateScheduler

/**
 * Custom background-check intervals are whole hours inside the same 1..168
 * range the settings repository clamps to, and the presets stay inside it.
 */
class UpdateSchedulerTest {

    @Test
    fun customIntervalsAcceptWholeHoursInRange() {
        assertEquals(1, UpdateScheduler.parseIntervalHours("1"))
        assertEquals(36, UpdateScheduler.parseIntervalHours("36"))
        assertEquals(168, UpdateScheduler.parseIntervalHours("168"))
        assertEquals(7, UpdateScheduler.parseIntervalHours("007"))
        assertEquals(12, UpdateScheduler.parseIntervalHours(" 12 "))
    }

    @Test
    fun customIntervalsRejectOutOfRangeAndMalformedInput() {
        listOf("", "   ", "0", "169", "-5", "1.5", "12h", "999").forEach {
            assertNull(it, UpdateScheduler.parseIntervalHours(it))
        }
    }

    @Test
    fun presetsAreDistinctAndInsideTheCustomRange() {
        val presets = UpdateScheduler.INTERVAL_OPTIONS
        assertEquals(presets.sorted(), presets)
        assertEquals(presets.distinct(), presets)
        presets.forEach {
            assertEquals(
                it,
                UpdateScheduler.parseIntervalHours(it.toString()),
            )
        }
    }
}
