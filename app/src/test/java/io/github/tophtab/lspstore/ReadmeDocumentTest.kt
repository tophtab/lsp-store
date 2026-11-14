package io.github.tophtab.lspstore

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.tophtab.lspstore.ui.screens.detail.ReadmeBlock
import io.github.tophtab.lspstore.ui.screens.detail.ReadmeDocument
import io.github.tophtab.lspstore.ui.screens.detail.ReadmeDocumentKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadmeDocumentTest {
    private fun key(html: String = "body") = ReadmeDocumentKey(html, "https://example.com/", false, Color.Red, Color.Gray)

    @Test
    fun `parsing leaves the calling thread and reentry immediately reuses the item count`() = runBlocking {
        val caller = Thread.currentThread()
        var parserThread: Thread? = null
        var parses = 0
        val blocks = List(1000) { ReadmeBlock.Text(AnnotatedString("block $it")) }
        val document = ReadmeDocument { parses++; parserThread = Thread.currentThread(); blocks }
        assertSame(blocks, document.load(key()))
        assertNotSame(caller, parserThread)
        assertSame(blocks, document.cached(key()))
        assertSame(blocks, document.load(key()))
        assertEquals(1000, document.cached(key())!!.size)
        assertEquals(1, parses)
    }

    @Test
    fun `theme changes preserve the current anchor while new HTML invalidates content`() = runBlocking {
        var parses = 0
        val document = ReadmeDocument { key -> parses++; listOf(ReadmeBlock.Text(AnnotatedString(key.html))) }
        val initial = document.load(key())
        val recoloured = key().copy(dark = true, linkColor = Color.Blue)
        assertNull(document.cached(recoloured))
        assertSame(initial, document.previousContent(recoloured))
        document.load(recoloured)
        assertEquals(2, parses)
        assertNull(document.cached(key()))
        assertNull(document.previousContent(key("new body")))
        assertNull(document.previousContent(key().copy(baseUrl = "https://other.example/")))
        document.load(key("new body"))
        assertEquals(3, parses)
    }

    @Test
    fun `cancelled parsing cannot publish a cached document`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val document = ReadmeDocument { entered.complete(Unit); awaitCancellation() }
        val loading = async { document.load(key()) }
        entered.await()
        loading.cancelAndJoin()
        assertNull(document.cached(key()))
    }

    @Test
    fun `an older parse cannot replace the cache after a new document finishes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        val document = ReadmeDocument { request ->
            if (request.html == "old") { entered.complete(Unit); finishOld.await() }
            listOf(ReadmeBlock.Text(AnnotatedString(request.html)))
        }
        val old = async { document.load(key("old")) }
        entered.await()
        val fresh = document.load(key("fresh"))
        finishOld.complete(Unit)
        old.await()
        assertSame(fresh, document.cached(key("fresh")))
        assertNull(document.cached(key("old")))
    }
}
