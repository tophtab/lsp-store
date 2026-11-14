package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal data class ReadmeDocumentKey(
    val html: String,
    val baseUrl: String?,
    val dark: Boolean,
    val linkColor: Color,
    val inlineCodeBackground: Color,
)

/** One page's parsed content survives composition without retaining a URI handler. */
internal class ReadmeDocument(
    private val parse: (suspend (ReadmeDocumentKey) -> List<ReadmeBlock>)? = null,
) {
    private data class Entry(val key: ReadmeDocumentKey, val blocks: List<ReadmeBlock>)
    @Volatile private var entry: Entry? = null
    @Volatile private var structure: Pair<String, ReadmeStructure>? = null
    private val generation = AtomicLong()

    fun cached(key: ReadmeDocumentKey): List<ReadmeBlock>? = entry?.takeIf { it.key == key }?.blocks

    /** Keep the item count/scroll anchor while recolouring the same document. */
    fun previousContent(key: ReadmeDocumentKey): List<ReadmeBlock>? = entry?.takeIf {
        it.key.html == key.html && it.key.baseUrl == key.baseUrl
    }?.blocks

    suspend fun load(key: ReadmeDocumentKey): List<ReadmeBlock> {
        val request = generation.incrementAndGet()
        cached(key)?.let { return it }
        val blocks = withContext(Dispatchers.Default) {
            parse?.invoke(key) ?: run {
                val scanned = structure?.takeIf { it.first == key.html }?.second ?: scanReadmeStructure(key.html).also {
                    if (generation.get() == request) structure = key.html to it
                }
                val links = ReadmeLinks(
                    TextLinkStyles(SpanStyle(color = key.linkColor, textDecoration = TextDecoration.Underline)),
                    listener = null,
                    inlineCodeBackground = key.inlineCodeBackground,
                )
                parseReadme(scanned, key.baseUrl, links, key.dark)
            }
        }
        if (generation.get() == request) entry = Entry(key, blocks)
        return blocks
    }
}
