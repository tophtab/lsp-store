@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.design.KomiChipRow
import io.github.tophtab.lspstore.ui.design.KomiDiamond
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiStarValue
import io.github.tophtab.lspstore.ui.design.KomiStat
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/**
 * Full-width module card, modelled on komi's repo card: icon tile, title,
 * owner/package line, two-line summary, chips, then a divider and a stats row.
 */
@Composable
fun ModuleCard(
    module: Module,
    installed: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KomiSurface(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(15.dp),
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Feed and search cards never show a launcher icon: the LSPosed
                // repository only exposes the author's avatar, and swapping in
                // the installed APK icon made installed modules look different
                // from the rest of the feed. The Installed tab keeps the APK
                // icon. The installed state is still flagged by the chip below.
                Column(modifier = Modifier.weight(1f)) {
                    KomiText(
                        text = module.title,
                        role = KomiTextRole.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KomiText(
                            text = "@${module.contributor ?: "LSPosed"}",
                            role = KomiTextRole.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        KomiDiamond()
                        KomiText(
                            text = module.packageName,
                            role = KomiTextRole.Mono,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            module.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                KomiText(
                    text = summary,
                    role = KomiTextRole.Body,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 11.dp),
                )
            }

            // Version chips are intentionally omitted: the LSPosed module
            // version is shown on the detail page, not in the feed.
            if (installed) {
                FlowRow(
                    modifier = Modifier.padding(top = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KomiInfoChip(stringResource(R.string.label_installed))
                }
            }

            KomiDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KomiStarValue(module.stars)
                    formatRelativeTime(module.updatedAt).takeIf { it.isNotBlank() }?.let { relative ->
                        KomiStat(
                            icon = Icons.Outlined.Schedule,
                            value = stringResource(R.string.module_updated_relative, relative),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Compact card kept for horizontal shelves. */
@Composable
fun ModuleCardCompact(
    module: Module,
    installed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KomiSurface(
        modifier = modifier.width(196.dp),
        onClick = onClick,
        contentPadding = PaddingValues(13.dp),
    ) {
        Column {
            ModuleAvatar(
                module,
                size = 40,
            )
            Spacer(Modifier.height(9.dp))
            KomiText(
                text = module.title,
                role = KomiTextRole.Title,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            KomiText(
                text = module.packageName,
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (installed) {
                    KomiInfoChip(stringResource(R.string.label_installed))
                }
                module.latestRelease?.let { KomiInfoChip(it.versionName) }
            }
            KomiDivider(
                modifier = Modifier.padding(top = 9.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Row(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KomiStarValue(module.stars)
                formatRelativeTime(module.updatedAt).takeIf { it.isNotBlank() }?.let {
                    KomiText(
                        text = it,
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun KomiChipSpacer() {
    Spacer(Modifier.width(6.dp))
}
