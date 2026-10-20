package org.lspstore.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The panel primitive: 16dp corner, 1dp outline, a hairline shadow. This is the
 * surface shape used for cards, settings groups and dialogs.
 */
@Composable
fun KomiSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevation: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = color,
            contentColor = contentColor,
            border = border,
            shadowElevation = elevation,
        ) {
            Box(modifier = Modifier.padding(contentPadding)) { content() }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = color,
            contentColor = contentColor,
            border = border,
            shadowElevation = elevation,
        ) {
            Box(modifier = Modifier.padding(contentPadding)) { content() }
        }
    }
}

@Composable
fun KomiDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    thickness: Dp = Dp.Unspecified,
) {
    HorizontalDivider(
        modifier = modifier,
        color = if (color != Color.Unspecified) color
        else MaterialTheme.colorScheme.outlineVariant,
        thickness = if (thickness != Dp.Unspecified) thickness else 1.dp,
    )
}
