package io.github.tophtab.lspstore.ui.screens.installed

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.variant.variantTags

@Composable
internal fun VariantPickerDialog(
    picker: InstalledVariantPicker,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.installed_choose_variant),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            KomiText(
                text = picker.title,
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            KomiText(
                text = stringResource(R.string.variant_picker_subtitle),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            when {
                picker.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                picker.error != null -> KomiText(
                    text = picker.error,
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )

                else -> {
                    val eligible = picker.candidates
                    val current = picker.selectedAssetName
                        ?.takeIf { name -> eligible.any { it.asset.name == name } }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        item {
                            VariantChoiceRow(
                                label = stringResource(R.string.installed_variant_auto),
                                subtitle = stringResource(R.string.installed_variant_auto_subtitle),
                                selected = current == null,
                                onClick = { onSelect(null) },
                            )
                        }
                        items(eligible, key = { it.asset.name }) { candidate ->
                            val labels = if (candidate.recommended) {
                                resolver.matchLabels(candidate.traits, candidate.meta)
                            } else {
                                emptyList()
                            }
                            val reason = labels.takeIf { it.isNotEmpty() }?.let {
                                stringResource(
                                    R.string.variant_reason_matches_device,
                                    it.joinToString(", "),
                                )
                            }
                            val tags = variantTags(candidate.traits, candidate.meta)
                            val subtitle = listOfNotNull(
                                Formatter.formatShortFileSize(context, candidate.asset.size),
                                tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                reason,
                            ).joinToString(" - ")
                            VariantChoiceRow(
                                label = candidate.asset.name,
                                subtitle = subtitle,
                                selected = current == candidate.asset.name,
                                badge = when {
                                    candidate.recommended ->
                                        stringResource(R.string.variant_recommended)
                                    else -> null
                                },
                                onClick = { onSelect(candidate.asset.name) },
                            )
                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun VariantChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = (if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            badge?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            KomiText(
                text = label,
                role = KomiTextRole.Body,
                fontSize = 13.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            subtitle?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
