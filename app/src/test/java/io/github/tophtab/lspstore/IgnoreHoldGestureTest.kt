package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.ui.components.IGNORE_HOLD_MILLIS
import io.github.tophtab.lspstore.ui.components.IgnoreHoldGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreHoldGestureTest {
    private class Actions(platformLongPressMillis: Long = 500L) {
        var opened = 0
        var ignored = 0
        var readyFeedback = 0
        var rejectFeedback = 0
        val gesture = IgnoreHoldGesture(
            platformLongPressMillis = platformLongPressMillis,
            onOpen = { opened++ },
            onIgnore = { ignored++ },
            onReady = { readyFeedback++ },
            onReject = { rejectFeedback++ },
        )

        fun release(atMillis: Long, insideCard: Boolean = true) {
            gesture.release(atMillis, insideCard)
            // Surface processes the up too: even if it calls onClick, a hold
            // or cancellation must never navigate to the detail page.
            gesture.open()
        }
    }

    @Test
    fun `a normal short press opens details without ignoring`() {
        val actions = Actions()
        actions.gesture.press(10_000L)

        actions.release(10_120L)

        assertEquals(1, actions.opened)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.readyFeedback)
        assertFalse(actions.gesture.isPressed)
    }

    @Test
    fun `a click without a pointer press still supports accessibility`() {
        val actions = Actions()

        actions.gesture.open()

        assertEquals(1, actions.opened)
        assertEquals(0, actions.ignored)
    }

    @Test
    fun `keyboard and accessibility clicks work again after the up finishes dispatching`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.release(700L)
        assertEquals(0, actions.opened)

        actions.gesture.finish()
        actions.gesture.open()

        assertEquals(1, actions.opened)
        assertEquals(0, actions.ignored)
        assertFalse(actions.gesture.captured)
    }

    @Test
    fun `capture starts at the platform long press threshold`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(499L)
        assertFalse(actions.gesture.captured)

        actions.gesture.advance(500L)

        assertTrue(actions.gesture.captured)
        assertFalse(actions.gesture.isReady)
        actions.release(501L)
        assertEquals(0, actions.opened)
        assertEquals(0, actions.ignored)
    }

    @Test
    fun `capture never waits longer than the ignore hold`() {
        val actions = Actions(platformLongPressMillis = 3_000L)
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS - 1L)
        assertFalse(actions.gesture.captured)

        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        assertTrue(actions.gesture.captured)
        assertTrue(actions.gesture.isReady)
        assertEquals(0, actions.ignored)
    }

    @Test
    fun `an early release after capture cancels and swallows the click`() {
        val actions = Actions()
        actions.gesture.press(0L)

        actions.release(1_199L)

        assertEquals(0, actions.opened)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.readyFeedback)
    }

    @Test
    fun `exactly 1200 milliseconds commits on release even before the timer runs`() {
        val actions = Actions()
        actions.gesture.press(42_000L)

        actions.release(42_000L + 1_200L)

        assertEquals(1, actions.ignored)
        assertEquals(1, actions.readyFeedback)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `reaching the deadline only arms ignore and vibrates once`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)
        actions.gesture.advance(3_000L)
        actions.gesture.advance(4_000L)

        assertTrue(actions.gesture.isReady)
        assertEquals(1, actions.readyFeedback)
        assertEquals(0, actions.ignored)

        actions.release(4_001L)
        actions.release(4_002L)
        assertEquals(1, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `moving outside after ready rejects once and moving back cannot commit`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        actions.gesture.updatePointer(1_710L, insideCard = false)
        actions.gesture.updatePointer(1_720L, insideCard = false)
        actions.gesture.updatePointer(1_730L, insideCard = true)
        actions.release(2_000L)

        assertFalse(actions.gesture.isReady)
        assertFalse(actions.gesture.isPressed)
        assertTrue(actions.gesture.captured)
        assertEquals(1, actions.rejectFeedback)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `an outside up event after ready cancels instead of committing`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        actions.release(1_701L, insideCard = false)

        assertEquals(1, actions.rejectFeedback)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `moving outside before ready permanently cancels without reject feedback`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(500L)

        actions.gesture.updatePointer(800L, insideCard = false)
        actions.gesture.updatePointer(1_800L, insideCard = true)
        actions.release(2_000L)

        assertTrue(actions.gesture.captured)
        assertEquals(0, actions.readyFeedback)
        assertEquals(0, actions.rejectFeedback)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `a second finger before capture cancels without taking over scrolling`() {
        val actions = Actions()
        actions.gesture.press(0L)

        actions.gesture.updatePointer(100L, insideCard = true, hasOtherPointers = true)
        actions.gesture.updatePointer(2_000L, insideCard = true)
        actions.release(2_001L)

        assertFalse(actions.gesture.captured)
        assertEquals(0, actions.readyFeedback)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `a second finger after ready cancels and keeps capture until all fingers lift`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        actions.gesture.updatePointer(1_800L, insideCard = true, hasOtherPointers = true)
        actions.gesture.updatePointer(1_900L, insideCard = true)
        actions.release(2_000L)

        assertTrue(actions.gesture.captured)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.rejectFeedback)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `a second finger on the deadline prevents ready and commit`() {
        val actions = Actions()
        actions.gesture.press(0L)

        actions.gesture.updatePointer(IGNORE_HOLD_MILLIS, insideCard = true, hasOtherPointers = true)
        actions.release(1_800L)

        assertEquals(0, actions.readyFeedback)
        assertEquals(0, actions.ignored)
    }

    @Test
    fun `scroll consumption before capture cancels while allowing the scroll to continue`() {
        val actions = Actions()
        actions.gesture.press(0L)

        actions.gesture.updatePointer(100L, insideCard = true, interrupted = true)
        actions.gesture.advance(2_000L)
        actions.release(2_001L)

        assertFalse(actions.gesture.captured)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `touch interruption after ready cancels without committing`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        actions.gesture.updatePointer(1_800L, insideCard = true, interrupted = true)
        actions.release(2_000L)

        assertTrue(actions.gesture.captured)
        assertEquals(0, actions.ignored)
        assertEquals(0, actions.rejectFeedback)
        assertEquals(0, actions.opened)
    }

    @Test
    fun `disposal cancels an armed hold`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)

        actions.gesture.cancel()
        actions.release(2_000L)

        assertFalse(actions.gesture.isPressed)
        assertEquals(0, actions.ignored)
    }

    @Test
    fun `the next press resets cancellation and can tap or ignore normally`() {
        val actions = Actions()
        actions.gesture.press(0L)
        actions.gesture.advance(IGNORE_HOLD_MILLIS)
        actions.gesture.cancel(movedOutside = true)
        actions.release(2_000L)

        actions.gesture.press(3_000L)
        actions.release(3_100L)
        assertEquals(1, actions.opened)

        actions.gesture.press(4_000L)
        actions.release(4_000L + IGNORE_HOLD_MILLIS)
        assertEquals(1, actions.ignored)
        assertEquals(1, actions.opened)
        assertEquals(2, actions.readyFeedback)
    }
}
