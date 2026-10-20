@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package org.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.lspstore.R
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.theme.KomiTextRole
import org.lspstore.ui.theme.LocalKomiType

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

/**
 * Readme renderer. The repository returns GitHub-rendered HTML; it is split
 * into headings, code blocks, quotes, lists, tables, foldable details and
 * images so the detail page keeps the markdown hierarchy while staying one
 * scrolling column (a WebView inside a LazyColumn swallows vertical drags).
 */
@Composable
internal fun ReadmeContent(html: String, baseUrl: String?) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val uriHandler = LocalUriHandler.current
    val linkStyles = remember(accent) {
        TextLinkStyles(
            style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
        )
    }
    val inlineCode = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url.orEmpty()
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }
    val links = remember(linkStyles, linkListener, inlineCode) {
        ReadmeLinks(linkStyles, linkListener, inlineCode)
    }
    val blocks = remember(html, baseUrl, dark, links) { parseReadme(html, baseUrl, links, dark) }
    if (blocks.isEmpty()) {
        KomiText(
            text = stringResource(R.string.detail_no_readme),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bodyStyle = LocalKomiType.current.styleFor(KomiTextRole.Body).copy(fontSize = 14.sp)
    ReadmeBlocks(blocks = blocks, bodyStyle = bodyStyle)
}

internal class ReadmeLinks(
    val styles: TextLinkStyles,
    val listener: LinkInteractionListener,
    val inlineCodeBackground: Color,
)

/** Renders a block list; nested lists (details) reuse it with a tighter rhythm. */
@Composable
internal fun ReadmeBlocks(
    blocks: List<ReadmeBlock>,
    bodyStyle: TextStyle,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEach { block -> ReadmeBlockView(block, bodyStyle) }
    }
}

@Composable
internal fun ReadmeBlockView(block: ReadmeBlock, bodyStyle: TextStyle) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (block) {
        is ReadmeBlock.Text -> Text(
            text = block.annotated,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is ReadmeBlock.Heading -> KomiText(
            text = block.annotated.text,
            role = KomiTextRole.Title,
            fontWeight = FontWeight.Bold,
            fontSize = when (block.level) {
                1 -> 24.sp
                2 -> 22.sp
                3 -> 20.sp
                4 -> 18.sp
                else -> 16.sp
            },
            color = MaterialTheme.colorScheme.onBackground,
            uppercase = false,
        )

        is ReadmeBlock.Code -> KomiSurface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentPadding = PaddingValues(12.dp),
        ) {
            KomiText(
                text = block.text,
                role = KomiTextRole.Mono,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ReadmeBlock.Bullet -> ReadmeListItem(
            marker = "\u2022",
            annotated = block.annotated,
            bodyStyle = bodyStyle,
        )

        is ReadmeBlock.Ordered -> ReadmeListItem(
            marker = "${block.number}.",
            annotated = block.annotated,
            bodyStyle = bodyStyle,
        )

        is ReadmeBlock.Quote -> Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onPrimaryContainer),
            )
            Text(
                text = block.annotated,
                style = bodyStyle,
                color = muted,
                modifier = Modifier.weight(1f),
            )
        }

        is ReadmeBlock.Image -> AsyncImage(
            model = block.url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = 320.dp)
                .widthIn(max = 480.dp)
                .clip(MaterialTheme.shapes.small),
        )

        is ReadmeBlock.Badges -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.urls.forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 40.dp).widthIn(max = 220.dp),
                )
            }
        }

        is ReadmeBlock.Table -> ReadmeTableView(block)

        is ReadmeBlock.Details -> ReadmeDetailsView(block, bodyStyle)

        ReadmeBlock.Divider -> KomiDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
    }
}

@Composable
internal fun ReadmeListItem(marker: String, annotated: AnnotatedString, bodyStyle: TextStyle) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = marker,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = annotated,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Table block: bordered panel, a tinted header row and hairline row rules. */
@Composable
internal fun ReadmeTableView(table: ReadmeBlock.Table) {
    val shape = MaterialTheme.shapes.small
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, outline, shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        table.header?.let { row ->
            ReadmeTableRowView(row)
            KomiDivider(color = outline, thickness = 1.dp)
        }
        table.rows.forEachIndexed { index, row ->
            ReadmeTableRowView(row)
            if (index != table.rows.lastIndex) {
                KomiDivider(color = outline.copy(alpha = 0.35f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
internal fun ReadmeTableRowView(row: ReadmeTableRow) {
    Row(modifier = Modifier.fillMaxWidth()) {
        row.cells.forEach { cell ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (cell.imageUrl != null && cell.annotated.text.isBlank()) {
                    AsyncImage(
                        model = cell.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 40.dp).widthIn(max = 160.dp),
                    )
                } else {
                    Text(
                        text = cell.annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        fontWeight = if (row.isHeader) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (row.isHeader) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** GitHub `<details>` block: collapsed by default, like komi's expandable fence. */
@Composable
internal fun ReadmeDetailsView(block: ReadmeBlock.Details, bodyStyle: TextStyle) {
    var expanded by remember(block.title) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, outline, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            KomiText(
                text = block.title.ifBlank { stringResource(R.string.detail_readme_details) },
                role = KomiTextRole.Label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                uppercase = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded && block.blocks.isNotEmpty()) {
            ReadmeBlocks(
                blocks = block.blocks,
                bodyStyle = bodyStyle,
                spacing = 8.dp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

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
