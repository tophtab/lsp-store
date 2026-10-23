package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
fun KomiStat(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = if (hero) MaterialTheme.colorScheme.onPrimaryContainer else tint,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = iconTint,
        )
        KomiText(
            text = value,
            role = KomiTextRole.Body,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun KomiDiamond(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .size(4.dp)
            .rotate(45f)
            .background(color),
    )
}

@Composable
fun KomiStarValue(stars: Int, modifier: Modifier = Modifier) {
    if (stars <= 0) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        KomiText(
            text = compactCount(stars),
            role = KomiTextRole.Body,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> "${(value / 100_000) / 10.0}M"
    value >= 1_000 -> "${(value / 100) / 10.0}k"
    else -> value.toString()
}
