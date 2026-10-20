package org.lspstore.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lspstore.ui.theme.KomiTextRole

data class KomiSegment(
    val label: String,
    val count: Int? = null,
)

/**
 * Segmented tab row matching komi's chart tabs: 10dp corners and an accent fill
 * on the selected segment.
 */
@Composable
fun KomiSegmented(
    segments: List<KomiSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val selected = index == selectedIndex
            val background = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            }
            val foreground = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val shape = MaterialTheme.shapes.small
            val base = Modifier
                .weight(1f)
                .clip(shape)
                .background(background)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = shape,
                )
                .clickable { onSelect(index) }
                .padding(vertical = 9.dp, horizontal = 6.dp)
            Column(
                modifier = base,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KomiText(
                    text = segment.label,
                    role = KomiTextRole.Label,
                    color = foreground,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                segment.count?.let {
                    KomiText(
                        text = it.toString(),
                        role = KomiTextRole.Mono,
                        color = if (selected) foreground.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Content-sized segmented control for settings rows. Unlike [KomiSegmented]
 * every cell wraps its label, so it can sit in a row's trailing slot next to a
 * title instead of filling the whole width.
 */
@Composable
fun KomiCompactSegmented(
    segments: List<KomiSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = shape,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                KomiText(
                    text = segment.label,
                    role = KomiTextRole.Label,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
