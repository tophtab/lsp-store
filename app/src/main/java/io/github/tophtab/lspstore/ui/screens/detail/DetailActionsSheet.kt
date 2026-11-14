@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.detail

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.components.ActionSheetRow

@Composable
internal fun DetailActionsSheet(
    state: ModuleDetailUiState,
    repoPackageName: String,
    onDismiss: () -> Unit,
    onToggleFavourite: () -> Unit,
    onSetModuleIgnored: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.detail_share_module)
    val installedPackage = state.installed?.packageName ?: repoPackageName
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            ActionSheetRow(
                icon = if (state.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                label = stringResource(
                    if (state.isFavourite) R.string.library_remove_favourite else R.string.module_favourite,
                ),
                onClick = { onDismiss(); onToggleFavourite() },
            )
            ActionSheetRow(
                icon = Icons.Filled.Share,
                label = stringResource(R.string.action_share),
                onClick = {
                    onDismiss()
                    state.detail?.module?.let { module ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, module.title)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "${module.title}\n${module.url ?: module.packageName}",
                                    )
                                },
                                shareTitle,
                            ),
                        )
                    }
                },
            )
            if (state.hiddenByKeywords.isNotEmpty()) {
                ActionSheetRow(
                    icon = Icons.Filled.Block,
                    label = stringResource(
                        R.string.detail_hidden_by_keyword,
                        state.hiddenByKeywords.joinToString(", "),
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            ActionSheetRow(
                icon = if (state.isModuleIgnored) Icons.Filled.Restore else Icons.Filled.Block,
                label = stringResource(
                    if (state.isModuleIgnored) R.string.detail_unignore_module
                    else R.string.detail_ignore_module,
                ),
                onClick = {
                    onDismiss()
                    onSetModuleIgnored(!state.isModuleIgnored)
                },
            )
            state.installed?.let {
                ActionSheetRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = stringResource(R.string.detail_open_app),
                    onClick = {
                        onDismiss()
                        context.packageManager.getLaunchIntentForPackage(installedPackage)
                            ?.let { intent -> context.startActivity(intent) }
                    },
                )
                ActionSheetRow(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.detail_app_info),
                    onClick = {
                        onDismiss()
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:$installedPackage".toUri(),
                            ),
                        )
                    },
                )
                ActionSheetRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.detail_uninstall),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        onDismiss()
                        onUninstall()
                    },
                )
            }
        }
    }
}
