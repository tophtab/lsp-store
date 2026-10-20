package org.lspstore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lspstore.ui.theme.KomiTextRole

/** Chips built on komi's Material Assist/Filter chips. */
@Composable
fun KomiInfoChip(
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    AssistChip(
        onClick = onClick ?: {},
        label = {
            KomiText(
                text = label,
                role = KomiTextRole.Label,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
        modifier = modifier,
        leadingIcon = leadingIcon?.let {
            {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
    )
}

@Composable
fun KomiFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    count: Int? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KomiText(
                    text = label,
                    role = KomiTextRole.Label,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                count?.let {
                    Spacer(Modifier.width(6.dp))
                    KomiText(
                        text = it.toString(),
                        role = KomiTextRole.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        },
        leadingIcon = leadingIcon?.let {
            {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
    )
}

@Composable
fun KomiInputChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onRemove,
        label = {
            KomiText(
                text = label,
                role = KomiTextRole.Label,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
        modifier = modifier,
        trailingIcon = {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
        },
    )
}

@Composable
fun KomiChipRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
