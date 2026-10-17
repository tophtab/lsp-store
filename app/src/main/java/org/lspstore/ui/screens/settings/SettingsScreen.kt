@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.ui.LocalAppContainer

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
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
            SectionTitle("Update channel")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateChannel.entries.forEach { channel ->
                    FilterChip(
                        selected = state.channel == channel,
                        onClick = { viewModel.setChannel(channel) },
                        label = { Text(channel.label) },
                    )
                }
            }
            Text(
                text = "Stable follows normal releases, Beta follows pre-releases, Nightly follows CI snapshot builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionTitle("Sort modules by")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { order ->
                    FilterChip(
                        selected = state.sortOrder == order,
                        onClick = { viewModel.setSortOrder(order) },
                        label = { Text(order.label) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle("Background checks")
            SwitchRow(
                title = "Check for updates in the background",
                subtitle = "Periodically refresh the module index and notify about updates",
                checked = state.backgroundCheck,
                onCheckedChange = viewModel::setBackgroundCheck,
            )
            if (state.backgroundCheck) {
                Text("Check interval", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6L, 12L, 24L, 48L).forEach { hours ->
                        FilterChip(
                            selected = state.intervalHours == hours,
                            onClick = { viewModel.setIntervalHours(hours) },
                            label = { Text("${hours}h") },
                        )
                    }
                }
            }
            SwitchRow(
                title = "Auto-update when rooted",
                subtitle = "Silently install updates in the background using su",
                checked = state.autoUpdate,
                onCheckedChange = viewModel::setAutoUpdate,
            )

            SwitchRow(
                title = "Root silent install",
                subtitle = when (state.rootAvailable) {
                    true -> "su detected, APKs install silently"
                    false -> "su not available on this device"
                    null -> "Install and update APKs silently via su"
                },
                checked = state.rootInstall,
                onCheckedChange = viewModel::setRootInstall,
            )

            HorizontalDivider()

            SectionTitle("Storage")
            Text(
                text = "Downloaded APKs: ${formatBytes(state.downloadsSize)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = viewModel::clearCache) { Text("Clear cached index and APKs") }

            HorizontalDivider()

            SectionTitle("About")
            Text("LSPosed Store 0.1.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Module data is sourced from modules.lsposed.org (GitHub Xposed-Modules-Repo).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
}
