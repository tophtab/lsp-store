@file:OptIn(ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.theme.LocalKomiType

/**
 * The hero: a tinted card with the owner handle, oversized title, summary and
 * install controls. The LSPosed repository has no fork/issue/license data, so
 * only the parameters it does expose are shown.
 */
@Composable
internal fun DetailHero(
    module: io.github.tophtab.lspstore.domain.model.Module,
    summary: String,
    scope: List<String>,
    collaborators: List<String>,
    pending: Boolean,
    updateAvailable: Boolean,
    latestVersion: ModuleVersion?,
    onOpenAuthor: (String) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val shape = MaterialTheme.shapes.medium
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val headerSurface = lerp(surface, accent, if (dark) 0.10f else 0.06f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(headerSurface)
            .border(1.5.dp, outline, shape),
    ) {
        Column {
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KomiText(
                    text = module.contributor ?: "LSPosed",
                    role = KomiTextRole.Label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(enabled = module.contributor != null) {
                            module.contributor?.let(onOpenAuthor)
                        },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = module.title,
                    style = LocalKomiType.current.styleFor(KomiTextRole.Display).copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 30.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                        minFontSize = 18.sp,
                        maxFontSize = 30.sp,
                        stepSize = 1.sp,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            module.lastPublishedAt?.let { updated ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                        modifier = Modifier.size(15.dp),
                    )
                    KomiText(
                        text = stringResource(
                            R.string.module_updated_relative,
                            formatRelativeTime(updated),
                        ),
                        role = KomiTextRole.Label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                        uppercase = false,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            KomiText(
                text = summary,
                role = KomiTextRole.Body,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            // Plain "installed" is redundant here (the install button says
            // Reinstall); only the states that need attention keep a pill.
            if (pending || updateAvailable) {
                HeroStatusPill(
                    pending = pending,
                    latestVersion = latestVersion?.versionName,
                    latestCode = latestVersion?.versionCode,
                )
            }

            if (scope.isNotEmpty()) {
                KomiText(
                    text = stringResource(R.string.detail_scope),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    scope.forEach { KomiInfoChip(label = it) }
                }
            }

            if (collaborators.isNotEmpty()) {
                KomiText(
                    text = stringResource(R.string.detail_contributors),
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    collaborators.take(8).forEach { collaborator ->
                        val isPrimary = collaborator.equals(module.contributor, ignoreCase = true)
                        KomiText(
                            text = collaborator,
                            role = KomiTextRole.Label,
                            color = if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = if (isPrimary) {
                                Modifier.clickable { onOpenAuthor(collaborator) }
                            } else {
                                Modifier
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Bordered status pill in the hero, kept only for states that need attention. */
@Composable
internal fun HeroStatusPill(
    pending: Boolean,
    latestVersion: String?,
    latestCode: Long?,
) {
    val shape = MaterialTheme.shapes.small
    val label = if (pending) {
        stringResource(R.string.pending_install)
    } else {
        stringResource(
            R.string.detail_update_available,
            latestVersion ?: "?",
            latestCode ?: 0L,
        )
    }
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer, shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            uppercase = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
