package io.github.tophtab.lspstore

import com.sun.net.httpserver.HttpServer
import io.github.tophtab.lspstore.data.probe.HttpRangeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HttpRangeSourceTest {
    private data class Reply(
        val status: Int = 206,
        val body: ByteArray = byteArrayOf(1, 2, 3, 4),
        val range: String? = "bytes 10-13/100",
        val length: Long = body.size.toLong(), // 0 selects chunked transport.
        val beforeBody: () -> Unit = {},
    )

    private class Server(private val reply: Reply) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "range-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { exchange ->
                try {
                    reply.range?.let { exchange.responseHeaders.add("Content-Range", it) }
                    exchange.sendResponseHeaders(reply.status, reply.length)
                    reply.beforeBody()
                    exchange.responseBody.use { it.write(reply.body) }
                } catch (_: Exception) {
                    // Truncation, cancellation and oversized-body rejection close the socket.
                } finally { exchange.close() }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/app.apk"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private fun client(timeoutMs: Long = 2000) = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 1000
            socketTimeoutMillis = timeoutMs
            requestTimeoutMillis = timeoutMs
        }
    }

    @Test
    fun `valid fixed and chunked 206 bodies return exactly the requested interval`() = runBlocking {
        client().use { client ->
            for (length in listOf(4L, 0L)) {
                Server(Reply(length = length)).use { server ->
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), HttpRangeSource(client).read(server.url, 10, 13))
                }
            }
        }
    }

    @Test
    fun `missing malformed shifted and impossible content ranges are rejected`() = runBlocking {
        client().use { client ->
            for (range in listOf(null, "garbage", "bytes 0-3/100", "bytes 10-12/100", "bytes 10-13/13", "bytes 10-13/999999999999999999999")) {
                Server(Reply(range = range)).use { server ->
                    assertNull("range=$range", HttpRangeSource(client).read(server.url, 10, 13))
                }
            }
        }
    }

    @Test
    fun `short and oversized bodies are rejected including a large chunked response`() = runBlocking {
        client().use { client ->
            val replies = listOf(
                Reply(body = byteArrayOf(1, 2), length = 4),
                Reply(body = ByteArray(5)),
                Reply(body = ByteArray(4 * 1024 * 1024), length = 0),
                Reply(body = byteArrayOf(1, 2), length = 0),
            )
            for (reply in replies) Server(reply).use { server ->
                assertNull(HttpRangeSource(client).read(server.url, 10, 13))
            }
        }
    }

    @Test
    fun `ignored ranges accept only an exact bounded whole file`() = runBlocking {
        client().use { client ->
            Server(Reply(status = 200, range = null)).use { server ->
                val source = HttpRangeSource(client)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.read(server.url, 0, 3))
                assertNull(source.read(server.url, 0, 1))
                assertNull(source.read(server.url, 10, 13))
                assertNull(source.read(server.url, -1, 3))
                assertNull(source.read(server.url, 3, 2))
                assertNull(source.read(server.url, 0, 8L * 1024 * 1024))
            }
        }
    }

    @Test
    fun `a stalled response times out and returns a probe miss`() = runBlocking {
        val release = CountDownLatch(1)
        Server(Reply(beforeBody = { release.await(5, TimeUnit.SECONDS) })).use { server ->
            try {
                client(200).use { client ->
                    assertNull(withTimeout(3000) { HttpRangeSource(client).read(server.url, 10, 13) })
                }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `parent cancellation is propagated while reading a stalled body`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server(Reply(beforeBody = { entered.countDown(); release.await(5, TimeUnit.SECONDS) })).use { server ->
            try {
                client().use { client ->
                    val read = async(Dispatchers.IO) { HttpRangeSource(client).read(server.url, 10, 13) }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    read.cancelAndJoin()
                    assertThrows(CancellationException::class.java) { runBlocking { read.await() } }
                    Unit
                }
            } finally { release.countDown() }
        }
    }
}
