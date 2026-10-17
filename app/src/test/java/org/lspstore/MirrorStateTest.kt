package org.lspstore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lspstore.data.remote.MIRRORS
import org.lspstore.data.remote.MirrorState

class MirrorStateTest {

    @Test
    fun blankPreferenceKeepsDefaultOrder() {
        val state = MirrorState()
        assertEquals(MIRRORS.map { it.host }, state.orderedHosts())
    }

    @Test
    fun preferredHostIsTriedFirst() {
        val state = MirrorState()
        val chosen = MIRRORS.last().host
        state.preferred = chosen
        val ordered = state.orderedHosts()
        assertEquals(chosen, ordered.first())
        assertEquals(MIRRORS.size, ordered.size)
        assertEquals(MIRRORS.size, ordered.toSet().size)
    }

    @Test
    fun unknownPreferenceFallsBackToDefaultOrder() {
        val state = MirrorState()
        state.preferred = "https://example.invalid/"
        assertEquals(MIRRORS.map { it.host }, state.orderedHosts())
    }
}
