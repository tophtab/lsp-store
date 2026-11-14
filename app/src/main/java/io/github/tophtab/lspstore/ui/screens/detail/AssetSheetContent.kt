package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.formatByteSize
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.variant.variantCandidateText

@Composable
internal fun AssetSheetContent(
    candidates: List<ScoredAsset>,
    selectedAsset: ReleaseAsset?,
    onSelect: (ReleaseAsset) -> Unit,
) {
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
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
                items(candidates, key = { it.asset.name }) { candidate ->
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
    val asset = candidate.asset
    val enabled = onClick != null
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val text = remember(candidate, resolver, configuration) { context.variantCandidateText(candidate, resolver) }
    val tags = text.tags
    val caption = text.recommendation
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
                text = pluralStringResource(
                    R.plurals.detail_downloads,
                    asset.downloadCount,
                    formatByteSize(asset.size),
                    asset.downloadCount,
                ),
                role = KomiTextRole.Body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tags != null) {
                KomiText(
                    text = tags,
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
