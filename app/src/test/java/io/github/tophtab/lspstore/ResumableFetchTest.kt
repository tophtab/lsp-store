package io.github.tophtab.lspstore

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tophtab.lspstore.data.install.contentRangeStart
import io.github.tophtab.lspstore.data.install.contentRangeTotal
import io.github.tophtab.lspstore.data.install.fetchResumable
import io.github.tophtab.lspstore.data.install.resumeOffset

/**
 * The store resumes paused APK downloads with `Range`. These tests cover the
 * cases that used to end in a bare "HTTP 416" error: a partial file that already
 * covers the asset, a server that refuses the range, and a 206 for the wrong
 * slice.
 */
class ResumableFetchTest {

    private val client = OkHttpClient()

    private val payload = ByteArray(250) { (it % 251).toByte() }

    private data class Response(
        val code: Int,
        val body: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
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
            response.body?.let { output.write(it) }
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

    private fun download(
        server: TestServer,
        target: File,
        expectedSize: Long = payload.size.toLong(),
    ): Pair<Long, List<Pair<Long, Long>>> {
        val progress = mutableListOf<Pair<Long, Long>>()
        val serverSize = fetchResumable(client, server.url, target, target.length(), expectedSize) {
                downloaded,
                total,
            ->
            progress += downloaded to total
        }
        return serverSize to progress
    }

    @Test
    fun resumesFromPartialFileWhenServerHonoursTheRange() {
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
    fun completePartialFileIsNotResumedWithAnEmptyRange() {
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
    fun partialFilePastTheExpectedSizeRestartsFromZero() {
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
    fun rangeRejectedWith416FallsBackToAFreshRequest() {
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
    fun ignoredRangeTruncatesThePartialFile() {
        TestServer { _ -> Response(200, payload) }.use { server ->
            val target = tempFile("ignored.apk", payload.copyOfRange(0, 100))
            val (serverSize, _) = download(server, target)

            assertArrayEquals(payload, target.readBytes())
            // A plain 200 is authoritative even if the index size disagreed.
            assertEquals(250L, serverSize)
        }
    }

    @Test
    fun partialContentStartingElsewhereIsDiscarded() {
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
