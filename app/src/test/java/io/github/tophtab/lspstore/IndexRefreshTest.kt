package io.github.tophtab.lspstore

import com.sun.net.httpserver.HttpServer
import io.github.tophtab.lspstore.data.remote.LsposedRepoApi
import io.github.tophtab.lspstore.data.remote.MirrorState
import io.github.tophtab.lspstore.data.remote.createHttpClient
import io.github.tophtab.lspstore.data.remote.createJson
import io.github.tophtab.lspstore.data.repository.ModuleRepositoryImpl
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.takeFrom
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexRefreshTest {
    @get:Rule val directory = TemporaryFolder()

    private class Fixture(file: File, clock: () -> Long = { 1_000_000L }) : AutoCloseable {
        private val json = createJson()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body = if (path == "/modules.json") "[{\"name\":\"backup\"}]" else {
                    val page = path.substringAfterLast('/').substringBefore('.').toInt()
                    "{\"page\":$page,\"pageCount\":3,\"total\":3,\"modules\":[{\"name\":\"page-$page\"}]}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var beforeRequest: suspend (String) -> Unit = {}
        val mirror = MirrorState()
        private val client = createHttpClient(json).apply {
            plugin(HttpSend).intercept { request ->
                val path = request.url.build().encodedPath
                requests += path
                beforeRequest(path)
                request.url.takeFrom("http://127.0.0.1:${server.address.port}$path")
                execute(request)
            }
        }
        val repository = ModuleRepositoryImpl(file, LsposedRepoApi(client, mirror), json, clock)
        override fun close() { client.close(); server.stop(0) }
    }

    @Test
    fun `overlapping forced refreshes share one full pagination round and a later refresh is independent`() = runBlocking {
        Fixture(directory.newFile()).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeRequest = { path -> if (path == "/module-list/1.json") { entered.complete(Unit); release.await() } }
            val repository = fixture.repository
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.getIndex(force = true) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshIndexIfNeeded(force = true) }
            val third = async(start = CoroutineStart.UNDISPATCHED) { repository.getIndex(force = true) }
            release.complete(Unit)
            val modules = first.await()
            assertSame(modules, second.await())
            assertSame(modules, third.await())
            assertEquals(listOf("page-1", "page-2", "page-3"), modules.map { it.packageName })
            assertEquals(3, fixture.requests.size)
            repository.getIndex(force = true)
            assertEquals(6, fixture.requests.size)
            repository.getIndex(force = false)
            repository.refreshIndexIfNeeded(force = false)
            assertEquals(6, fixture.requests.size)
        }
    }

    @Test
    fun `a failed refresh is not shared and a waiting refresh can retry`() = runBlocking {
        Fixture(directory.newFile()).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstPages = AtomicInteger()
            fixture.beforeRequest = { path ->
                if (path == "/module-list/1.json" && firstPages.getAndIncrement() == 0) {
                    entered.complete(Unit)
                    release.await()
                    throw IOException("first paginated attempt failed")
                }
                if (path == "/modules.json") throw IOException("backup unavailable")
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.repository.getIndex(force = true) } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.refreshIndexIfNeeded(force = true) }
            release.complete(Unit)
            assertTrue(first.await().isFailure)
            assertEquals(3, second.await().size)
            assertEquals(2, firstPages.get())
            assertEquals(1, fixture.requests.count { it == "/modules.json" })
        }
    }

    @Test
    fun `mirror changes do not reuse a success from another index source`() = runBlocking {
        Fixture(directory.newFile()).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeRequest = { path -> if (path == "/module-list/1.json") { entered.complete(Unit); release.await() } }
            val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.getIndex(force = true) }
            entered.await()
            fixture.mirror.preferred = "https://backup.modules.lsposed.org/"
            val backup = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.getIndex(force = true) }
            release.complete(Unit)
            assertEquals(3, first.await().size)
            assertEquals(listOf("backup"), backup.await().map { it.packageName })
            assertEquals(4, fixture.requests.size)
            assertTrue(fixture.repository.hasFetchedIndexThisSession())
            fixture.mirror.preferred = ""
            assertFalse(fixture.repository.hasFetchedIndexThisSession())
            assertEquals(3, fixture.repository.refreshIndexIfNeeded(force = false).size)
            assertEquals(7, fixture.requests.size)
        }
    }

    @Test
    fun `clear invalidates in-flight and queued writes while overlapping callers still share the result`() = runBlocking {
        val file = directory.newFile()
        Fixture(file).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeRequest = { path -> if (path == "/module-list/1.json") { entered.complete(Unit); release.await() } }
            val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.getIndex(force = true) }
            entered.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.refreshIndexIfNeeded(force = true) }
            fixture.repository.clearCache()
            release.complete(Unit)
            assertSame(first.await(), waiting.await())
            assertEquals(3, fixture.requests.size)
            assertFalse(file.exists())
            assertEquals(0L, fixture.repository.cacheSizeBytes())
            assertNull(fixture.repository.getCachedIndex())
            assertFalse(fixture.repository.hasFetchedIndexThisSession())
            fixture.repository.getIndex(force = true)
            assertEquals(6, fixture.requests.size)
            assertTrue(file.exists())
            assertNotNull(fixture.repository.getCachedIndex())
        }
    }

    @Test
    fun `a cancelled caller releases refresh ownership and does not suppress a waiting retry`() = runBlocking {
        Fixture(directory.newFile()).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstPages = AtomicInteger()
            fixture.beforeRequest = { path ->
                if (path == "/module-list/1.json" && firstPages.getAndIncrement() == 0) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.getIndex(force = true) }
            entered.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.getIndex(force = true) }
            first.cancelAndJoin()
            assertEquals(3, waiting.await().size)
            assertEquals(2, firstPages.get())
        }
    }

    @Test
    fun `old cache format is readable and stale cache remains the ordinary offline fallback`() = runBlocking {
        val file = directory.newFile().apply { writeText("{\"savedAt\":1,\"modules\":[{\"name\":\"old\"}]}") }
        Fixture(file) { 30_000_000L }.use { fixture ->
            fixture.beforeRequest = { throw IOException("offline") }
            assertEquals("old", fixture.repository.getCachedIndex()!!.single().packageName)
            assertEquals("old", fixture.repository.getIndex(force = false).single().packageName)
            val forced = runCatching { fixture.repository.getIndex(force = true) }
            assertTrue(forced.isFailure)
            assertFalse(fixture.repository.hasFetchedIndexThisSession())
        }
    }

    @Test
    fun `corrupt cache is ignored and rebuilt from the entire index`() = runBlocking {
        val file = directory.newFile().apply { writeText("{truncated") }
        Fixture(file).use { fixture ->
            assertNull(fixture.repository.getCachedIndex())
            assertEquals(3, fixture.repository.getIndex().size)
            assertEquals(3, fixture.requests.size)
            assertTrue(file.readText().contains("page-3"))
            assertEquals(setOf(file.name), file.parentFile!!.listFiles()!!.map { it.name }.toSet())
        }
    }

    @Test
    fun `atomic cache failure does not lose a successful network result or trigger fallback`() = runBlocking {
        val unwritableTarget = directory.newFolder("cache.json")
        Fixture(unwritableTarget).use { fixture ->
            val modules = fixture.repository.getIndex(force = true)
            assertEquals(3, modules.size)
            assertEquals(3, fixture.requests.size)
            assertTrue(fixture.repository.hasFetchedIndexThisSession())
            assertSame(modules, fixture.repository.getCachedIndex())
            assertTrue(unwritableTarget.isDirectory)
            assertEquals(listOf("cache.json"), directory.root.listFiles()!!.map { it.name })
        }
    }
}
