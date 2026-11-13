package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.backup.LibraryBackup
import io.github.tophtab.lspstore.data.backup.LibraryRestoreMode
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun CustomIntervalDialog(
    input: String,
    invalid: Boolean,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_check_interval_custom_title),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KomiText(
                text = stringResource(R.string.settings_check_interval_custom_desc),
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            KomiTextField(
                value = input,
                onValueChange = onInputChange,
                label = stringResource(R.string.settings_check_interval_hours_label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            )
            if (invalid) {
                KomiText(
                    text = stringResource(R.string.settings_check_interval_custom_invalid),
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
internal fun LibraryImportDialog(
    backup: LibraryBackup,
    onApply: (LibraryRestoreMode) -> Unit,
    onDismiss: () -> Unit,
) {
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_import_title),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KomiText(
                text = stringResource(
                    R.string.backup_import_summary,
                    pluralStringResource(R.plurals.backup_favourites_count, backup.favourites.size, backup.favourites.size),
                    pluralStringResource(R.plurals.backup_ignored_modules_count, backup.ignoredModules.size, backup.ignoredModules.size),
                    pluralStringResource(R.plurals.backup_skipped_versions_count, backup.skippedUpdates.size, backup.skippedUpdates.size),
                ),
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            KomiText(
                text = stringResource(R.string.backup_import_note),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            KomiButton(
                onClick = { onApply(LibraryRestoreMode.MERGE) },
                label = stringResource(R.string.backup_import_merge),
                fullWidth = true,
            )
            KomiButton(
                onClick = { onApply(LibraryRestoreMode.REPLACE) },
                label = stringResource(R.string.backup_import_replace),
                variant = KomiButtonVariant.Destructive,
                fullWidth = true,
            )
        }
    }
}

@Composable
internal fun HideLauncherIconDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_hide_icon_confirm_title),
        confirmLabel = stringResource(R.string.settings_hide_icon_confirm_action),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        KomiText(
            text = stringResource(R.string.settings_hide_icon_confirm_message),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}
