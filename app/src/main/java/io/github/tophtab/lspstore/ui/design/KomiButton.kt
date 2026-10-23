package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

enum class KomiButtonVariant { Primary, Tonal, Outline, Text, Destructive }

enum class KomiButtonSize { Small, Medium, Large }

private data class ButtonMetrics(
    val height: Dp,
    val horizontal: Dp,
    val font: TextUnit,
    val icon: Dp,
    val gap: Dp,
)

private fun metricsFor(size: KomiButtonSize): ButtonMetrics = when (size) {
    KomiButtonSize.Small -> ButtonMetrics(34.dp, 14.dp, 12.sp, 15.dp, 7.dp)
    KomiButtonSize.Medium -> ButtonMetrics(44.dp, 20.dp, 13.5.sp, 18.dp, 9.dp)
    KomiButtonSize.Large -> ButtonMetrics(54.dp, 26.dp, 16.sp, 22.dp, 10.dp)
}

@Composable
fun KomiButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    variant: KomiButtonVariant = KomiButtonVariant.Primary,
    size: KomiButtonSize = KomiButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val metrics = metricsFor(size)
    val active = enabled && !loading
    val contentPadding = PaddingValues(horizontal = metrics.horizontal)
    val layout = modifier
        .heightIn(min = metrics.height)
        .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)

    val content: @Composable RowScope.() -> Unit = {
        ButtonContent(
            label = label,
            contentColor = LocalContentColor.current,
            metrics = metrics,
            loading = loading,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
    }

    when (variant) {
        KomiButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = layout,
            enabled = active,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(),
            content = content,
        )

        KomiButtonVariant.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = layout,
            enabled = active,
            contentPadding = contentPadding,
            colors = ButtonDefaults.filledTonalButtonColors(),
            content = content,
        )

        KomiButtonVariant.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = layout,
            enabled = active,
            contentPadding = contentPadding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = content,
        )

        KomiButtonVariant.Text -> TextButton(
            onClick = onClick,
            modifier = layout,
            enabled = active,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = content,
        )

        KomiButtonVariant.Destructive -> Button(
            onClick = onClick,
            modifier = layout,
            enabled = active,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = content,
        )
    }
}

/**
 * Page-level action in the bottom-right corner. Same primary-container colours
 * as komi-store's classic extended FAB, with a label that can carry a live
 * count ("Update all (4)").
 */
@Composable
fun KomiExtendedFab(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        text = {
            KomiText(
                text = label,
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 13.5.sp,
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun RowScope.ButtonContent(
    label: String,
    contentColor: Color,
    metrics: ButtonMetrics,
    loading: Boolean,
    leadingIcon: ImageVector?,
    trailingIcon: ImageVector?,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(metrics.icon),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            Spacer(Modifier.width(metrics.gap))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(metrics.icon))
            Spacer(Modifier.width(metrics.gap))
        }
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            color = contentColor,
            fontSize = metrics.font,
            maxLines = 1,
        )
        if (trailingIcon != null && !loading) {
            Spacer(Modifier.width(metrics.gap))
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(metrics.icon))
        }
    }
}
