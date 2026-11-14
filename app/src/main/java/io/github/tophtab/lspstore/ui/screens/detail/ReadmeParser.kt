package io.github.tophtab.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml

internal sealed interface ReadmeBlock {
    val path: String
    data class Text(val annotated: AnnotatedString, override val path: String = "") : ReadmeBlock
    data class Heading(val level: Int, val annotated: AnnotatedString, override val path: String = "") : ReadmeBlock
    data class Code(val text: String, override val path: String = "") : ReadmeBlock
    data class Bullet(val annotated: AnnotatedString, override val path: String = "", val depth: Int = 0) : ReadmeBlock
    data class Ordered(val number: Int, val annotated: AnnotatedString, override val path: String = "", val depth: Int = 0) : ReadmeBlock
    data class Quote(val annotated: AnnotatedString, override val path: String = "") : ReadmeBlock
    data class Image(val url: String, override val path: String = "") : ReadmeBlock
    data class Badges(val urls: List<String>, override val path: String = "") : ReadmeBlock
    data class Table(val header: ReadmeTableRow?, val rows: List<ReadmeTableRow>, override val path: String = "") : ReadmeBlock
    data class Details(val title: String, val blocks: List<ReadmeBlock>, override val path: String = "") : ReadmeBlock
    data class Divider(override val path: String = "") : ReadmeBlock
}

internal data class ReadmeTableCell(val annotated: AnnotatedString, val imageUrl: String? = null, val isHeader: Boolean = false)

/** Images render separately, so captions cannot retain HTML's OBJ placeholder. */
internal val ReadmeTableCell.captionText: String
    get() = annotated.text.filterNot { it == '\uFFFC' }.trim()

internal data class ReadmeTableRow(val cells: List<ReadmeTableCell>, val isHeader: Boolean, val path: String = "")

internal class ReadmeLinks(val styles: TextLinkStyles, val listener: LinkInteractionListener?, val inlineCodeBackground: Color)

internal fun parseReadme(html: String, baseUrl: String?, links: ReadmeLinks?, dark: Boolean): List<ReadmeBlock> =
    parseReadme(scanReadmeStructure(html), baseUrl, links, dark)

