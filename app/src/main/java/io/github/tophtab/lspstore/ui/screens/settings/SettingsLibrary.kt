package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.formatByteSize
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead

@Composable
internal fun LibrarySettingsGroup(
    onOpenFavourites: () -> Unit,
    onOpenRecentlyViewed: () -> Unit,
    onOpenIgnoredModules: () -> Unit,
    onOpenSkipped: () -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_library))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(R.string.library_favourites),
            onClick = onOpenFavourites,
        )
        KomiSettingsRow(
            title = stringResource(R.string.library_recently_viewed),
            onClick = onOpenRecentlyViewed,
        )
        KomiSettingsRow(
            title = stringResource(R.string.library_ignored_modules),
            onClick = onOpenIgnoredModules,
        )
        KomiSettingsRow(
            title = stringResource(R.string.library_skipped),
            onClick = onOpenSkipped,
            last = true,
        )
    }
}

@Composable
internal fun StorageSettingsGroup(
    state: SettingsUiState,
    onClearDownloads: () -> Unit,
    onClearIndexCache: () -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_storage))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(
                R.string.settings_downloaded,
                formatByteSize(state.downloadsSize, emptyValue = "0 MB"),
            ),
            subtitle = stringResource(R.string.settings_clear_apks),
            trailing = {
                KomiButton(
                    onClick = onClearDownloads,
                    label = stringResource(R.string.action_clear),
                    variant = KomiButtonVariant.Text,
                    size = KomiButtonSize.Small,
                )
            },
        )
        KomiSettingsRow(
            title = stringResource(R.string.diagnostics_index_cache) + ": " +
                formatByteSize(state.indexCacheSize, emptyValue = "0 MB"),
            subtitle = stringResource(R.string.settings_clear_index),
            trailing = {
                KomiButton(
                    onClick = onClearIndexCache,
                    label = stringResource(R.string.action_clear),
                    variant = KomiButtonVariant.Text,
                    size = KomiButtonSize.Small,
                )
            },
            last = true,
        )
    }
}

@Composable
internal fun BackupSettingsGroup(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_backup))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(R.string.backup_export),
            subtitle = stringResource(R.string.backup_export_desc),
            onClick = onExport,
        )
        KomiSettingsRow(
            title = stringResource(R.string.backup_import),
            subtitle = stringResource(R.string.backup_import_desc),
            onClick = onImport,
            last = true,
        )
    }
}

@Composable
internal fun AdvancedSettingsGroup(
    onOpenMirrors: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_advanced))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(R.string.mirror_title),
            onClick = onOpenMirrors,
        )
        KomiSettingsRow(
            title = stringResource(R.string.settings_about),
            onClick = onOpenAbout,
            last = true,
        )
    }
}
