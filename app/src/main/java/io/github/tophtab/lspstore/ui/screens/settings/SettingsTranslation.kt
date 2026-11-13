@file:OptIn(ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import io.github.tophtab.lspstore.domain.model.TranslationSettings
import io.github.tophtab.lspstore.ui.design.KomiFilterChip
import io.github.tophtab.lspstore.ui.design.KomiSettingsExpandableRow
import io.github.tophtab.lspstore.ui.design.KomiSettingsGroup
import io.github.tophtab.lspstore.ui.design.KomiSettingsSectionHead
import io.github.tophtab.lspstore.ui.design.KomiSettingsSwitchRow
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.labelRes
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun TranslationSettingsGroup(
    settings: TranslationSettings,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUpdate: ((TranslationSettings) -> TranslationSettings) -> Unit,
) {
    KomiSettingsSectionHead(stringResource(R.string.settings_translation))
    KomiSettingsGroup {
        KomiSettingsExpandableRow(
            title = stringResource(R.string.settings_translation_provider),
            subtitle = stringResource(settings.provider.labelRes()),
            expanded = expanded,
            onToggle = onToggle,
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
                            selected = settings.provider == entry,
                            onClick = {
                                onUpdate { it.copy(provider = entry) }
                            },
                        )
                    }
                }
                when (settings.provider) {
                    TranslationProvider.GOOGLE -> KomiText(
                        text = stringResource(R.string.settings_translation_note),
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )

                    TranslationProvider.DEEPL -> KomiTextField(
                        value = settings.deeplKey,
                        onValueChange = { value ->
                            onUpdate { it.copy(deeplKey = value) }
                        },
                        label = stringResource(R.string.settings_translation_deepl_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TranslationProvider.LIBRE_TRANSLATE -> {
                        KomiTextField(
                            value = settings.libreUrl,
                            onValueChange = { value ->
                                onUpdate { it.copy(libreUrl = value) }
                            },
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
                            value = settings.libreKey,
                            onValueChange = { value ->
                                onUpdate { it.copy(libreKey = value) }
                            },
                            label = stringResource(R.string.settings_translation_libre_key),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    TranslationProvider.MICROSOFT -> {
                        KomiTextField(
                            value = settings.microsoftKey,
                            onValueChange = { value ->
                                onUpdate { it.copy(microsoftKey = value) }
                            },
                            label = stringResource(R.string.settings_translation_ms_key),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        KomiTextField(
                            value = settings.microsoftRegion,
                            onValueChange = { value ->
                                onUpdate { it.copy(microsoftRegion = value) }
                            },
                            label = stringResource(R.string.settings_translation_ms_region),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        // The target follows the app language, so there is no target
        // field; the only translation choice left is whether a module
        // page translates itself.
        KomiSettingsSwitchRow(
            title = stringResource(R.string.settings_translation_auto),
            subtitle = stringResource(R.string.settings_translation_auto_desc),
            checked = settings.autoTranslate,
            onCheckedChange = { checked ->
                onUpdate { it.copy(autoTranslate = checked) }
            },
            last = true,
        )
    }
}
