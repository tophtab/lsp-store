@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.diagnostics

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.ui.LocalAppContainer

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section(stringResource(R.string.diagnostics_app))
            InfoRow(stringResource(R.string.diagnostics_version), state.appVersion)
            InfoRow(stringResource(R.string.diagnostics_android), state.androidVersion)
            InfoRow(stringResource(R.string.diagnostics_device), state.device)

            HorizontalDivider()
            Section(stringResource(R.string.diagnostics_modules))
            InfoRow(stringResource(R.string.diagnostics_installed), state.installedModules.toString())
            InfoRow(stringResource(R.string.diagnostics_repo), state.repoModules.toString())

            HorizontalDivider()
            Section(stringResource(R.string.diagnostics_storage))
            InfoRow(stringResource(R.string.diagnostics_index_cache), formatBytes(state.indexCacheSize))
            InfoRow(stringResource(R.string.diagnostics_downloads), formatBytes(state.downloadsSize))

            HorizontalDivider()
            Section(stringResource(R.string.diagnostics_settings))
            InfoRow(stringResource(R.string.settings_update_channel), state.channel)
            InfoRow(stringResource(R.string.settings_appearance), state.theme)
            InfoRow(stringResource(R.string.settings_background_toggle), state.backgroundCheck.toString())
            InfoRow(stringResource(R.string.settings_root), state.rootInstall.toString())
            InfoRow(stringResource(R.string.settings_proxy_enable), state.proxyEnabled.toString())
            InfoRow(stringResource(R.string.settings_translation_provider), state.translationProvider)

            HorizontalDivider()
            Section(stringResource(R.string.diagnostics_feedback))
            Text(
                text = stringResource(R.string.diagnostics_feedback_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::copyDiagnostics) {
                    Text(stringResource(R.string.diagnostics_copy))
                }
                OutlinedButton(onClick = viewModel::shareDiagnostics) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = onOpenLicenses, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.licenses_title))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
}
