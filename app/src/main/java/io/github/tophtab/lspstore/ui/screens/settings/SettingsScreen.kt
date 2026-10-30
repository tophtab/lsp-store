@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import io.github.tophtab.lspstore.domain.model.TranslationSettings
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiCompactSegmented
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiFilterChip
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSettingsExpandableRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiSettingsSwitchRow
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.design.topContentWindowInsets
import io.github.tophtab.lspstore.ui.labelRes
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

private val ThemeModeOrder = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)

@Composable
fun SettingsScreen(
    onOpenFavourites: () -> Unit,
    onOpenRecentlyViewed: () -> Unit,
    onOpenSkipped: () -> Unit,
    onOpenImports: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMirrors: () -> Unit,
    reselectTick: Int = 0,
) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var translationExpanded by remember { mutableStateOf(false) }

    var provider by remember(state.translation.provider) { mutableStateOf(state.translation.provider) }
    var targetLang by remember(state.translation.targetLang) { mutableStateOf(state.translation.targetLang) }
    var deeplKey by remember(state.translation.deeplKey) { mutableStateOf(state.translation.deeplKey) }
    var libreUrl by remember(state.translation.libreUrl) { mutableStateOf(state.translation.libreUrl) }
    var libreKey by remember(state.translation.libreKey) { mutableStateOf(state.translation.libreKey) }
    var msKey by remember(state.translation.microsoftKey) { mutableStateOf(state.translation.microsoftKey) }
    var msRegion by remember(state.translation.microsoftRegion) { mutableStateOf(state.translation.microsoftRegion) }

    fun saveTranslation() = viewModel.saveTranslation(
        TranslationSettings(
            provider = provider,
            targetLang = targetLang,
            deeplKey = deeplKey,
            libreUrl = libreUrl,
            libreKey = libreKey,
            microsoftKey = msKey,
            microsoftRegion = msRegion,
        ),
    )

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) scrollState.animateScrollTo(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = topContentWindowInsets(),
        topBar = {
            KomiTopBar(title = stringResource(R.string.nav_settings))
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        KomiConstrainedContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                onSelect = { viewModel.setThemeMode(ThemeModeOrder[it]) },
                            )
                        },
                    )
                    KomiSettingsSwitchRow(
                        title = stringResource(R.string.settings_amoled),
                        subtitle = stringResource(R.string.settings_amoled_desc),
                        checked = state.amoled,
                        onCheckedChange = viewModel::setAmoled,
                    )
                    KomiSettingsSwitchRow(
                        title = stringResource(R.string.settings_dynamic),
                        subtitle = stringResource(R.string.settings_dynamic_desc),
                        checked = state.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                        last = true,
                    )
                }

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
                        onCheckedChange = viewModel::setRootInstall,
                        last = true,
                    )
                }

                KomiSettingsSectionHead(stringResource(R.string.settings_translation))
                KomiSettingsGroup {
                    KomiSettingsExpandableRow(
                        title = stringResource(R.string.settings_translation_provider),
                        subtitle = stringResource(provider.labelRes()),
                        expanded = translationExpanded,
                        onToggle = { translationExpanded = !translationExpanded },
                        last = true,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TranslationProvider.entries.forEach { entry ->
                                    KomiFilterChip(
                                        label = stringResource(entry.labelRes()),
                                        selected = provider == entry,
                                        onClick = {
                                            provider = entry
                                            saveTranslation()
                                        },
                                    )
                                }
                            }
                            KomiTextField(
                                value = targetLang,
                                onValueChange = { targetLang = it.take(5) },
                                label = stringResource(R.string.settings_translation_target),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            when (provider) {
                                TranslationProvider.GOOGLE -> KomiText(
                                    text = stringResource(R.string.settings_translation_note),
                                    role = KomiTextRole.Label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )

                                TranslationProvider.DEEPL -> KomiTextField(
                                    value = deeplKey,
                                    onValueChange = { deeplKey = it },
                                    label = stringResource(R.string.settings_translation_deepl_key),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                TranslationProvider.LIBRE_TRANSLATE -> {
                                    KomiTextField(
                                        value = libreUrl,
                                        onValueChange = { libreUrl = it },
                                        label = stringResource(R.string.settings_translation_libre_url),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    KomiText(
                                        text = stringResource(R.string.settings_translation_https_note),
                                        role = KomiTextRole.Label,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                    KomiTextField(
                                        value = libreKey,
                                        onValueChange = { libreKey = it },
                                        label = stringResource(R.string.settings_translation_libre_key),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                TranslationProvider.MICROSOFT -> {
                                    KomiTextField(
                                        value = msKey,
                                        onValueChange = { msKey = it },
                                        label = stringResource(R.string.settings_translation_ms_key),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    KomiTextField(
                                        value = msRegion,
                                        onValueChange = { msRegion = it },
                                        label = stringResource(R.string.settings_translation_ms_region),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                TranslationProvider.DISABLED -> Unit
                            }
                            if (provider != TranslationProvider.DISABLED) {
                                KomiButton(
                                    onClick = { saveTranslation() },
                                    label = stringResource(R.string.action_save),
                                )
                            }
                        }
                    }
                }

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
                        title = stringResource(R.string.library_skipped),
                        onClick = onOpenSkipped,
                    )
                    KomiSettingsRow(
                        title = stringResource(R.string.import_title),
                        onClick = onOpenImports,
                        last = true,
                    )
                }

                KomiSettingsSectionHead(stringResource(R.string.settings_storage))
                KomiSettingsGroup {
                    KomiSettingsRow(
                        title = stringResource(
                            R.string.settings_downloaded,
                            formatBytes(state.downloadsSize),
                        ),
                        subtitle = stringResource(R.string.settings_clear_apks),
                        trailing = {
                            KomiButton(
                                onClick = viewModel::clearDownloadedApks,
                                label = stringResource(R.string.action_clear),
                                variant = KomiButtonVariant.Text,
                                size = KomiButtonSize.Small,
                            )
                        },
                    )
                    KomiSettingsRow(
                        title = stringResource(R.string.diagnostics_index_cache) + ": " +
                            formatBytes(state.indexCacheSize),
                        subtitle = stringResource(R.string.settings_clear_index),
                        trailing = {
                            KomiButton(
                                onClick = viewModel::clearIndexCache,
                                label = stringResource(R.string.action_clear),
                                variant = KomiButtonVariant.Text,
                                size = KomiButtonSize.Small,
                            )
                        },
                        last = true,
                    )
                }

                KomiSettingsSectionHead(stringResource(R.string.settings_advanced))
                KomiSettingsGroup {
                    KomiSettingsRow(
                        title = stringResource(R.string.mirror_title),
                        onClick = onOpenMirrors,
                    )
                    KomiSettingsRow(
                        title = stringResource(R.string.diagnostics_title),
                        onClick = onOpenDiagnostics,
                    )
                    KomiSettingsRow(
                        title = stringResource(R.string.settings_about),
                        onClick = onOpenAbout,
                        last = true,
                    )
                }
            }
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
