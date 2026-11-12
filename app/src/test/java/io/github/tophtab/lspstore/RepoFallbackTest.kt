package io.github.tophtab.lspstore

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tophtab.lspstore.data.remote.*

class RepoFallbackTest {
    @Test
    fun transientOfficialFailureDoesNotPinSubsequentDetailsToBackup() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { response ->
            val body = "{\"name\":\"fixture\"}".toByteArray()
            response.responseHeaders.add("Content-Type", "application/json")
            response.sendResponseHeaders(200, body.size.toLong())
            response.responseBody.use { it.write(body) }
        }
        server.start()
        val client = createHttpClient(createJson())
        val hosts = mutableListOf<String>()
        client.plugin(HttpSend).intercept { request ->
            hosts += request.url.host
            if (hosts.size == 1) throw IOException("One transient official failure")
            request.url.takeFrom("http://127.0.0.1:${server.address.port}/fixture")
            execute(request)
        }
        try {
            val state = MirrorState()
            val api = LsposedRepoApi(client, state)
            api.moduleDetail("fixture")
            api.moduleDetail("fixture")
            state.preferred = "https://backup.modules.lsposed.org/"
            api.moduleDetail("fixture")
            assertEquals(listOf(
                "modules.lsposed.org", "backup.modules.lsposed.org", "modules.lsposed.org",
                "backup.modules.lsposed.org",
            ), hosts)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
