@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.components.ActionSheetRow
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun MoreActionsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun ModuleActionsSheet(
    title: String,
    repoPackageName: String?,
    isUpdateIgnored: Boolean,
    onDismiss: () -> Unit,
    onChooseVariant: (() -> Unit)?,
    onSkipVersion: (() -> Unit)? = null,
    onToggleIgnoreUpdate: (() -> Unit)?,
    preReleasesEnabled: Boolean? = null,
    onTogglePreReleases: (() -> Unit)? = null,
    onUninstall: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Landscape leaves little height for the sheet; scroll instead of
        // letting the last actions fall off the bottom edge.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            KomiText(
                text = title,
                role = KomiTextRole.Title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repoPackageName != null && onChooseVariant != null) {
                InstalledActionRow(
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.installed_choose_variant),
                ) {
                    onDismiss()
                    onChooseVariant()
                }
            }
            if (preReleasesEnabled != null && onTogglePreReleases != null) {
                InstalledActionRow(
                    icon = Icons.Filled.Science,
                    label = stringResource(R.string.installed_pre_releases),
                    checked = preReleasesEnabled,
                ) {
                    onDismiss()
                    onTogglePreReleases()
                }
            }
            if (onSkipVersion != null) {
                InstalledActionRow(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.detail_skip_version),
                ) {
                    onDismiss()
                    onSkipVersion()
                }
            }
            if (repoPackageName != null && onToggleIgnoreUpdate != null) {
                InstalledActionRow(
                    icon = if (isUpdateIgnored) Icons.Filled.Restore else Icons.Filled.Block,
                    label = stringResource(
                        if (isUpdateIgnored) {
                            R.string.installed_unignore_update
                        } else {
                            R.string.installed_ignore_update
                        },
                    ),
                ) {
                    onDismiss()
                    onToggleIgnoreUpdate()
                }
            }
            InstalledActionRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.detail_uninstall),
                tint = MaterialTheme.colorScheme.error,
            ) {
                onDismiss()
                onUninstall()
            }
        }
    }
}

@Composable
private fun InstalledActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    ActionSheetRow(
        icon = icon,
        label = label,
        tint = tint,
        checked = checked,
        horizontalPadding = 20.dp,
        fillLabel = true,
        onClick = onClick,
    )
}
