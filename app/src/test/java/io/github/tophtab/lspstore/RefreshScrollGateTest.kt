package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.ui.RefreshScrollGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished refresh scrolls the list back to the top, but only once: the
 * screens re-launch their scroll effect with the same counter value whenever
 * they come back from a module page, which is the bug this gate fixes.
 */
class RefreshScrollGateTest {

    @Test
    fun `the startup value is not a finished refresh`() {
        assertFalse(RefreshScrollGate().consume(0))
    }

    @Test
    fun `a finished refresh is handed out once`() {
        val gate = RefreshScrollGate()

        assertTrue(gate.consume(1))
        // Re-entering the screen observes the same tick again.
        assertFalse(gate.consume(1))
        assertFalse(gate.consume(1))
    }

    @Test
    fun `a later refresh scrolls again`() {
        val gate = RefreshScrollGate()

        assertTrue(gate.consume(1))
        assertFalse(gate.consume(1))
        assertTrue(gate.consume(2))
        assertFalse(gate.consume(2))
    }

    @Test
    fun `a refresh that skipped a tick still counts`() {
        // Pull-to-refresh can finish while a reselect-triggered load is being
        // replaced; the counter jumps and the newest value is the one to honour.
        val gate = RefreshScrollGate()

        assertTrue(gate.consume(2))
        assertFalse(gate.consume(2))
        assertTrue(gate.consume(5))
    }
}
