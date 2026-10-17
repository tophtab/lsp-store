package org.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MirrorTester(private val client: HttpClient) {

    suspend fun test(host: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.currentTimeMillis()
            client.get(host + TEST_PATH)
            System.currentTimeMillis() - started
        }
    }

    suspend fun testAll(hosts: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        hosts.mapNotNull { host -> test(host).getOrNull()?.let { host to it } }.toMap()
    }

    private companion object {
        const val TEST_PATH = "module-list/1.json"
    }
}
