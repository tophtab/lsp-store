@file:OptIn(ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiFilterChip
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun VersionSheetContent(
    releases: List<Release>,
    selectedRelease: Release?,
    latestRelease: Release?,
    onSelect: (Release) -> Unit,
) {
    val lines = remember(releases) {
        releases.mapNotNull { releaseLine(it) }.distinct()
    }
    var lineFilter by remember(selectedRelease?.tagName, lines) {
        mutableStateOf(selectedRelease?.let(::releaseLine)?.takeIf { it in lines })
    }
    val visibleReleases = remember(releases, lineFilter) {
        val line = lineFilter
        if (line == null) releases else releases.filter { releaseLine(it) == line }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        KomiText(
            text = stringResource(R.string.detail_releases),
            role = KomiTextRole.Title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 6.dp),
            uppercase = false,
        )
        Spacer(Modifier.height(8.dp))
        if (lines.size > 1) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KomiFilterChip(
                    label = stringResource(R.string.detail_all),
                    selected = lineFilter == null,
                    onClick = { lineFilter = null },
                )
                lines.forEach { line ->
                    KomiFilterChip(
                        label = line,
                        selected = lineFilter == line,
                        onClick = { lineFilter = line },
                    )
                }
            }
        }
        if (visibleReleases.isEmpty()) {
            KomiText(
                text = stringResource(R.string.detail_no_releases),
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(visibleReleases, key = { it.tagName.ifBlank { it.hashCode().toString() } }) { release ->
                    VersionRow(
                        release = release,
                        isSelected = release.tagName == selectedRelease?.tagName,
                        isLatest = release.tagName == latestRelease?.tagName,
                        onClick = { onSelect(release) },
                    )
                    KomiDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Major-version release line, e.g. `v2.1.3` -> `v2.x`. */
internal fun releaseLine(release: Release): String? {
    val name = release.version?.versionName ?: release.tagName
    val core = name.trim().removePrefix("v").substringBefore(' ').substringBefore('-')
    val major = core.substringBefore('.')
    if (major.isBlank() || major.toIntOrNull() == null) return null
    return "v$major.x"
}

@Composable
internal fun VersionRow(
    release: Release,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KomiText(
                    text = release.version?.versionName ?: release.tagName,
                    role = KomiTextRole.Stamp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    uppercase = false,
                    maxLines = 1,
                )
                if (isLatest) {
                    VersionBadge(stringResource(R.string.detail_stat_latest))
                }
                if (release.isPrerelease) {
                    VersionBadge(stringResource(R.string.label_beta))
                }
            }
            release.name?.takeIf { it != release.tagName }?.let { name ->
                KomiText(
                    text = name,
                    role = KomiTextRole.Body,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            KomiText(
                text = formatRelativeTime(release.publishedAt),
                role = KomiTextRole.Label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                uppercase = false,
            )
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun VersionBadge(text: String) {
    val shape = MaterialTheme.shapes.small
    KomiText(
        text = text,
        role = KomiTextRole.Label,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        uppercase = false,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
