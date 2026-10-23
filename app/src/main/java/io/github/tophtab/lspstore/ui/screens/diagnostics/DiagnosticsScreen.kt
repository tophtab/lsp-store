@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.diagnostics_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        KomiConstrainedContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
            ) {
                KomiSettingsSectionHead(stringResource(R.string.diagnostics_app))
                KomiSettingsGroup {
                    InfoRow(stringResource(R.string.diagnostics_version), state.appVersion)
                    InfoRow(stringResource(R.string.diagnostics_android), state.androidVersion)
                    InfoRow(stringResource(R.string.diagnostics_device), state.device, last = true)
                }

                KomiSettingsSectionHead(stringResource(R.string.diagnostics_modules))
                KomiSettingsGroup {
                    InfoRow(stringResource(R.string.diagnostics_installed), state.installedModules.toString())
                    InfoRow(stringResource(R.string.diagnostics_repo), state.repoModules.toString(), last = true)
                }

                KomiSettingsSectionHead(stringResource(R.string.diagnostics_storage))
                KomiSettingsGroup {
                    InfoRow(stringResource(R.string.diagnostics_index_cache), formatBytes(state.indexCacheSize))
                    InfoRow(stringResource(R.string.diagnostics_downloads), formatBytes(state.downloadsSize), last = true)
                }

                KomiSettingsSectionHead(stringResource(R.string.diagnostics_settings))
                KomiSettingsGroup {
                    InfoRow(stringResource(R.string.settings_appearance), state.theme)
                    InfoRow(stringResource(R.string.settings_root), state.rootInstall.toString())
                    InfoRow(stringResource(R.string.settings_translation_provider), state.translationProvider, last = true)
                }

                KomiSettingsSectionHead(stringResource(R.string.diagnostics_feedback))
                KomiSettingsGroup {
                    Column(modifier = Modifier.padding(16.dp)) {
                        KomiText(
                            text = stringResource(R.string.diagnostics_feedback_desc),
                            role = KomiTextRole.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KomiButton(
                                onClick = viewModel::copyDiagnostics,
                                label = stringResource(R.string.diagnostics_copy),
                                variant = KomiButtonVariant.Outline,
                                size = KomiButtonSize.Small,
                            )
                            KomiButton(
                                onClick = viewModel::shareDiagnostics,
                                label = stringResource(R.string.diagnostics_share),
                                variant = KomiButtonVariant.Outline,
                                size = KomiButtonSize.Small,
                            )
                        }
                    }
                }

                KomiSettingsSectionHead(stringResource(R.string.licenses_title))
                KomiSettingsGroup {
                    KomiSettingsRow(
                        title = stringResource(R.string.licenses_title),
                        subtitle = stringResource(R.string.licenses_intro),
                        onClick = onOpenLicenses,
                        last = true,
                    )
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
            KomiText(
                text = label,
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            KomiText(
                text = value,
                role = KomiTextRole.Mono,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        if (!last) {
            KomiDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(java.util.Locale.getDefault(), "%.1f MB", mb) else String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}
