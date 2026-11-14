package io.github.tophtab.lspstore.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun UninstallConfirmDialog(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    KomiDialog(
        onDismissRequest = onDismiss,
        title = label,
        confirmLabel = stringResource(R.string.detail_uninstall),
        confirmVariant = KomiButtonVariant.Destructive,
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        KomiText(
            text = stringResource(R.string.installed_uninstall_confirm_message, label),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}
