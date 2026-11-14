@file:OptIn(ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/**
 * Parse on a worker and retain the page's result so lazy scroll restoration
 * never starts with an empty item count when returning to this screen.
 */
@Composable
internal fun rememberReadmeBlocks(html: String?, baseUrl: String?, document: ReadmeDocument): List<ReadmeBlock>? {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val inlineCode = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val key = remember(html, baseUrl, dark, accent, inlineCode) {
        ReadmeDocumentKey(html.orEmpty(), baseUrl, dark, accent, inlineCode)
    }
    val parsed = remember(document, key) {
        mutableStateOf(
            if (key.html.isBlank()) emptyList() else document.cached(key) ?: document.previousContent(key),
        )
    }
    LaunchedEffect(document, key) {
        if (key.html.isNotBlank()) parsed.value = document.load(key)
    }
    return parsed.value
}

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
    val uriHandler = LocalUriHandler.current
    val guarded = remember(uriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("mailto:")) {
                    runCatching { uriHandler.openUri(uri) }
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides guarded) {
        ReadmeBlockBody(block, bodyStyle)
    }
}

@Composable
private fun ReadmeBlockBody(block: ReadmeBlock, bodyStyle: TextStyle) {
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
                    modifier = Modifier.height(18.dp).widthIn(max = 200.dp),
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
                val imageUrl = cell.imageUrl
                if (imageUrl != null) {
                    val caption = cell.captionText
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = caption.ifBlank { null },
                        contentScale = ContentScale.Fit,
                        modifier = if (isBadgeUrl(imageUrl)) {
                            Modifier.height(18.dp).widthIn(max = 160.dp)
                        } else {
                            // Screenshot tables put several portrait images side
                            // by side; fit each one to its cell, not to 18 dp.
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        },
                    )
                    if (caption.isNotEmpty()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
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
    var expanded by rememberSaveable(block.title) { mutableStateOf(false) }
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
