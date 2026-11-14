package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

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
