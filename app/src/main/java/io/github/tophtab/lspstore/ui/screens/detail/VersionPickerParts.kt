@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UnfoldMore
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiFilterChip
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import io.github.tophtab.lspstore.ui.variant.variantTags

/**
 * komi's channel row above the resource picker: stable, pre-release and all.
 *
 * A stream the module never published is shown greyed out instead of hidden, so
 * the row keeps its shape and the reason for the missing builds stays visible.
 */
@Composable
internal fun ReleaseChannelRow(
    channel: ReleaseChannel,
    hasPreReleases: Boolean,
    onSelect: (ReleaseChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelButton(
            label = stringResource(R.string.detail_stable),
            selected = channel == ReleaseChannel.STABLE,
            onClick = { onSelect(ReleaseChannel.STABLE) },
            modifier = Modifier.weight(1f),
        )
        ChannelButton(
            label = stringResource(R.string.detail_pre_releases),
            selected = channel == ReleaseChannel.PRE_RELEASE,
            enabled = hasPreReleases,
            onClick = { onSelect(ReleaseChannel.PRE_RELEASE) },
            modifier = Modifier.weight(1f),
        )
        ChannelButton(
            label = stringResource(R.string.detail_all),
            selected = channel == ReleaseChannel.ALL,
            onClick = { onSelect(ReleaseChannel.ALL) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChannelButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val background = when {
        selected -> colors.primary
        enabled -> colors.surface
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        selected -> colors.onPrimary
        enabled -> colors.onSurface
        else -> colors.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = 1.dp,
                color = if (enabled) colors.outline else colors.outlineVariant,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            color = foreground,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** komi's header picker row: the resource picker takes the wider slot, versions the narrower one. */
@Composable
internal fun VersionAssetPickerRow(
    selectedRelease: Release?,
    selectedAsset: ReleaseAsset?,
    apkCount: Int,
    onOpenVersions: () -> Unit,
    onOpenAssets: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerField(
            label = stringResource(R.string.variant_picker_title),
            value = selectedAsset?.name ?: if (apkCount > 1) {
                // Nothing is preselected until the resolver (or the user) decides.
                stringResource(R.string.variant_field_choose, apkCount)
            } else {
                stringResource(R.string.detail_no_apk)
            },
            onClick = onOpenAssets,
            enabled = selectedRelease?.apkAssets?.isNotEmpty() == true,
            modifier = Modifier.weight(0.65f),
        )
        PickerField(
            label = stringResource(R.string.detail_releases),
            value = selectedRelease?.version?.versionName ?: selectedRelease?.tagName
                ?: stringResource(R.string.detail_no_releases),
            subtitle = selectedRelease?.name?.takeIf { it != selectedRelease.tagName },
            onClick = onOpenVersions,
            enabled = true,
            modifier = Modifier.weight(0.35f),
        )
    }
}

@Composable
internal fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp),
            maxLines = 1,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .heightIn(min = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = value,
                    role = KomiTextRole.Stamp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    uppercase = false,
                )
                subtitle?.let {
                    KomiText(
                        text = it,
                        role = KomiTextRole.Body,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

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

@Composable
internal fun AssetSheetContent(
    candidates: List<ScoredAsset>,
    selectedAsset: ReleaseAsset?,
    onSelect: (ReleaseAsset) -> Unit,
) {
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    val eligible = candidates.filter { it.blocked == null }
    val blocked = candidates.filter { it.blocked != null }
    val bundled = blocked.filter { it.blocked?.kind in BUNDLED_REASON_KINDS }
    val incompatible = blocked.filter { it.blocked?.kind !in BUNDLED_REASON_KINDS }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        KomiText(
            text = stringResource(R.string.variant_picker_title),
            role = KomiTextRole.Title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 6.dp),
            uppercase = false,
        )
        Spacer(Modifier.height(8.dp))
        if (candidates.isEmpty()) {
            KomiText(
                text = stringResource(R.string.detail_no_apk),
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (eligible.size > 1 && eligible.none { it.recommended }) {
                    item(key = "confirm") {
                        KomiText(
                            text = stringResource(R.string.variant_needs_confirm),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                items(eligible, key = { it.asset.name }) { candidate ->
                    AssetRow(
                        candidate = candidate,
                        resolver = resolver,
                        isSelected = candidate.asset.name == selectedAsset?.name,
                        onClick = { onSelect(candidate.asset) },
                    )
                    KomiDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
                if (incompatible.isNotEmpty()) {
                    item(key = "incompatible_header") {
                        KomiText(
                            text = stringResource(R.string.variant_incompatible_group, incompatible.size),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(incompatible, key = { "incompatible:${it.asset.name}" }) { candidate ->
                        AssetRow(
                            candidate = candidate,
                            resolver = resolver,
                            isSelected = false,
                            onClick = null,
                        )
                        KomiDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }
                if (bundled.isNotEmpty()) {
                    item(key = "bundled_header") {
                        KomiText(
                            text = stringResource(R.string.variant_bundled_apps, bundled.size),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(bundled, key = { "blocked:${it.asset.name}" }) { candidate ->
                        AssetRow(
                            candidate = candidate,
                            resolver = resolver,
                            isSelected = false,
                            onClick = null,
                        )
                        KomiDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun AssetRow(
    candidate: ScoredAsset,
    resolver: VariantResolver,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val asset = candidate.asset
    val enabled = onClick != null && candidate.blocked == null
    val labels = if (candidate.recommended) {
        resolver.matchLabels(candidate.traits, candidate.meta)
    } else {
        emptyList()
    }
    val tags = variantTags(candidate.traits, candidate.meta)
    val matchCaption = if (labels.isEmpty()) {
        null
    } else {
        stringResource(R.string.variant_reason_matches_device, labels.joinToString(", "))
    }
    val caption = when {
        candidate.blocked != null -> context.variantReasonText(candidate.blocked)
        else -> matchCaption
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick ?: {})
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (candidate.recommended) {
                    VersionBadge(stringResource(R.string.variant_recommended))
                }
                if (candidate.traits.debugName) {
                    VersionBadge(stringResource(R.string.variant_debug_badge))
                }
            }
            KomiText(
                text = asset.name,
                role = KomiTextRole.Stamp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                uppercase = false,
                modifier = Modifier.fillMaxWidth(),
            )
            KomiText(
                text = stringResource(
                    R.string.detail_downloads,
                    formatSize(asset.size),
                    asset.downloadCount,
                ),
                role = KomiTextRole.Body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tags.isNotEmpty()) {
                KomiText(
                    text = tags.joinToString(" · "),
                    role = KomiTextRole.Label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            caption?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            isSelected -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }

            candidate.recommended -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
