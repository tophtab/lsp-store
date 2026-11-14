package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.ui.components.IGNORE_HOLD_MILLIS
import io.github.tophtab.lspstore.ui.components.IgnoreHoldCoordinator
import io.github.tophtab.lspstore.ui.components.IgnoreHoldGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreHoldCoordinatorTest {
    private class Card(platformLongPressMillis: Long = 500L) {
        var ignored = 0
        var opened = 0
        var readyFeedback = 0
        val gesture = IgnoreHoldGesture(
            platformLongPressMillis = platformLongPressMillis,
            onOpen = { opened++ },
            onIgnore = { ignored++ },
            onReady = { readyFeedback++ },
        )

        fun press(coordinator: IgnoreHoldCoordinator, pointerId: Long, atMillis: Long = 0L) {
            gesture.press(atMillis)
            if (!coordinator.claim(gesture, pointerId)) gesture.cancel()
        }

        fun release(atMillis: Long = 2_000L) {
            gesture.release(atMillis, insideCard = true)
            gesture.open()
        }
    }

    @Test
    fun `a second finger on another hit path cancels the ready card without a local event`() {
        val coordinator = IgnoreHoldCoordinator()
        val first = Card()
        val second = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        first.press(coordinator, 1L)
        first.gesture.advance(IGNORE_HOLD_MILLIS)

        // Only the app ancestor sees this finger on a different card. Do not
        // feed it to the first card's gesture: Compose filters its hit path.
        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        second.press(coordinator, 2L, 1_800L)
        first.release()
        second.release(4_000L)

        assertEquals(0, first.ignored)
        assertEquals(0, second.ignored)
        assertEquals(0, first.opened)
        assertEquals(0, second.opened)
        assertFalse(first.gesture.isReady)
        assertTrue(coordinator.captured)
    }

    @Test
    fun `a finger on the header before capture cancels without taking over scrolling`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)

        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        coordinator.observePointers(setOf(1L), setOf(1L, 2L))
        card.gesture.advance(IGNORE_HOLD_MILLIS)
        card.release()

        assertFalse(coordinator.captured)
        assertEquals(0, card.readyFeedback)
        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
    }

    @Test
    fun `a finger on navigation after capture cancels before the ready deadline`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card(platformLongPressMillis = 250L)
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)
        card.gesture.advance(250L)

        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        card.gesture.advance(IGNORE_HOLD_MILLIS)
        card.release()

        assertTrue(coordinator.captured)
        assertEquals(0, card.readyFeedback)
        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
    }

    @Test
    fun `lifting the other finger does not rearm the original card`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)
        card.gesture.advance(IGNORE_HOLD_MILLIS)

        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        coordinator.observePointers(setOf(1L), setOf(1L, 2L))
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.gesture.advance(4_000L)
        card.release(4_001L)

        assertTrue(coordinator.captured)
        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
        assertEquals(1, card.readyFeedback)
    }

    @Test
    fun `lifting the primary finger first keeps draining after its detector finishes`() {
        val coordinator = IgnoreHoldCoordinator()
        val first = Card()
        val next = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        first.press(coordinator, 1L)
        first.gesture.advance(IGNORE_HOLD_MILLIS)
        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))

        coordinator.observePointers(setOf(2L), setOf(1L, 2L))
        first.release()
        coordinator.detach(first.gesture)
        first.gesture.finish()
        coordinator.observePointers(setOf(2L), setOf(2L))

        assertFalse(first.gesture.captured)
        assertTrue(coordinator.captured)
        assertTrue(coordinator.consumesAtHost(2L))
        next.press(coordinator, 2L, 3_000L)
        next.release(5_000L)
        assertEquals(0, first.ignored)
        assertEquals(0, next.ignored)
        assertEquals(0, next.opened)

        // All-up stays captured through Main; only the ancestor's Final pass
        // releases ownership, so the last up cannot click another control.
        coordinator.observePointers(emptySet(), setOf(2L))
        assertTrue(coordinator.captured)
        assertTrue(coordinator.consumesAtHost(1L))
        assertTrue(coordinator.consumesAtHost(2L))
        coordinator.finish()
        assertFalse(coordinator.captured)
    }

    @Test
    fun `an additional finger cannot claim a captured stream after the card disappears`() {
        val coordinator = IgnoreHoldCoordinator()
        val removed = Card()
        val replacement = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        removed.press(coordinator, 1L)
        removed.gesture.advance(IGNORE_HOLD_MILLIS)

        coordinator.detach(removed.gesture)
        removed.gesture.finish()
        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        replacement.press(coordinator, 2L, 2_000L)
        replacement.release(4_000L)

        assertTrue(coordinator.captured)
        assertEquals(0, removed.ignored)
        assertEquals(0, replacement.ignored)
        assertEquals(0, replacement.opened)
    }

    @Test
    fun `disposal before capture cancels without blocking normal movement`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)

        coordinator.detach(card.gesture)
        card.release()

        assertFalse(coordinator.captured)
        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
    }

    @Test
    fun `interruption cancels the owner and a fresh touch can commit normally`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)
        card.gesture.advance(IGNORE_HOLD_MILLIS)

        coordinator.finish()
        card.release()
        card.gesture.finish()
        assertFalse(coordinator.captured)
        assertEquals(0, card.ignored)

        coordinator.observePointers(setOf(3L), setOf(3L))
        card.press(coordinator, 3L, 3_000L)
        card.release(3_000L + IGNORE_HOLD_MILLIS)
        assertEquals(1, card.ignored)
        assertEquals(0, card.opened)
    }

    @Test
    fun `a valid hold remains owned through its release and commits only once`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)
        card.gesture.advance(IGNORE_HOLD_MILLIS)

        coordinator.observePointers(emptySet(), setOf(1L))
        assertTrue(coordinator.owns(card.gesture))
        assertTrue(coordinator.captured)
        assertFalse(coordinator.consumesAtHost(1L))
        card.release()
        card.release(2_001L)

        assertEquals(1, card.ignored)
        assertEquals(0, card.opened)
        coordinator.finish()
        card.gesture.finish()
        card.gesture.open()
        assertEquals(1, card.opened)
        assertFalse(coordinator.captured)
    }

    @Test
    fun `a consumed synthetic cancel on the owner pointer cancels instead of committing`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        card.press(coordinator, 1L)
        card.gesture.advance(IGNORE_HOLD_MILLIS)

        // Compose sends a consumed synthetic up to the card on ACTION_CANCEL.
        // The host leaves the owner's change alone, so the card can treat that
        // consumption as interruption before processing the apparent release.
        assertFalse(coordinator.consumesAtHost(1L))
        card.gesture.updatePointer(1_800L, insideCard = true, interrupted = true)
        card.release(1_800L)

        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
        assertTrue(coordinator.captured)
        coordinator.detach(card.gesture)
        card.gesture.finish()
        assertTrue(coordinator.consumesAtHost(1L))
        coordinator.finish()
        assertFalse(coordinator.consumesAtHost(1L))
    }

    @Test
    fun `a card cannot start a hold when a different finger is already down`() {
        val coordinator = IgnoreHoldCoordinator()
        val card = Card()
        coordinator.observePointers(setOf(1L), setOf(1L))
        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))

        card.press(coordinator, 2L)
        card.release()

        assertFalse(coordinator.captured)
        assertEquals(0, card.ignored)
        assertEquals(0, card.opened)
    }

    @Test
    fun `ordinary touches outside module cards never capture the app`() {
        val coordinator = IgnoreHoldCoordinator()

        coordinator.observePointers(setOf(1L), setOf(1L))
        coordinator.observePointers(setOf(1L, 2L), setOf(1L, 2L))
        coordinator.observePointers(emptySet(), setOf(1L, 2L))

        assertFalse(coordinator.captured)
        coordinator.finish()
        val card = Card()
        coordinator.observePointers(setOf(3L), setOf(3L))
        card.press(coordinator, 3L, 3_000L)
        card.release(3_120L)
        assertEquals(1, card.opened)
        assertEquals(0, card.ignored)
    }
}
