@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.SnackbarMessageEffect
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.versionLabel

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    SnackbarMessageEffect(
        message = state.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.settings_about),
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KomiSurface(
                    modifier = Modifier.padding(top = 8.dp),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(56.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = Color.Transparent,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_launcher_icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                KomiText(
                                    text = stringResource(R.string.app_name),
                                    role = KomiTextRole.Display,
                                    fontSize = 24.sp,
                                    maxLines = 1,
                                )
                                KomiText(
                                    text = stringResource(R.string.about_version, versionLabel),
                                    role = KomiTextRole.Mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        KomiDivider(modifier = Modifier.padding(vertical = 14.dp))
                        KomiText(
                            text = stringResource(R.string.about_body),
                            role = KomiTextRole.Body,
                            fontSize = 13.5.sp,
                        )
                    }
                }

                KomiSurface(
                    contentPadding = PaddingValues(18.dp),
                    onClick = { uriHandler.openUri("https://github.com/tophtab/lsp-store") },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            KomiText(
                                text = stringResource(R.string.about_source_title),
                                role = KomiTextRole.Title,
                                fontSize = 14.sp,
                            )
                            KomiText(
                                text = stringResource(R.string.about_source_body),
                                role = KomiTextRole.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.5.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AboutDiagnostics(
                    state = state,
                    onCopy = viewModel::copyDiagnostics,
                    onShare = viewModel::shareDiagnostics,
                    onOpenLicenses = onOpenLicenses,
                )
            }
        }
    }
}

@Composable
private fun AboutDiagnostics(
    state: DiagnosticsUiState,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column {
        KomiSettingsSectionHead(stringResource(R.string.diagnostics_app))
        KomiSettingsGroup {
            InfoRow(stringResource(R.string.diagnostics_version), state.appVersion)
            InfoRow(stringResource(R.string.diagnostics_android), state.androidVersion)
            InfoRow(stringResource(R.string.diagnostics_device), state.device, last = true)
        }

        KomiSettingsSectionHead(stringResource(R.string.diagnostics_modules))
        KomiSettingsGroup {
            InfoRow(
                stringResource(R.string.diagnostics_installed),
                state.installedModules.toString(),
            )
            InfoRow(
                stringResource(R.string.diagnostics_repo),
                state.repoModules.toString(),
                last = true,
            )
        }

        KomiSettingsSectionHead(stringResource(R.string.diagnostics_storage))
        KomiSettingsGroup {
            InfoRow(
                stringResource(R.string.diagnostics_index_cache),
                formatBytes(state.indexCacheSize),
            )
            InfoRow(
                stringResource(R.string.diagnostics_downloads),
                formatBytes(state.downloadsSize),
                last = true,
            )
        }

        KomiSettingsSectionHead(stringResource(R.string.diagnostics_settings))
        KomiSettingsGroup {
            InfoRow(stringResource(R.string.settings_appearance), state.theme)
            InfoRow(stringResource(R.string.settings_root), state.rootInstall.toString())
            InfoRow(
                stringResource(R.string.settings_translation_provider),
                state.translationProvider,
                last = true,
            )
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
                        onClick = onCopy,
                        label = stringResource(R.string.diagnostics_copy),
                        variant = KomiButtonVariant.Outline,
                        size = KomiButtonSize.Small,
                    )
                    KomiButton(
                        onClick = onShare,
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
    return if (mb >= 1) {
        String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}
