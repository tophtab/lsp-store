package org.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import org.lspstore.data.remote.dto.FullModuleIndexDto
import org.lspstore.data.remote.dto.ModuleListResponseDto
import org.lspstore.data.remote.dto.OnlineModuleDto
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Talks to the LSPosed module repository. The canonical host is modules.lsposed.org;
 * the two mirrors are used by LSPosed Manager itself when the origin fails.
 */
class LsposedRepoApi(
    private val client: HttpClient,
    private val mirrorState: MirrorState = MirrorState(),
) {
    @Volatile
    private var hostIndex = 0

    private suspend fun <T> fetch(
        path: String,
        maxAttempts: Int = Int.MAX_VALUE,
        request: suspend (String) -> T,
    ): T {
        val hosts = mirrorState.orderedHosts()
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
        fetch("module-list/$page.json") { url -> client.get(url).body() }

    /** Single-request full index, the same endpoint the official LSPosed Manager uses. */
    suspend fun fullModuleIndex(): List<FullModuleIndexDto> =
        fetch("modules.json", maxAttempts = 1) { url ->
            client.get(url) {
                // A stalled single-file request must not delay the paginated
                // fallback for the client's full 30 s timeout.
                timeout {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }.body()
        }

    suspend fun moduleDetail(packageName: String): OnlineModuleDto =
        fetch("module/$packageName.json") { url -> client.get(url).body() }

    companion object {
        val DEFAULT_HOSTS = MIRRORS.map { it.host }
    }
}
