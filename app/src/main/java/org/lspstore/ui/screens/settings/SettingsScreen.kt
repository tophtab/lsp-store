@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.labelRes

@Composable
fun SettingsScreen(
    onOpenFavourites: () -> Unit,
    onOpenRecentlyViewed: () -> Unit,
    onOpenHidden: () -> Unit,
    onOpenSkipped: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_amoled),
                subtitle = stringResource(R.string.settings_amoled_desc),
                checked = state.amoled,
                onCheckedChange = viewModel::setAmoled,
            )
            SwitchRow(
                title = stringResource(R.string.settings_dynamic),
                subtitle = stringResource(R.string.settings_dynamic_desc),
                checked = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_update_channel))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateChannel.entries.forEach { channel ->
                    FilterChip(
                        selected = state.channel == channel,
                        onClick = { viewModel.setChannel(channel) },
                        label = { Text(stringResource(channel.labelRes())) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_update_channel_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_sort))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { order ->
                    FilterChip(
                        selected = state.sortOrder == order,
                        onClick = { viewModel.setSortOrder(order) },
                        label = { Text(stringResource(order.labelRes())) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_background))
            SwitchRow(
                title = stringResource(R.string.settings_background_toggle),
                subtitle = stringResource(R.string.settings_background_desc),
                checked = state.backgroundCheck,
                onCheckedChange = viewModel::setBackgroundCheck,
            )
            if (state.backgroundCheck) {
                Text(stringResource(R.string.settings_interval), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6L, 12L, 24L, 48L).forEach { hours ->
                        FilterChip(
                            selected = state.intervalHours == hours,
                            onClick = { viewModel.setIntervalHours(hours) },
                            label = { Text(stringResource(R.string.settings_interval_hours, hours)) },
                        )
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_auto_update),
                subtitle = stringResource(R.string.settings_auto_update_desc),
                checked = state.autoUpdate,
                onCheckedChange = viewModel::setAutoUpdate,
            )
            SwitchRow(
                title = stringResource(R.string.settings_root),
                subtitle = when (state.rootAvailable) {
                    true -> stringResource(R.string.settings_root_detected)
                    false -> stringResource(R.string.settings_root_unavailable)
                    null -> stringResource(R.string.settings_root_desc)
                },
                checked = state.rootInstall,
                onCheckedChange = viewModel::setRootInstall,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_library))
            NavRow(stringResource(R.string.library_favourites), onOpenFavourites)
            NavRow(stringResource(R.string.library_recently_viewed), onOpenRecentlyViewed)
            NavRow(stringResource(R.string.library_hidden), onOpenHidden)
            NavRow(stringResource(R.string.library_skipped), onOpenSkipped)

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_backup))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("lspstore-library.json") }) {
                    Text(stringResource(R.string.action_export))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                ) {
                    Text(stringResource(R.string.action_import))
                }
            }
            Text(
                text = stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_storage))
            Text(
                text = stringResource(R.string.settings_downloaded, formatBytes(state.downloadsSize)),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = viewModel::clearCache) {
                Text(stringResource(R.string.settings_clear_cache))
            }

            HorizontalDivider()

            NavRow(stringResource(R.string.settings_about), onOpenAbout)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(0.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
}
