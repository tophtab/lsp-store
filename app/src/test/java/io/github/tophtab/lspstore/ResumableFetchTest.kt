package io.github.tophtab.lspstore

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.install.DownloadRetry
import io.github.tophtab.lspstore.data.install.HttpStatusException
import io.github.tophtab.lspstore.data.install.contentRangeStart
import io.github.tophtab.lspstore.data.install.contentRangeTotal
import io.github.tophtab.lspstore.data.install.fetchResumable
import io.github.tophtab.lspstore.data.install.resumeOffset

/**
 * The store resumes APK downloads with `Range`, both after an explicit pause and
 * after a transfer dies mid-stream. These tests cover the cases that used to end
 * in a bare "HTTP 416" error (a partial file that already covers the asset, a
 * server that refuses the range, a 206 for the wrong slice) and the retry path
 * that a dropped Wi-Fi connection now takes: the next request asks only for the
 * tail that never arrived.
 */
class ResumableFetchTest {

    private val client = OkHttpClient()

    private val payload = ByteArray(250) { (it % 251).toByte() }

    /** Retries without waiting; the policy's timing is tested separately. */
    private val fastRetry = DownloadRetry(sleep = {})

    private data class Response(
        val code: Int,
        val body: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Advertise the full body but write only this many bytes, then close. */
        val truncateAt: Int? = null,
    )

    /**
     * Minimal loopback HTTP/1.1 server (the unit-test classpath has no mock web
     * server) that records the `Range` header of every request.
     */
    private class TestServer(private val handler: (String?) -> Response) : AutoCloseable {
        val ranges = CopyOnWriteArrayList<String?>()
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val worker = Thread(::acceptLoop, "test-http").apply {
            isDaemon = true
            start()
        }

        val url: String
            get() = "http://127.0.0.1:${socket.localPort}/module.apk"

