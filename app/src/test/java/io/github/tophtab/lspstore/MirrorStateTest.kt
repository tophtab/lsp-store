package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.remote.MIRRORS
import io.github.tophtab.lspstore.data.remote.MirrorState
import io.github.tophtab.lspstore.data.remote.RepoEndpoint

class MirrorStateTest {

    private val backup = "https://backup.modules.lsposed.org/"
    private val official = "https://modules.lsposed.org/"

    @Test
    fun everyEndpointHasAtLeastOneHost() {
        RepoEndpoint.entries.forEach { endpoint ->
            assertTrue(
                "no host serves $endpoint",
                MIRRORS.any { endpoint in it.endpoints },
            )
        }
    }

    @Test
    fun indexIsServedOnlyByBackup() {
        assertEquals(listOf(backup), MirrorState().orderedHosts(RepoEndpoint.INDEX))
    }

    @Test
    fun listIsServedOnlyByOfficial() {
        assertEquals(listOf(official), MirrorState().orderedHosts(RepoEndpoint.LIST))
    }

    @Test
    fun detailFallsBackToBackup() {
        assertEquals(
            listOf(official, backup),
            MirrorState().orderedHosts(RepoEndpoint.DETAIL),
        )
    }

    @Test
    fun preferredHostGoesFirstWhereItServesTheEndpoint() {
        val state = MirrorState().apply { preferred = backup }
        assertEquals(listOf(backup, official), state.orderedHosts(RepoEndpoint.DETAIL))
        assertEquals(listOf(backup), state.orderedHosts(RepoEndpoint.INDEX))
    }

    @Test
    fun preferenceIsIgnoredForEndpointsTheHostDoesNotServe() {
        val state = MirrorState().apply { preferred = backup }
        // The backup host answers 404 for the paginated paths, so the preference must not
        // turn the paginated catalogue into a guaranteed failure.
        assertEquals(listOf(official), state.orderedHosts(RepoEndpoint.LIST))
    }

    @Test
    fun unknownPreferenceFallsBackToDefaultOrder() {
        val state = MirrorState().apply { preferred = "https://example.invalid/" }
        RepoEndpoint.entries.forEach { endpoint ->
            val default = MIRRORS.filter { endpoint in it.endpoints }.map { it.host }
            assertEquals(default, state.orderedHosts(endpoint))
        }
    }

    @Test
    fun deadHostsFromTheFrozenManagerAreGone() {
        val hosts = MIRRORS.map { it.host }
        assertTrue(hosts.none { it.contains("blogcdn") })
        assertTrue(hosts.none { it.contains("cloudflare") })
    }

    @Test
    fun autoDoesNotPreferTheFullIndex() {
        assertTrue(!MirrorState().prefersFullIndex())
    }

    @Test
    fun pinningBackupPrefersTheFullIndex() {
        // The backup host has no paginated catalogue, so pinning it can only mean
        // "use the older single file".
        val state = MirrorState().apply { preferred = backup }
        assertTrue(state.prefersFullIndex())
    }

    @Test
    fun pinningTheOfficialHostKeepsThePaginatedCatalogue() {
        val state = MirrorState().apply { preferred = official }
        assertTrue(!state.prefersFullIndex())
    }
}
