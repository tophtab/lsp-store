package org.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
    private val hosts: List<String> = DEFAULT_HOSTS,
) {
    @Volatile
    private var hostIndex = 0

    private suspend fun <T> fetch(path: String, request: suspend (String) -> T): T {
        var last: Throwable? = null
        for (offset in hosts.indices) {
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

    suspend fun moduleDetail(packageName: String): OnlineModuleDto =
        fetch("module/$packageName.json") { url -> client.get(url).body() }

    companion object {
        val DEFAULT_HOSTS = listOf(
            "https://modules.lsposed.org/",
            "https://modules-blogcdn.lsposed.org/",
            "https://modules-cloudflare.lsposed.org/",
        )
    }
}
