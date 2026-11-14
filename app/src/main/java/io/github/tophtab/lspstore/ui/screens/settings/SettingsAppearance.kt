package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.ui.design.KomiCompactSegmented
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiSettingsSwitchRow
import io.github.tophtab.lspstore.ui.labelRes

private val ThemeModeOrder = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)

@Composable
internal fun AppearanceSettingsGroup(
    state: SettingsUiState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onHideIconChange: (Boolean) -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_appearance))
    KomiSettingsGroup {
        KomiSettingsRow(
            title = stringResource(R.string.settings_theme),
            trailing = {
                KomiCompactSegmented(
                    segments = ThemeModeOrder.map {
                        KomiSegment(stringResource(it.labelRes()))
                    },
                    selectedIndex = ThemeModeOrder.indexOf(state.themeMode),
                    onSelect = { onThemeModeChange(ThemeModeOrder[it]) },
                )
            },
        )
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_amoled),
            subtitle = stringResource(R.string.settings_amoled_desc),
            checked = state.amoled,
            onCheckedChange = onAmoledChange,
        )
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_dynamic),
            subtitle = stringResource(R.string.settings_dynamic_desc),
            checked = state.dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_hide_icon),
            subtitle = stringResource(R.string.settings_hide_icon_desc),
            checked = state.hideLauncherIcon,
            onCheckedChange = onHideIconChange,
            last = true,
        )
    }
}