/** Android styling is an adapter; structure and ordering are testable with JVM text renderers. */
internal fun parseReadme(
    structure: ReadmeStructure,
    baseUrl: String?,
    links: ReadmeLinks?,
    dark: Boolean,
    annotate: (String) -> AnnotatedString = { htmlToAnnotated(it, links) },
    plain: (String) -> String = ::plainText,
): List<ReadmeBlock> {
    fun inline(nodes: List<ReadmeHtmlNode>): String = nodes.joinToString("") { node ->
        inlineReadmeHtml(node, baseUrl, dark)
    }
    fun table(node: ReadmeHtmlNode): ReadmeBlock.Table {
        val rows = node.descendants(setOf("table")).filter { it.tag == "tr" }.map { row ->
            val cells = row.children.filter { it.tag == "td" || it.tag == "th" }.map { cell ->
                val image = cell.descendants().filter { it.tag == "img" }.firstNotNullOfOrNull {
                    it.attributes["src"]?.let { src -> resolveReadmeUrl(src, baseUrl, dark) }
                }
                ReadmeTableCell(annotate(inline(cell.children)), image, cell.tag == "th")
            }
            ReadmeTableRow(cells, cells.any { it.isHeader }, row.path)
        }.toList()
        val header = rows.firstOrNull { it.isHeader }
        return ReadmeBlock.Table(header, if (header == null) rows else rows.filterNot { it.isHeader }, node.path)
    }
    fun blocks(nodes: List<ReadmeHtmlNode>, listDepth: Int = 0): List<ReadmeBlock> {
        val output = mutableListOf<ReadmeBlock>()
        val text = StringBuilder()
        var textPath = ""
        fun flush() {
            if (text.isNotEmpty()) {
                val value = annotate(text.toString())
                if (value.text.isNotBlank()) output += ReadmeBlock.Text(value, textPath)
                text.clear()
            }
        }
        fun append(node: ReadmeHtmlNode) {
            if (text.isEmpty()) textPath = node.path
            text.append(inlineReadmeHtml(node, baseUrl, dark))
        }
        fun listItem(node: ReadmeHtmlNode, number: Int?) {
            val nested = node.children.filter { it.hasBlocks }
            val value = annotate(inline(node.children.filterNot { it in nested }))
            if (value.text.isNotBlank()) {
                output += if (number == null) ReadmeBlock.Bullet(value, node.path, listDepth)
                else ReadmeBlock.Ordered(number, value, node.path, listDepth)
            }
            output += blocks(nested, listDepth + 1)
        }
        for (node in nodes) {
            when {
                node.tag in setOf("script", "style", "svg") -> flush()
                node.tag == "aside" && node.attributes["class"].orEmpty().split(' ').any { it.startsWith("ad-slot") } -> flush()
                node.tag == "img" -> {
                    flush()
                    node.attributes["src"]?.let { resolveReadmeUrl(it, baseUrl, dark) }?.let { output += ReadmeBlock.Image(it, node.path) }
                }
                node.tag == "pre" -> {
                    flush()
                    val code = node.innerHtml.trim().let { inner ->
                        if (inner.startsWith("<code", ignoreCase = true) && inner.endsWith("</code>", ignoreCase = true)) {
                            inner.substring(inner.indexOf('>') + 1, inner.length - 7)
                        } else inner
                    }
                    val value = plain(code.replace("<", "&lt;").replace(">", "&gt;"))
                    if (value.isNotBlank()) output += ReadmeBlock.Code(value, node.path)
                }
                node.tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> {
                    flush()
                    val value = annotate(inline(node.children))
                    if (value.text.isNotBlank()) output += ReadmeBlock.Heading(node.tag.last().digitToInt(), value, node.path)
                }
                node.tag == "blockquote" -> {
                    flush()
                    val value = annotate(inline(node.children))
                    if (value.text.isNotBlank()) output += ReadmeBlock.Quote(value, node.path)
                }
                node.tag == "table" -> { flush(); output += table(node) }
                node.tag == "details" -> {
                    flush()
                    val summary = node.summary
                    val title = summary?.let { annotate(inline(it.children)).text.trim() }.orEmpty()
                    output += ReadmeBlock.Details(title, blocks(node.children.filterNot { it === summary }, listDepth), node.path)
                }
                node.tag == "ul" || node.tag == "ol" -> {
                    flush()
                    var number = node.attributes["start"]?.toIntOrNull() ?: 1
                    for (child in node.children) {
                        if (child.tag == "li") {
                            val current = child.attributes["value"]?.toIntOrNull() ?: number
                            listItem(child, if (node.tag == "ol") current else null)
                            number = current + 1
                        } else output += blocks(listOf(child), listDepth)
                    }
                }
                node.tag == "li" -> { flush(); listItem(node, null) }
                node.tag == "hr" -> { flush(); output += ReadmeBlock.Divider(node.path) }
                node.tag in setOf("p", "div", "section", "article") || node.hasBlocks -> {
                    flush()
                    output += blocks(node.children, listDepth)
                }
                else -> append(node)
            }
        }
        flush()
        return groupBadges(output)
    }
    return blocks(structure.nodes)
}

/** Link attributes are rewritten from scanned nodes, without scanning the HTML again. */
private fun inlineReadmeHtml(node: ReadmeHtmlNode, baseUrl: String?, dark: Boolean): String {
    if (node.tag in setOf("script", "style", "svg")) return ""
    if (node.children.isEmpty()) return node.html
    val inner = node.children.joinToString("") { inlineReadmeHtml(it, baseUrl, dark) }
    if (node.tag == "a") {
        val href = node.attributes["href"]?.let { resolveReadmeLink(it, baseUrl) }
        return if (href == null) inner else "<a href=\"${href.replace("&", "&amp;").replace("\"", "&quot;")}\">$inner</a>"
    }
    return node.openingHtml + inner + node.closingHtml
}

