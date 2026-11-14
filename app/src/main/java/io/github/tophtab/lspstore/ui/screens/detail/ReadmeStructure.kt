package io.github.tophtab.lspstore.ui.screens.detail

/** Source ranges retain inline HTML while the structural scanner stays JVM-only. */
internal data class ReadmeHtmlNode(
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<ReadmeHtmlNode>,
    val path: String,
    private val source: String,
    private val start: Int,
    private val contentStart: Int,
    private val contentEnd: Int,
    private val end: Int,
    val hasBlocks: Boolean,
) {
    val html: String get() = source.substring(start, end)
    val innerHtml: String get() = source.substring(contentStart, contentEnd)
    val openingHtml: String get() = source.substring(start, contentStart)
    val closingHtml: String get() = source.substring(contentEnd, end)
    val summary: ReadmeHtmlNode? get() = children.firstOrNull { it.tag == "summary" }

    fun descendants(stopAt: Set<String> = emptySet()): Sequence<ReadmeHtmlNode> = sequence {
        for (child in children) {
            yield(child)
            if (child.tag !in stopAt) yieldAll(child.descendants(stopAt))
        }
    }
}

internal data class ReadmeStructure(val nodes: List<ReadmeHtmlNode>, val scannedTags: Int)

private val BLOCK_TAGS = setOf("img", "pre", "blockquote", "table", "details", "ul", "ol", "li", "hr", "h1", "h2", "h3", "h4", "h5", "h6")
private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val OPAQUE_TAGS = setOf("pre", "script", "style", "svg", "textarea")

private data class HtmlTag(val name: String, val attributes: Map<String, String>, val closing: Boolean, val selfClosing: Boolean, val end: Int)

/** Reads a tag once; quoted `>` and unquoted attributes cannot truncate it. */
private fun readTag(html: String, start: Int): HtmlTag? {
    var cursor = start + 1
    val closing = html.getOrNull(cursor) == '/'
    if (closing) cursor++
    if (html.getOrNull(cursor)?.isLetter() != true) return null
    val nameStart = cursor
    while (cursor < html.length && (html[cursor].isLetterOrDigit() || html[cursor] in "-:")) cursor++
    val name = html.substring(nameStart, cursor).lowercase()
    val attributes = mutableMapOf<String, String>()
    var selfClosing = false
    while (cursor < html.length) {
        while (cursor < html.length && html[cursor].isWhitespace()) cursor++
        when (html.getOrNull(cursor)) {
            '>' -> return HtmlTag(name, attributes, closing, selfClosing, cursor + 1)
            '/' -> { selfClosing = true; cursor++; continue }
            null -> return null
        }
        val attributeStart = cursor
        while (cursor < html.length && !html[cursor].isWhitespace() && html[cursor] !in "=/>\"'") cursor++
        if (cursor == attributeStart) { cursor++; continue }
        val attribute = html.substring(attributeStart, cursor).lowercase()
        while (cursor < html.length && html[cursor].isWhitespace()) cursor++
        var value = ""
        if (html.getOrNull(cursor) == '=') {
            cursor++
            while (cursor < html.length && html[cursor].isWhitespace()) cursor++
            val quote = html.getOrNull(cursor)?.takeIf { it == '\'' || it == '"' }
            if (quote != null) cursor++
            val valueStart = cursor
            while (cursor < html.length && if (quote != null) html[cursor] != quote else !html[cursor].isWhitespace() && html[cursor] != '>') cursor++
            value = html.substring(valueStart, cursor)
            if (quote != null && cursor < html.length) cursor++
        }
        attributes.putIfAbsent(attribute, value)
    }
    return null
}

private class OpenNode(val tag: String, val attributes: Map<String, String>, val start: Int, val contentStart: Int) {
    var contentEnd = contentStart
    var end = contentStart
    val children = mutableListOf<OpenNode>()

    fun freeze(html: String, path: String): ReadmeHtmlNode {
        val children = children.mapIndexed { index, child -> child.freeze(html, "$path/$index") }
        return ReadmeHtmlNode(tag, attributes, children, path, html, start, contentStart, contentEnd, end,
            tag in BLOCK_TAGS || children.any { it.hasBlocks })
    }
}

/** One forward tag scan and an ancestor stack handle nesting and missing end tags. */
internal fun scanReadmeStructure(html: String): ReadmeStructure {
    val root = OpenNode("", emptyMap(), 0, 0)
    val stack = mutableListOf(root)
    var cursor = 0
    var textStart = 0
    var scannedTags = 0
    fun text(start: Int, end: Int) {
        if (start >= end) return
        stack.last().children += OpenNode("", emptyMap(), start, start).also { it.contentEnd = end; it.end = end }
    }
    fun closeFrom(index: Int, contentEnd: Int, end: Int) {
        while (stack.lastIndex >= index) {
            stack.removeAt(stack.lastIndex).also { it.contentEnd = contentEnd; it.end = end }
        }
    }
    while (cursor < html.length) {
        val start = html.indexOf('<', cursor)
        if (start < 0) break
        if (html.startsWith("<!--", start)) {
            text(textStart, start)
            cursor = html.indexOf("-->", start + 4).let { if (it < 0) html.length else it + 3 }
            textStart = cursor
            continue
        }
        val tag = readTag(html, start)
        if (tag == null) { cursor = start + 1; continue }
        scannedTags++
        text(textStart, start)
        if (tag.closing) {
            val index = stack.indexOfLast { it.tag == tag.name }
            if (index > 0) {
                // Unclosed descendants end before their ancestor's closing tag.
                if (stack.lastIndex > index) closeFrom(index + 1, start, start)
                closeFrom(index, start, tag.end)
            } else text(start, tag.end)
        } else {
            val boundaries = when (tag.name) {
                "li" -> setOf("ul", "ol")
                "tr" -> setOf("table")
                "td", "th" -> setOf("tr", "table")
                else -> emptySet()
            }
            if (boundaries.isNotEmpty()) {
                for (index in stack.lastIndex downTo 1) {
                    if (stack[index].tag in boundaries) break
                    if (stack[index].tag == tag.name || tag.name in setOf("td", "th") && stack[index].tag in setOf("td", "th")) {
                        closeFrom(index, start, start)
                        break
                    }
                }
            }
            val node = OpenNode(tag.name, tag.attributes, start, tag.end)
            stack.last().children += node
            when {
                tag.selfClosing || tag.name in VOID_TAGS -> Unit
                tag.name in OPAQUE_TAGS -> {
                    var search = tag.end
                    var closing: HtmlTag? = null
                    var closeStart = html.length
                    while (search < html.length) {
                        val candidate = html.indexOf("</${tag.name}", search, ignoreCase = true)
                        if (candidate < 0) break
                        val endTag = readTag(html, candidate)
                        if (endTag?.closing == true && endTag.name == tag.name) { closing = endTag; closeStart = candidate; break }
                        search = candidate + 2
                    }
                    node.contentEnd = closeStart
                    node.end = closing?.end ?: html.length
                    if (closing != null) scannedTags++
                    cursor = node.end
                    textStart = cursor
                    continue
                }
                else -> stack += node
            }
        }
        cursor = tag.end
        textStart = cursor
    }
    text(textStart, html.length)
    closeFrom(1, html.length, html.length)
    root.contentEnd = html.length
    root.end = html.length
    return ReadmeStructure(root.children.mapIndexed { index, node -> node.freeze(html, index.toString()) }, scannedTags)
}
