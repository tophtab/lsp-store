package org.lspstore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.lspstore.ui.theme.KomiTextRole

/**
 * Panel dialog matching komi's `KomiDialog`: a [KomiSurface] panel with an
 * optional title, free-form content and text buttons along the bottom edge.
 */
@Composable
fun KomiDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        KomiSurface(
            modifier = modifier.widthIn(max = 420.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                title?.let {
                    KomiText(
                        text = it,
                        role = KomiTextRole.Title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        uppercase = false,
                    )
                }
                content()
                if (confirmLabel != null || dismissLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissLabel != null) {
                            KomiButton(
                                onClick = { onDismiss?.invoke() },
                                label = dismissLabel,
                                variant = KomiButtonVariant.Text,
                                size = KomiButtonSize.Small,
                            )
                        }
                        if (confirmLabel != null) {
                            Spacer(Modifier.width(8.dp))
                            KomiButton(
                                onClick = { onConfirm?.invoke() },
                                label = confirmLabel,
                                variant = KomiButtonVariant.Text,
                                size = KomiButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}
