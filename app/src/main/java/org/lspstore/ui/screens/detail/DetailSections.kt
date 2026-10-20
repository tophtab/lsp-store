@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package org.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.lspstore.R
import org.lspstore.data.install.DownloadStatus
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.Release
import org.lspstore.domain.variant.Reason
import org.lspstore.ui.components.ModuleAvatar
import org.lspstore.ui.components.formatRelativeTime
import org.lspstore.ui.design.KomiButton
import org.lspstore.ui.design.KomiButtonSize
import org.lspstore.ui.design.KomiButtonVariant
import org.lspstore.ui.design.KomiInfoChip
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.compactCount
import org.lspstore.ui.theme.KomiTextRole
import org.lspstore.ui.theme.LocalKomiType

@Composable
internal fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    KomiText(
        text = title,
        role = KomiTextRole.Title,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        uppercase = false,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}

/** komi's install log block: a mono list of the download / install steps. */
@Composable
internal fun InstallLogCard(lines: List<String>) {
    KomiSurface(contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                KomiText(
                    text = "\u2022 $line",
                    role = KomiTextRole.Mono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun StatsSection(detail: ModuleDetail) {
    val module = detail.module
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(stringResource(R.string.detail_stats))
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem(
                label = stringResource(R.string.detail_stat_stars),
                stat = module.stars.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatItem(
                label = stringResource(R.string.detail_stat_releases),
                stat = detail.releases.size.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatItem(
                label = stringResource(R.string.detail_scope),
                stat = detail.scope.size.toLong(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
internal fun StatItem(label: String, stat: Long, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        KomiText(
            text = label,
            role = KomiTextRole.Label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            uppercase = false,
        )
        KomiText(
            text = compactCount(stat.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
            role = KomiTextRole.Title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            uppercase = false,
        )
    }
}

/**
 * The hero, modelled on komi's AppHeader: an accent stripe, a 100dp avatar
 * hanging over it, the owner handle and oversized title, then the install
 * controls. The LSPosed repository has no fork/issue/license data, so only the
 * parameters it does expose are shown.
 */
@Composable
internal fun DetailHero(
    state: ModuleDetailUiState,
    detail: ModuleDetail,
    onTranslate: () -> Unit,
    onClearTranslation: () -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val module = detail.module
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val shape = MaterialTheme.shapes.medium
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val headerSurface = lerp(surface, accent, if (dark) 0.10f else 0.06f)
    val avatarShape = MaterialTheme.shapes.small

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(headerSurface)
            .border(1.5.dp, outline, shape),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clipToBounds()
                    .drawBehind { drawHeroStripes(accent, dark) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 130.dp, end = 20.dp, top = 12.dp),
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
                    .padding(start = 130.dp, end = 20.dp, top = 2.dp),
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

            module.updatedAt?.let { updated ->
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
                text = state.translatedSummary ?: module.summary.orEmpty(),
                role = KomiTextRole.Body,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (state.translatedSummary != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearTranslation) {
                        KomiText(
                            text = stringResource(R.string.detail_clear_translation),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            } else if (!module.summary.isNullOrBlank()) {
                TextButton(
                    onClick = onTranslate,
                    enabled = !state.isTranslating,
                ) {
                    KomiText(
                        text = stringResource(R.string.detail_translate),
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Plain "installed" is redundant here (the install button says
            // Reinstall); only the states that need attention keep a pill.
            if (state.pendingInstall != null || state.updateAvailable) {
                HeroStatusPill(
                    pending = state.pendingInstall != null,
                    latestVersion = module.latestFor(state.channel)?.versionName,
                    latestCode = module.latestFor(state.channel)?.versionCode,
                )
            }

            if (detail.scope.isNotEmpty()) {
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
                    detail.scope.forEach { KomiInfoChip(label = it) }
                }
            }

            if (detail.collaborators.isNotEmpty()) {
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
                    detail.collaborators.take(8).forEach { collaborator ->
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

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 20.dp, y = 30.dp)
                .size(100.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(avatarShape)
                    .background(accent.copy(alpha = if (dark) 0.20f else 0.14f))
                    .border(2.5.dp, accent, avatarShape),
                contentAlignment = Alignment.Center,
            ) {
                ModuleAvatar(
                    module = module,
                    size = 92,
                    installedPackage = state.installed?.packageName,
                )
            }
            val task = state.activeDownload
            when {
                task != null && (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.PAUSED) -> {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(100.dp),
                        color = accent.copy(alpha = 0.2f),
                        strokeWidth = 3.dp,
                    )
                    CircularProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.size(100.dp),
                        color = accent,
                        strokeWidth = 3.dp,
                        trackColor = Color.Transparent,
                    )
                }

                state.isBusy -> CircularProgressIndicator(
                    modifier = Modifier.size(100.dp),
                    color = accent,
                    strokeWidth = 3.dp,
                    trackColor = Color.Transparent,
                )
            }
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

@Composable
internal fun InstallControls(
    state: ModuleDetailUiState,
    targetVersion: ModuleVersion?,
    showUpdateHint: Boolean,
    installEnabled: Boolean,
    notApplicableReason: String?,
    debugBuild: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        if (showUpdateHint && targetVersion != null) {
            KomiText(
                text = stringResource(
                    R.string.detail_update_available,
                    targetVersion.versionName,
                    targetVersion.versionCode,
                ),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        val activeTask = state.activeDownload
        val buttonLabel = when {
            activeTask != null && activeTask.status == DownloadStatus.RUNNING ->
                "${(activeTask.progress * 100).toInt()}%"
            state.isBusy -> stringResource(R.string.detail_working)
            targetVersion == null -> stringResource(R.string.detail_no_release)
            showUpdateHint -> stringResource(R.string.detail_update_to, targetVersion.versionName)
            state.installed != null -> stringResource(R.string.detail_reinstall, targetVersion.versionName)
            else -> stringResource(R.string.detail_install, targetVersion.versionName)
        }
        if (debugBuild) {
            VersionBadge(stringResource(R.string.variant_debug_badge))
            Spacer(Modifier.height(6.dp))
        }
        KomiButton(
            onClick = onInstall,
            label = buttonLabel,
            modifier = Modifier.fillMaxWidth(),
            variant = KomiButtonVariant.Primary,
            size = KomiButtonSize.Medium,
            enabled = targetVersion != null && installEnabled && !state.isBusy,
            loading = state.isBusy,
            leadingIcon = Icons.Filled.Download,
        )

        if (notApplicableReason != null) {
            KomiText(
                text = notApplicableReason,
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        state.activeDownload?.let { task ->
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            KomiText(
                text = stringResource(
                    R.string.download_progress,
                    formatSize(task.downloaded),
                    formatSize(task.total),
                ),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (task.status) {
                    DownloadStatus.RUNNING -> KomiButton(
                        onClick = onPause,
                        label = stringResource(R.string.download_pause),
                        variant = KomiButtonVariant.Text,
                        size = KomiButtonSize.Small,
                    )

                    DownloadStatus.PAUSED -> KomiButton(
                        onClick = onResume,
                        label = stringResource(R.string.download_resume),
                        variant = KomiButtonVariant.Text,
                        size = KomiButtonSize.Small,
                    )

                    else -> Unit
                }
                KomiButton(
                    onClick = onCancel,
                    label = stringResource(R.string.download_cancel),
                    variant = KomiButtonVariant.Text,
                    size = KomiButtonSize.Small,
                )
            }
        }
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeroStripes(accent: Color, dark: Boolean) {
    val base = if (dark) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.12f)
    drawRect(base)
    val thick = 9.dp.toPx()
    val thin = 2.5.dp.toPx()
    val gapAfterThick = 10.dp.toPx()
    val gapBetweenThin = 6.dp.toPx()
    val cycle = thick + gapAfterThick + thin + gapBetweenThin + thin + gapAfterThick
    val thickColor = if (dark) accent.copy(alpha = 0.45f) else accent.copy(alpha = 0.55f)
    val thinColor = if (dark) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.30f)
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            color = thickColor,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = thick,
            cap = StrokeCap.Round,
        )
        var xt = x + thick + gapAfterThick
        drawLine(
            color = thinColor,
            start = Offset(xt, size.height),
            end = Offset(xt + size.height, 0f),
            strokeWidth = thin,
            cap = StrokeCap.Round,
        )
        xt += thin + gapBetweenThin
        drawLine(
            color = thinColor,
            start = Offset(xt, size.height),
            end = Offset(xt + size.height, 0f),
            strokeWidth = thin,
            cap = StrokeCap.Round,
        )
        x += cycle
    }
}

@Composable
internal fun WhatsNewCard(
    release: Release,
    notesHtml: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val notes = remember(notesHtml) {
        Html.fromHtml(notesHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    val needsExpansion = notes.length > 320
    val collapsedHeight = 220.dp
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KomiText(
                text = release.version?.versionName ?: release.tagName,
                role = KomiTextRole.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                uppercase = false,
            )
            KomiText(
                text = formatRelativeTime(release.publishedAt),
                role = KomiTextRole.Label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                uppercase = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        KomiSurface(contentPadding = PaddingValues(14.dp)) {
            Box {
                Box(
                    modifier = if (!expanded && needsExpansion) {
                        Modifier.height(collapsedHeight).clipToBounds()
                    } else {
                        Modifier
                    },
                ) {
                    KomiText(
                        text = notes,
                        role = KomiTextRole.Body,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                    )
                }
                if (!expanded && needsExpansion) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        if (needsExpansion) {
            Spacer(Modifier.height(12.dp))
            val shape = MaterialTheme.shapes.small
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KomiText(
                    text = stringResource(if (expanded) R.string.show_less else R.string.read_more),
                    role = KomiTextRole.Title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                    uppercase = false,
                )
                if (!expanded) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "?"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(java.util.Locale.getDefault(), "%.1f MB", mb) else String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}

/**
 * Blocked reasons that mean "this APK is not the module" (a companion app), as
 * opposed to "this build cannot run on this device".
 */
internal val BUNDLED_REASON_KINDS = setOf(
    Reason.Kind.NOT_A_MODULE,
    Reason.Kind.PACKAGE_MISMATCH,
    Reason.Kind.BUNDLED_APP,
)
