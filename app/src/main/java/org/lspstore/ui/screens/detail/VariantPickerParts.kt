@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package org.lspstore.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.lspstore.R
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.variant.ScoredAsset
import org.lspstore.domain.variant.VariantResolver
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.design.KomiDialog
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.theme.KomiTextRole
import org.lspstore.ui.variant.variantTags

@Composable
internal fun VariantPickerDialog(
    picker: VariantPicker,
    onSelect: (ReleaseAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    val eligible = picker.candidates.filter { it.blocked == null }
    val blocked = picker.candidates.filter { it.blocked != null }
    val bundled = blocked.filter { it.blocked?.kind in BUNDLED_REASON_KINDS }
    val incompatible = blocked.filter { it.blocked?.kind !in BUNDLED_REASON_KINDS }
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.variant_picker_title),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            KomiText(
                text = stringResource(R.string.variant_needs_confirm),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            eligible.forEach { candidate ->
                VariantCandidateRow(
                    candidate = candidate,
                    resolver = resolver,
                    onClick = { onSelect(candidate.asset) },
                )
                KomiDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                )
            }
            if (incompatible.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                KomiText(
                    text = stringResource(R.string.variant_incompatible_group, incompatible.size),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (bundled.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                KomiText(
                    text = stringResource(R.string.variant_bundled_apps, bundled.size),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun VariantCandidateRow(
    candidate: ScoredAsset,
    resolver: VariantResolver,
    onClick: () -> Unit,
) {
    val labels = if (candidate.recommended) {
        resolver.matchLabels(candidate.traits, candidate.meta)
    } else {
        emptyList()
    }
    val tags = variantTags(candidate.traits, candidate.meta)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = candidate.blocked == null, onClick = onClick)
            .padding(vertical = 10.dp),
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
                if (candidate.blocked != null) {
                    VersionBadge(stringResource(R.string.variant_not_applicable))
                }
            }
            KomiText(
                text = candidate.asset.name,
                role = KomiTextRole.Body,
                fontSize = 13.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            KomiText(
                text = stringResource(
                    R.string.detail_downloads,
                    formatSize(candidate.asset.size),
                    candidate.asset.downloadCount,
                ),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            if (tags.isNotEmpty()) {
                KomiText(
                    text = tags.joinToString(" · "),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (labels.isNotEmpty()) {
                KomiText(
                    text = stringResource(
                        R.string.variant_reason_matches_device,
                        labels.joinToString(", "),
                    ),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        Icon(Icons.Filled.Download, contentDescription = null)
    }
}
