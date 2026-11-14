package io.github.tophtab.lspstore.ui.screens.detail

import io.github.tophtab.lspstore.domain.model.formatByteSize
import io.github.tophtab.lspstore.domain.model.installAction
import io.github.tophtab.lspstore.domain.model.InstallAction
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/**
 * The install button, komi style: the download progress fills the button itself
 * instead of a bar underneath, and while a transfer is running a tap on the same
 * button cancels it. There is no separate pause/resume/cancel row any more.
 */
@Composable
internal fun InstallControls(
    state: ModuleDetailUiState,
    run: io.github.tophtab.lspstore.data.install.ModuleInstallRun? = state.activeInstall,
    targetVersion: ModuleVersion?,
    target: ResolvedInstallTarget?,
    showUpdateHint: Boolean,
    installEnabled: Boolean,
    notApplicableReason: String?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    // A run that is already installing has nothing left to cancel, so the button
    // shows the stage and stays inert (komi does the same). A queued run has not
    // started transferring yet but can still be cancelled.
    val queued = run?.takeIf { it.stage == InstallStage.QUEUED || it.stage == InstallStage.PREPARING }
    val downloading = run?.takeIf { it.stage == InstallStage.DOWNLOADING }
    val installing = run?.stage == InstallStage.INSTALLING || run?.stage == InstallStage.WAITING_TO_INSTALL
    val pending = state.pendingInstall != null || run?.stage == InstallStage.PENDING_CONFIRMATION
    val active = queued != null || downloading != null || installing || pending

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

        // Stage labels belong to active install tasks. Background APK
        // recommendations leave the selected action available on entry.
        val buttonLabel = when {
            pending -> stringResource(R.string.pending_install)
            run?.stage == InstallStage.PREPARING -> stringResource(R.string.installed_state_preparing)
            run?.stage == InstallStage.WAITING_TO_INSTALL -> stringResource(R.string.installed_state_waiting)
            queued != null -> stringResource(R.string.detail_queued)
            downloading != null -> stringResource(R.string.detail_downloading)
            installing -> stringResource(R.string.detail_installing)
            target == null -> stringResource(R.string.detail_no_release)
            else -> {
                val versionName = target.apkVersionName ?: targetVersion?.versionName ?: target.asset.name
                when (installAction(target, state.installed, state.variantState)) {
                    InstallAction.INSTALL -> stringResource(R.string.detail_install, versionName)
                    InstallAction.UPDATE -> stringResource(R.string.detail_update_to, versionName)
                    InstallAction.REINSTALL -> stringResource(R.string.detail_reinstall, versionName)
                    InstallAction.DOWNGRADE -> stringResource(R.string.detail_downgrade, versionName)
                }
            }
        }
        // While downloading the second line is the byte counter plus the cancel
        // affordance; the percentage is already the filled part of the button.
        val buttonSubtitle = downloading?.let {
            stringResource(
                R.string.download_progress,
                formatByteSize(it.downloaded),
                formatByteSize(it.total),
            ) + " · " + stringResource(R.string.download_tap_to_cancel)
        }

        InstallActionButton(
            label = buttonLabel,
            subtitle = buttonSubtitle,
            progress = downloading?.progress,
            active = active,
            enabled = queued != null || downloading != null ||
                (!active && installEnabled),
            leadingIcon = if (active) null else Icons.Filled.Download,
            onClick = if (queued != null || downloading != null) onCancel else onInstall,
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
    active: Boolean,
    enabled: Boolean,
    leadingIcon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
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
                // Queued has no byte progress, so it only shows the dimmed
                // active container; the fill starts with the transfer.
                if (active && progress != null) {
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