internal fun resolveReadmeLink(href: String, baseUrl: String?): String? {
    val value = decodeHtmlEntities(href).trim()
    if (value.startsWith("mailto:", ignoreCase = true)) return value
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
    val resolved = if (uri.isAbsolute) uri else baseUrl?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.net.URI(it.trimEnd('/') + "/").resolve(uri) }.getOrNull()
    } ?: return null
    return resolved.toString().takeIf { resolved.scheme in setOf("http", "https") }
}

internal fun plainText(html: String): String =
    runCatching { Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString() }.getOrDefault("").trim()

/** Runs of consecutive badge images share a row. */
internal fun groupBadges(blocks: List<ReadmeBlock>): List<ReadmeBlock> {
    val grouped = mutableListOf<ReadmeBlock>()
    val pending = mutableListOf<String>()
    var path = ""
    fun flush() {
        if (pending.isNotEmpty()) {
            grouped += ReadmeBlock.Badges(pending.toList(), path)
            pending.clear()
        }
    }
    blocks.forEach { block ->
        if (block is ReadmeBlock.Image && isBadgeUrl(block.url)) {
            if (pending.isEmpty()) path = block.path
            pending += block.url
        } else { flush(); grouped += block }
    }
    flush()
    return grouped
}

internal val BADGE_HOSTS = setOf("img.shields.io", "shields.io", "badgen.net", "badge.fury.io")

internal fun isBadgeUrl(url: String): Boolean {
    val lower = url.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    if (path.endsWith(".svg")) return true
    val host = lower.removePrefix("https://").removePrefix("http://").substringBefore('/')
    return host in BADGE_HOSTS || "/badge" in path || "/badges/" in path
}

internal fun htmlToAnnotated(html: String, links: ReadmeLinks?): AnnotatedString =
    runCatching {
        val parsed = if (links == null) {
            AnnotatedString.fromHtml(html)
        } else {
            AnnotatedString.fromHtml(html, links.styles, links.listener)
        }
        links?.let { parsed.withInlineCodeBackground(it.inlineCodeBackground) } ?: parsed
    }.getOrElse { AnnotatedString("") }

/** GitHub renders inline code as monospace only; komi also tints its background. */
internal fun AnnotatedString.withInlineCodeBackground(color: Color): AnnotatedString {
    if (spanStyles.none { it.item.fontFamily == FontFamily.Monospace }) return this
    val builder = AnnotatedString.Builder(this)
    spanStyles.forEach { range ->
        if (range.item.fontFamily == FontFamily.Monospace) {
            builder.addStyle(SpanStyle(background = color), range.start, range.end)
        }
    }
    return builder.toAnnotatedString()
}

internal fun resolveReadmeUrl(src: String, baseUrl: String?, dark: Boolean): String? {
    val trimmed = decodeHtmlEntities(src).trim()
    if (trimmed.isEmpty() || trimmed.startsWith("data:")) return null
    // GitHub's theme-aware images use #gh-dark-mode-only / #gh-light-mode-only.
    val fragment = trimmed.substringAfter('#', "").lowercase()
    if (fragment.contains("gh-dark-mode-only") && !dark) return null
    if (fragment.contains("gh-light-mode-only") && dark) return null
    val absolute = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        runCatching {
            java.net.URI(base.trimEnd('/') + "/").resolve(trimmed).toString()
        }.getOrNull() ?: return null
    }
    // GitHub blob links are HTML pages; raw.githubusercontent serves the image.
    return if (absolute.contains("github.com") && absolute.contains("/blob/")) {
        absolute.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
    } else {
        absolute
    }
}

/** Image src attributes arrive HTML-escaped, e.g. `?sign=..&amp;t=..`. */
internal fun decodeHtmlEntities(value: String): String = value
    .let { text -> NUMERIC_ENTITY.replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull() ?: match.groupValues[2].toIntOrNull(16)
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
    } }
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private val NUMERIC_ENTITY = Regex("&#(?:([0-9]+)|[xX]([0-9a-fA-F]+));")
