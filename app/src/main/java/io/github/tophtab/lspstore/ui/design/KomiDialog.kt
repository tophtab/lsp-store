package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/**
 * Panel dialog matching komi's `KomiDialog`: a [KomiSurface] panel with an
 * optional title, free-form content and text buttons along the bottom edge.
 *
 * Set [scrollable] when the content can be taller than a short window (a
 * landscape phone, a long candidate list): the body then takes the height left
 * between the title and the buttons and scrolls inside it, so the buttons stay
 * on screen.
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
    scrollable: Boolean = false,
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
                if (scrollable) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                } else {
                    content()
                }
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
