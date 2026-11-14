package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.compactCount
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    KomiText(
        text = title,
        role = KomiTextRole.Title,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        uppercase = false,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}

/** komi's install log block: a mono list of the download / install steps. */
@Composable
internal fun InstallLogCard(lines: List<String>) {
    KomiSurface(contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                KomiText(
                    text = "\u2022 $line",
                    role = KomiTextRole.Mono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun StatsSection(detail: ModuleDetail) {
    val module = detail.module
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(stringResource(R.string.detail_stats))
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem(
                label = stringResource(R.string.detail_stat_stars),
                stat = module.stars.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatItem(
                label = stringResource(R.string.detail_stat_releases),
                stat = detail.releases.size.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatItem(
                label = stringResource(R.string.detail_scope),
                stat = detail.scope.size.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
internal fun StatItem(label: String, stat: Long, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            uppercase = false,
        )
        KomiText(
            text = compactCount(stat.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
            role = KomiTextRole.Title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            uppercase = false,
        )
    }
}

@Composable
internal fun WhatsNewCard(
    release: Release,
    notes: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val needsExpansion = notes.length > 320
    val collapsedHeight = 220.dp
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KomiText(
                text = release.version?.versionName ?: release.tagName,
                role = KomiTextRole.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                uppercase = false,
            )
            KomiText(
                text = formatRelativeTime(release.publishedAt),
                role = KomiTextRole.Label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                uppercase = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        KomiSurface(contentPadding = PaddingValues(14.dp)) {
            Box {
                Box(
                    modifier = if (!expanded && needsExpansion) {
                        Modifier.height(collapsedHeight).clipToBounds()
                    } else {
                        Modifier
                    },
                ) {
                    KomiText(
                        text = notes,
                        role = KomiTextRole.Body,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                    )
                }
                if (!expanded && needsExpansion) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        if (needsExpansion) {
            Spacer(Modifier.height(12.dp))
            val shape = MaterialTheme.shapes.small
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KomiText(
                    text = stringResource(if (expanded) R.string.show_less else R.string.read_more),
                    role = KomiTextRole.Title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                    uppercase = false,
                )
                if (!expanded) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
