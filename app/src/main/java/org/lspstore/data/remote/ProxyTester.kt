package org.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyTester(private val client: HttpClient) {

    suspend fun test(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.currentTimeMillis()
            client.get(TEST_URL)
            System.currentTimeMillis() - started
        }
    }

    private companion object {
        const val TEST_URL = "https://modules.lsposed.org/module-list/1.json"
    }
}
