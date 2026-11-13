package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.update.UpdateScheduler
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiCompactSegmented
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiSettingsSwitchRow

@Composable
internal fun DownloadSettingsGroup(
    state: SettingsUiState,
    onPickLocation: () -> Unit,
    onResetLocation: () -> Unit,
    onMaxDownloadsChange: (Int) -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_downloads))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(R.string.settings_download_location),
            subtitle = if (state.downloadLocation.isBlank()) {
                stringResource(R.string.settings_download_location_default)
            } else {
                stringResource(
                    R.string.settings_download_location_picked,
                    state.downloadLocationLabel ?: state.downloadLocation,
                )
            },
            onClick = onPickLocation,
            trailing = if (state.downloadLocation.isNotBlank()) {
                {
                    KomiButton(
                        onClick = onResetLocation,
                        label = stringResource(R.string.action_reset),
                        variant = KomiButtonVariant.Text,
                        size = KomiButtonSize.Small,
                    )
                }
            } else {
                null
            },
        )
        KomiSettingsRow(
            title = stringResource(R.string.settings_max_downloads),
            trailing = {
                KomiCompactSegmented(
                    segments = (1..DownloadSlots.MAX_LIMIT).map {
                        KomiSegment(it.toString())
                    },
                    selectedIndex = (state.maxConcurrentDownloads - 1)
                        .coerceIn(0, DownloadSlots.MAX_LIMIT - 1),
                    onSelect = { onMaxDownloadsChange(it + 1) },
                )
            },
            last = true,
        )
    }
}

@Composable
internal fun RootSettingsGroup(
    state: SettingsUiState,
    onRootInstallChange: (Boolean) -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_root_section))
    KomiSettingsGroup {
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_root),
            subtitle = when (state.rootAvailable) {
                true -> stringResource(R.string.settings_root_detected)
                false -> stringResource(R.string.settings_root_unavailable)
                null -> stringResource(R.string.settings_root_desc)
            },
            checked = state.rootInstall,
            onCheckedChange = onRootInstallChange,
            last = true,
        )
    }
}

@Composable
internal fun UpdateSettingsGroup(
    state: SettingsUiState,
    onBackgroundCheckChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCustomInterval: (Int) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_updates_section))
    KomiSettingsGroup {
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_background_check),
            subtitle = stringResource(R.string.settings_background_check_desc),
            checked = state.backgroundCheck,
            onCheckedChange = onBackgroundCheckChange,
            last = !state.backgroundCheck,
        )
        if (state.backgroundCheck) {
            KomiSettingsRow(
                title = stringResource(R.string.settings_check_interval),
                subtitle = state.checkIntervalHours
                    .takeIf { it !in UpdateScheduler.INTERVAL_OPTIONS }
                    ?.let {
                        stringResource(
                            R.string.settings_check_interval_custom_current,
                            it,
                        )
                    }
                    ?: stringResource(R.string.settings_check_interval_desc),
                trailing = {
                    KomiCompactSegmented(
                        segments = UpdateScheduler.INTERVAL_OPTIONS.map {
                            KomiSegment(stringResource(R.string.settings_interval_days, it / 24))
                        },
                        selectedIndex = UpdateScheduler.INTERVAL_OPTIONS
                            .indexOf(state.checkIntervalHours),
                        onSelect = { index ->
                            UpdateScheduler.INTERVAL_OPTIONS.getOrNull(index)
                                ?.let(onIntervalChange)
                                ?: onCustomInterval(state.checkIntervalHours)
                        },
                        onLongSelect = { index ->
                            // Editing an existing custom value keeps
                            // it; a long-press on a preset starts
                            // from that preset's hours.
                            val initial =
                                state.checkIntervalHours.takeIf {
                                    it !in UpdateScheduler.INTERVAL_OPTIONS
                                } ?: UpdateScheduler.INTERVAL_OPTIONS[index]
                            onCustomInterval(initial)
                        },
                    )
                },
            )
            KomiSettingsSwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                subtitle = stringResource(R.string.settings_wifi_only_desc),
                checked = state.wifiOnly,
                onCheckedChange = onWifiOnlyChange,
            )
        }
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_auto_update),
            subtitle = if (state.rootInstall) {
                stringResource(R.string.settings_auto_update_desc)
            } else {
                stringResource(R.string.settings_auto_update_needs_root)
            },
            checked = state.autoUpdateRoot,
            onCheckedChange = onAutoUpdateChange,
            enabled = state.rootInstall,
            last = true,
        )
    }
}
