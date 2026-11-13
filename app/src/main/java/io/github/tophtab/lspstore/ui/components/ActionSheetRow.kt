package io.github.tophtab.lspstore.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/** An optional action and check mark, with the spacing owned by each sheet. */
@Composable
internal fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    checked: Boolean = false,
    horizontalPadding: Dp = 16.dp,
    fillLabel: Boolean = false,
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val contentColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
        KomiText(
            text = label,
            role = KomiTextRole.Body,
            color = contentColor,
            maxLines = maxLines,
            modifier = if (fillLabel) Modifier.weight(1f) else Modifier,
        )
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
