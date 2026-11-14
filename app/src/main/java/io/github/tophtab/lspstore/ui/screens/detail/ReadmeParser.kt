package io.github.tophtab.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml

internal val SCRIPT_TAG = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
internal val AD_ASIDE = Regex("<aside class=\"ad-slot[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE)

internal sealed interface ReadmeBlock {
    data class Text(val annotated: AnnotatedString) : ReadmeBlock
    data class Heading(val level: Int, val annotated: AnnotatedString) : ReadmeBlock
    data class Code(val text: String) : ReadmeBlock
    data class Bullet(val annotated: AnnotatedString) : ReadmeBlock
    data class Ordered(val number: Int, val annotated: AnnotatedString) : ReadmeBlock
    data class Quote(val annotated: AnnotatedString) : ReadmeBlock
    data class Image(val url: String) : ReadmeBlock
    data class Badges(val urls: List<String>) : ReadmeBlock
    data class Table(val header: ReadmeTableRow?, val rows: List<ReadmeTableRow>) : ReadmeBlock
    data class Details(val title: String, val blocks: List<ReadmeBlock>) : ReadmeBlock
    data object Divider : ReadmeBlock
}

internal data class ReadmeTableCell(
    val annotated: AnnotatedString,
    val imageUrl: String? = null,
    val isHeader: Boolean = false,
)

/**
 * `AnnotatedString.fromHtml` turns every `<img>` into U+FFFC (the object
 * replacement character), which Text renders as an `OBJ` box. Table images are
 * drawn with AsyncImage instead, so their captions must not keep that marker.
 */
internal val ReadmeTableCell.captionText: String
    get() = annotated.text.filterNot { it == '\uFFFC' }.trim()

internal data class ReadmeTableRow(
    val cells: List<ReadmeTableCell>,
    val isHeader: Boolean,
)

internal val SVG_TAG = Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE)
internal val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->", RegexOption.IGNORE_CASE)

/** Drops the repository's injected ad slots, scripts, SVGs and comments. */
internal fun sanitizeReadme(html: String): String {
    var out = SCRIPT_TAG.replace(html, "")
    out = AD_ASIDE.replace(out, "")
    out = SVG_TAG.replace(out, "")
    out = HTML_COMMENT.replace(out, "")
    return out
}

internal val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
internal val SRC_ATTR = Regex("(?<![\\w-])src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
internal val PRE_BLOCK = Regex("<pre\\b[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE)
internal val HEADING = Regex("<h([1-6])\\b[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
internal val QUOTE_BLOCK = Regex("<blockquote\\b[^>]*>([\\s\\S]*?)</blockquote>", RegexOption.IGNORE_CASE)
internal val LIST_ITEM = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
internal val ORDERED_LIST = Regex("<ol\\b[^>]*>([\\s\\S]*?)</ol>", RegexOption.IGNORE_CASE)
internal val TABLE_BLOCK = Regex("<table\\b[^>]*>([\\s\\S]*?)</table>", RegexOption.IGNORE_CASE)
internal val TABLE_ROW = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
internal val TABLE_CELL = Regex("<t([hd])\\b[^>]*>([\\s\\S]*?)</t\\1>", RegexOption.IGNORE_CASE)
internal val DETAILS_BLOCK = Regex("<details\\b[^>]*>([\\s\\S]*?)</details>", RegexOption.IGNORE_CASE)
internal val SUMMARY_TAG = Regex("<summary\\b[^>]*>([\\s\\S]*?)</summary>", RegexOption.IGNORE_CASE)
internal val HR_TAG = Regex("<hr\\b[^>]*>", RegexOption.IGNORE_CASE)

internal class ReadmeLinks(
    val styles: TextLinkStyles,
    val listener: LinkInteractionListener?,
    val inlineCodeBackground: Color,
)

internal enum class ReadmeKind { Image, Code, Heading, Quote, Bullet, Ordered, Table, Details, Divider }

internal class ReadmeMatch(
    val kind: ReadmeKind,
    val match: MatchResult,
) {
    val range get() = match.range
}

internal fun parseReadme(
    html: String,
    baseUrl: String?,
    links: ReadmeLinks?,
    dark: Boolean,
): List<ReadmeBlock> {
    val sanitized = sanitizeReadme(html)
    val blocks = mutableListOf<ReadmeBlock>()
    var cursor = 0
    while (cursor < sanitized.length) {
        val candidates = buildList {
            IMG_TAG.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Image, it)) }
            PRE_BLOCK.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Code, it)) }
            HEADING.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Heading, it)) }
            QUOTE_BLOCK.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Quote, it)) }
            TABLE_BLOCK.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Table, it)) }
            DETAILS_BLOCK.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Details, it)) }
            ORDERED_LIST.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Ordered, it)) }
            LIST_ITEM.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Bullet, it)) }
            HR_TAG.find(sanitized, cursor)?.let { add(ReadmeMatch(ReadmeKind.Divider, it)) }
        }
        val next = candidates.minByOrNull { it.range.first } ?: break

        val before = htmlToAnnotated(sanitized.substring(cursor, next.range.first), links)
        if (before.text.isNotBlank()) blocks += ReadmeBlock.Text(before)

        when (next.kind) {
            ReadmeKind.Image -> {
                val src = SRC_ATTR.find(next.match.value)?.groupValues?.get(1)
                val resolved = src?.let { resolveReadmeUrl(it, baseUrl, dark) }
                if (resolved != null) blocks += ReadmeBlock.Image(resolved)
            }

            ReadmeKind.Code -> {
                val text = plainText(next.match.groupValues[1])
                if (text.isNotBlank()) blocks += ReadmeBlock.Code(text)
            }

            ReadmeKind.Heading -> {
                val level = next.match.groupValues[1].toIntOrNull() ?: 2
                val annotated = htmlToAnnotated(next.match.groupValues[2], links)
                if (annotated.text.isNotBlank()) blocks += ReadmeBlock.Heading(level, annotated)
            }

            ReadmeKind.Quote -> {
                val annotated = htmlToAnnotated(next.match.groupValues[1], links)
                if (annotated.text.isNotBlank()) blocks += ReadmeBlock.Quote(annotated)
            }

            ReadmeKind.Table -> blocks += parseTable(next.match.groupValues[1], baseUrl, links, dark)

            ReadmeKind.Details -> {
                blocks += parseDetails(next.match.groupValues[1], baseUrl, links, dark)
            }

            ReadmeKind.Ordered -> {
                var number = 1
                LIST_ITEM.findAll(next.match.groupValues[1]).forEach { item ->
                    val annotated = htmlToAnnotated(item.groupValues[1], links)
                    if (annotated.text.isNotBlank()) {
                        blocks += ReadmeBlock.Ordered(number, annotated)
                        number++
                    }
                }
            }

            ReadmeKind.Bullet -> {
                val annotated = htmlToAnnotated(next.match.groupValues[1], links)
                if (annotated.text.isNotBlank()) blocks += ReadmeBlock.Bullet(annotated)
            }

            ReadmeKind.Divider -> blocks += ReadmeBlock.Divider
        }
        cursor = next.range.last + 1
    }
    val tail = htmlToAnnotated(sanitized.substring(cursor.coerceAtMost(sanitized.length)), links)
    if (tail.text.isNotBlank()) blocks += ReadmeBlock.Text(tail)
    return groupBadges(blocks)
}

