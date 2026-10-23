package io.github.tophtab.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reachability probe for the mirror picker.
 *
 * Each host is probed on a path it actually claims to serve: probing everyone with
 * `module-list/1.json` reports the backup host as unreachable even though it is the
 * only host that serves `modules.json`. HEAD keeps the 2 MB index off the wire; the
 * paginated list is preferred because it is ~27 KB.
 */
class MirrorTester(private val client: HttpClient) {

    suspend fun test(mirror: Mirror): Result<Long> = withContext(Dispatchers.IO) {
        val probePath = when {
            RepoEndpoint.LIST in mirror.endpoints -> RepoEndpoint.LIST.probePath
            RepoEndpoint.INDEX in mirror.endpoints -> RepoEndpoint.INDEX.probePath
            else -> "" // detail-only host: ask for the site root
        }
        runCatching {
            val started = System.currentTimeMillis()
            client.head(mirror.host + probePath) {
                timeout {
                    connectTimeoutMillis = 8_000
                    requestTimeoutMillis = 12_000
                    socketTimeoutMillis = 12_000
                }
            }
            System.currentTimeMillis() - started
        }
    }
}
