package io.github.tophtab.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.github.tophtab.lspstore.data.remote.dto.FullModuleIndexDto
import io.github.tophtab.lspstore.data.remote.dto.ModuleListResponseDto
import io.github.tophtab.lspstore.data.remote.dto.OnlineModuleDto
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Talks to the LSPosed module repository. Hosts are resolved per endpoint: no host
 * serves all three, so a single fallback list would send requests to a host that is
 * guaranteed to answer 403 or 404 (see [MIRRORS]).
 */
class LsposedRepoApi(
    private val client: HttpClient,
    private val mirrorState: MirrorState = MirrorState(),
) {
    @Volatile
    private var hostIndex = 0

    private suspend fun <T> fetch(
        endpoint: RepoEndpoint,
        path: String,
        maxAttempts: Int = Int.MAX_VALUE,
        request: suspend (String) -> T,
    ): T {
        val hosts = mirrorState.orderedHosts(endpoint)
        val attempts = minOf(maxAttempts, hosts.size)
        var last: Throwable? = null
        for (offset in 0 until attempts) {
            val index = (hostIndex + offset) % hosts.size
            try {
                val result = request(hosts[index] + path)
                hostIndex = index
                return result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IOException("No repository host reachable")
    }

    suspend fun moduleList(page: Int): ModuleListResponseDto =
        fetch(RepoEndpoint.LIST, "module-list/$page.json") { url -> client.get(url).body() }

    /**
     * Single-request full index. Only the backup host serves it; the official host
     * rejects the path with 403, so there is nothing to rotate over.
     */
    suspend fun fullModuleIndex(): List<FullModuleIndexDto> =
        fetch(RepoEndpoint.INDEX, "modules.json", maxAttempts = 1) { url ->
            client.get(url) {
                // ~2 MB: the paginated fallback must not wait out the client's
                // default 30 s request timeout on a stalled single file.
                timeout {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 20_000
                    socketTimeoutMillis = 20_000
                }
            }.body()
        }

    suspend fun moduleDetail(packageName: String): OnlineModuleDto =
        fetch(RepoEndpoint.DETAIL, "module/$packageName.json") { url -> client.get(url).body() }

    /**
     * True when the user pinned a host that only serves the single-file index, i.e. the
     * catalogue has to come from `modules.json` for that choice to mean anything.
     */
    fun prefersFullIndex(): Boolean = mirrorState.prefersFullIndex()
}