internal fun parseTable(
    inner: String,
    baseUrl: String?,
    links: ReadmeLinks?,
    dark: Boolean,
): ReadmeBlock.Table {
    val rows = TABLE_ROW.findAll(inner).map { row ->
        val cells = TABLE_CELL.findAll(row.groupValues[1]).map { cell ->
            val isHeader = cell.groupValues[1].equals("h", ignoreCase = true)
            val cellHtml = cell.groupValues[2]
            val imageSrc = IMG_TAG.find(cellHtml)?.let { img ->
                SRC_ATTR.find(img.value)?.groupValues?.get(1)
            }
            val resolved = imageSrc?.let { resolveReadmeUrl(it, baseUrl, dark) }
            val text = htmlToAnnotated(cellHtml, links)
            ReadmeTableCell(annotated = text, imageUrl = resolved, isHeader = isHeader)
        }.toList()
        ReadmeTableRow(cells = cells, isHeader = cells.any { it.isHeader })
    }.toList()
    val header = rows.firstOrNull { it.isHeader }
    val body = if (header != null) rows.filterNot { it.isHeader } else rows
    return ReadmeBlock.Table(header = header, rows = body)
}

internal fun parseDetails(
    inner: String,
    baseUrl: String?,
    links: ReadmeLinks?,
    dark: Boolean,
): ReadmeBlock.Details {
    val summary = SUMMARY_TAG.find(inner)
        ?.let { htmlToAnnotated(it.groupValues[1], links).text.trim() }
        .orEmpty()
    val body = if (summary.isEmpty()) inner else SUMMARY_TAG.replace(inner, "")
    return ReadmeBlock.Details(
        title = summary,
        blocks = parseReadme(body, baseUrl, links, dark),
    )
}

internal fun plainText(html: String): String =
    runCatching { Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString() }
        .getOrDefault("")
        .trim()

/** Runs of consecutive badge images flow on one line, like the reference app. */
internal fun groupBadges(blocks: List<ReadmeBlock>): List<ReadmeBlock> {
    val grouped = mutableListOf<ReadmeBlock>()
    val pending = mutableListOf<String>()
    fun flush() {
        if (pending.isNotEmpty()) {
            grouped += ReadmeBlock.Badges(pending.toList())
            pending.clear()
        }
    }
    blocks.forEach { block ->
        if (block is ReadmeBlock.Image && isBadgeUrl(block.url)) {
            pending += block.url
        } else {
            flush()
            grouped += block
        }
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
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
