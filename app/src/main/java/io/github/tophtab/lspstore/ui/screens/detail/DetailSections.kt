@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package io.github.tophtab.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.compactCount
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.theme.LocalKomiType

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
 * The hero: a tinted card with the owner handle, oversized title, summary and
 * install controls. The LSPosed repository has no fork/issue/license data, so
 * only the parameters it does expose are shown.
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

/**
 * The install button, komi style: the download progress fills the button itself
 * instead of a bar underneath, and while a transfer is running a tap on the same
 * button cancels it. There is no separate pause/resume/cancel row any more.
 */
@Composable
internal fun InstallControls(
    state: ModuleDetailUiState,
    targetVersion: ModuleVersion?,
    showUpdateHint: Boolean,
    installEnabled: Boolean,
    notApplicableReason: String?,
    debugBuild: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val run = state.activeInstall
    // A run that is already installing has nothing left to cancel, so the button
    // shows the stage and stays inert (komi does the same).
    val downloading = run?.takeIf { it.stage == InstallStage.DOWNLOADING }
    val installing = run?.stage == InstallStage.INSTALLING
    val active = downloading != null || installing

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

        val buttonLabel = when {
            downloading != null -> stringResource(R.string.detail_downloading)
            installing -> stringResource(R.string.detail_installing)
            state.isBusy -> stringResource(R.string.detail_working)
            targetVersion == null -> stringResource(R.string.detail_no_release)
            showUpdateHint -> stringResource(R.string.detail_update_to, targetVersion.versionName)
            state.installed != null -> stringResource(R.string.detail_reinstall, targetVersion.versionName)
            else -> stringResource(R.string.detail_install, targetVersion.versionName)
        }
        // While downloading the second line is the byte counter plus the cancel
        // affordance; the percentage is already the filled part of the button.
        val buttonSubtitle = downloading?.let {
            stringResource(
                R.string.download_progress,
                formatSize(it.downloaded),
                formatSize(it.total),
            ) + " · " + stringResource(R.string.download_tap_to_cancel)
        }
        if (debugBuild) {
            VersionBadge(stringResource(R.string.variant_debug_badge))
            Spacer(Modifier.height(6.dp))
        }
        InstallActionButton(
            label = buttonLabel,
            subtitle = buttonSubtitle,
            progress = downloading?.progress,
            enabled = downloading != null ||
                (!active && !state.isBusy && targetVersion != null && installEnabled),
            leadingIcon = if (active) null else Icons.Filled.Download,
            onClick = if (downloading != null) onCancel else onInstall,
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
    }
}

/**
 * Primary action bar whose background doubles as a progress indicator, ported
 * from komi's `PrimaryAction`: a dimmed accent while a transfer runs, the accent
 * filling from the left as bytes land, muted surface when there is nothing to
 * install.
 *
 * @param progress 0..1 while downloading, null when idle or installing.
 */
@Composable
private fun InstallActionButton(
    label: String,
    subtitle: String?,
    progress: Float?,
    enabled: Boolean,
    leadingIcon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val active = progress != null
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(durationMillis = 350),
        label = "install-progress",
    )
    val container = if (enabled || active) colors.primary else colors.surfaceContainerHigh
    val content = if (enabled || active) colors.onPrimary else colors.onSurface.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .drawBehind {
                drawRect(container.copy(alpha = if (active) 0.35f else 1f))
                if (active) {
                    drawRect(
                        color = container,
                        size = Size(width = size.width * animatedProgress, height = size.height),
                    )
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(18.dp),
                    )
                }
                KomiText(
                    text = label,
                    role = KomiTextRole.Title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    uppercase = false,
                )
            }
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    fontSize = 11.sp,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 1,
                    uppercase = false,
                )
            }
        }
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