        private fun acceptLoop() {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    return
                }
                client.use { serve(it) }
            }
        }

        private fun serve(client: java.net.Socket) {
            val input = client.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return
                head.append(byte.toChar())
            }
            val range = head.lineSequence()
                .drop(1)
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            ranges += range

            val response = handler(range)
            val reason = when (response.code) {
                206 -> "Partial Content"
                416 -> "Range Not Satisfiable"
                else -> "OK"
            }
            val headers = buildString {
                append("HTTP/1.1 ${response.code} $reason\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${response.body?.size ?: 0}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = client.getOutputStream()
            output.write(headers.toByteArray())
            response.body?.let { body ->
                output.write(body, 0, response.truncateAt?.coerceAtMost(body.size) ?: body.size)
            }
            output.flush()
        }

        override fun close() {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun tempFile(name: String, prefix: ByteArray = ByteArray(0)): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "fetch-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(prefix) }
    }

    private suspend fun download(
        server: TestServer,
        target: File,
        expectedSize: Long = payload.size.toLong(),
        retry: DownloadRetry = fastRetry,
    ): Pair<Long, List<Pair<Long, Long>>> {
        val progress = mutableListOf<Pair<Long, Long>>()
        val serverSize = fetchResumable(
            client = client,
            url = server.url,
            target = target,
            resumeFrom = target.length(),
            expectedSize = expectedSize,
            onProgress = { downloaded, total -> progress += downloaded to total },
            retry = retry,
        )
        return serverSize to progress
    }

    @Test
    fun resumesFromPartialFileWhenServerHonoursTheRange() = runBlocking {
        TestServer { range ->
            if (range == "bytes=100-") {
                Response(
                    206,
                    payload.copyOfRange(100, payload.size),
                    mapOf("Content-Range" to "bytes 100-249/250"),
                )
            } else {
                Response(200, payload)
            }
        }.use { server ->
            val target = tempFile("resume.apk", payload.copyOfRange(0, 100))
            val (serverSize, progress) = download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(listOf<String?>("bytes=100-"), server.ranges)
            assertEquals(100L to 250L, progress.first())
            assertEquals(250L to 250L, progress.last())
            // The Content-Range total, not the size we passed in.
            assertEquals(250L, serverSize)
        }
    }

    @Test
    fun completePartialFileIsNotResumedWithAnEmptyRange() = runBlocking {
        TestServer { range ->
            if (range != null) {
                Response(416, null, mapOf("Content-Range" to "bytes */250"))
            } else {
                Response(200, payload)
            }
        }.use { server ->
            // A pause that landed after the last write leaves a file at full size.
            val target = tempFile("complete.apk", payload)
            download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(listOf<String?>(null), server.ranges)
        }
    }

    @Test
    fun partialFilePastTheExpectedSizeRestartsFromZero() = runBlocking {
        TestServer { range ->
            if (range != null) {
                Response(416, null, mapOf("Content-Range" to "bytes */250"))
            } else {
                Response(200, payload)
            }
        }.use { server ->
            // Stale bytes from an asset that was re-uploaded smaller.
            val target = tempFile("stale.apk", ByteArray(400) { 7 })
            download(server, target)

            assertArrayEquals(payload, target.readBytes())
        }
    }

    @Test
    fun rangeRejectedWith416FallsBackToAFreshRequest() = runBlocking {
        TestServer { range ->
            if (range != null) {
                Response(416)
            } else {
                Response(200, payload)
            }
        }.use { server ->
            val target = tempFile("rejected.apk", payload.copyOfRange(0, 100))
            download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(listOf("bytes=100-", null), server.ranges)
        }
    }

    @Test
    fun ignoredRangeTruncatesThePartialFile() = runBlocking {
        TestServer { _ -> Response(200, payload) }.use { server ->
            val target = tempFile("ignored.apk", payload.copyOfRange(0, 100))
            val (serverSize, _) = download(server, target)

            assertArrayEquals(payload, target.readBytes())
            // A plain 200 is authoritative even if the index size disagreed.
            assertEquals(250L, serverSize)
        }
    }

    @Test
    fun partialContentStartingElsewhereIsDiscarded() = runBlocking {
        TestServer { range ->
            if (range != null) {
                Response(206, payload, mapOf("Content-Range" to "bytes 0-249/250"))
            } else {
                Response(200, payload)
            }
        }.use { server ->
            val target = tempFile("wrong-slice.apk", payload.copyOfRange(0, 100))
            download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(listOf("bytes=100-", null), server.ranges)
        }
    }

    @Test
    fun droppedConnectionResumesFromTheBytesAlreadyWritten() = runBlocking {
        TestServer { range ->
            if (range == "bytes=100-") {
                Response(
                    206,
                    payload.copyOfRange(100, payload.size),
                    mapOf("Content-Range" to "bytes 100-249/250"),
                )
            } else {
                // The Wi-Fi drops after 100 of the 250 advertised bytes.
                Response(200, payload, truncateAt = 100)
            }
        }.use { server ->
            val target = tempFile("dropped.apk")
            val (serverSize, progress) = download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(listOf(null, "bytes=100-"), server.ranges)
            // The retry reports the bytes already on disk instead of restarting
            // the progress bar at zero.
            assertTrue(progress.contains(100L to 250L))
            assertEquals(250L to 250L, progress.last())
            assertEquals(250L, serverSize)
        }
    }

    @Test
    fun serverErrorIsRetried() = runBlocking {
        val calls = AtomicInteger()
        TestServer {
            if (calls.getAndIncrement() < 2) Response(503) else Response(200, payload)
        }.use { server ->
            val target = tempFile("retry.apk")
            download(server, target)

            assertArrayEquals(payload, target.readBytes())
            assertEquals(3, server.ranges.size)
        }
    }

    @Test
    fun clientErrorIsNotRetried() = runBlocking {
        TestServer { Response(404) }.use { server ->
            val target = tempFile("gone.apk")
            val error = assertThrows(HttpStatusException::class.java) {
                runBlocking { download(server, target) }
            }

            assertEquals(404, error.code)
            assertEquals(1, server.ranges.size)
        }
    }

    @Test
    fun retriesStopAtTheAttemptLimitAndKeepThePartialFile() = runBlocking {
        TestServer { range ->
            val start = range
                ?.substringAfter("bytes=")
                ?.substringBefore('-')
                ?.toIntOrNull() ?: 0
            if (start == 0) {
                Response(200, payload, truncateAt = 100)
            } else {
                // Each resume gets 25 more bytes and then the connection dies.
                Response(
                    206,
                    payload.copyOfRange(start, payload.size),
                    mapOf("Content-Range" to "bytes $start-249/250"),
                    truncateAt = 25,
                )
            }
        }.use { server ->
            val target = tempFile("exhausted.apk")
            assertThrows(IOException::class.java) {
                runBlocking { download(server, target) }
            }

            // Four attempts: the initial one plus three retries.
            assertEquals(4, server.ranges.size)
            assertEquals(
                listOf(null, "bytes=100-", "bytes=125-", "bytes=150-"),
                server.ranges,
            )
            // The bytes are kept for the user's next attempt instead of being
            // thrown away, so that retry only fetches the missing tail.
            assertEquals(175L, target.length())
        }
    }

    @Test
    fun resumeOffsetRefusesFilesThatCoverTheAsset() {
        assertEquals(100L, resumeOffset(100, 250))
        assertEquals(0L, resumeOffset(250, 250))
        assertEquals(0L, resumeOffset(400, 250))
        assertEquals(0L, resumeOffset(0, 250))
        // Unknown size: keep resuming, the server decides.
        assertEquals(100L, resumeOffset(100, 0))
    }

    @Test
    fun contentRangeTotalParsesHeaders() {
        assertEquals(1000L, contentRangeTotal("bytes 100-999/1000"))
        assertEquals(1L, contentRangeTotal("bytes 0-0/1"))
        // Unsatisfiable ranges still carry the real total.
        assertEquals(1000L, contentRangeTotal("bytes */1000"))
        assertNull(contentRangeTotal(null))
        assertNull(contentRangeTotal(""))
    }

    @Test
    fun contentRangeStartParsesHeaders() {
        assertEquals(100L, contentRangeStart("bytes 100-999/1000"))
        assertEquals(0L, contentRangeStart("bytes 0-0/1"))
        assertNull(contentRangeStart("bytes */1000"))
        assertNull(contentRangeStart(null))
        assertNull(contentRangeStart(""))
    }
}
