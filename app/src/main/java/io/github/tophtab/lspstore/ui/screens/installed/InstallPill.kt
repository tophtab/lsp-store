package io.github.tophtab.lspstore.ui.screens.installed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import java.text.NumberFormat

/**
 * The update button while a row is transferring or installing: its background
 * fills left to right with the download and the label stays inside the pill.
 * The pill doubles as the cancel button while the APK is still on the wire.
 */
@Composable
internal fun InstallPill(
    state: BatchInstallState,
    onCancel: (() -> Unit)?,
    retry: Boolean = false,
) {
    val failed = state.phase == BatchInstallPhase.FAILED
    val colors = MaterialTheme.colorScheme
    // Tonal container + its on-color: the text stays legible on the unfilled
    // part and on the progress tint alike, in both light and dark themes.
    val container = if (failed) colors.errorContainer else colors.primaryContainer
    val content = if (failed) colors.onErrorContainer else colors.onPrimaryContainer
    // primary and primaryContainer are the same teal in the light theme, so the
    // fill is a tint of the content colour instead: it stays visible in both
    // themes and with dynamic colour.
    val fillColor = colors.onPrimaryContainer.copy(alpha = 0.20f)
    val filled = when (state.phase) {
        BatchInstallPhase.DOWNLOADING -> state.progress
        BatchInstallPhase.WAITING_TO_INSTALL,
        BatchInstallPhase.INSTALLING,
        BatchInstallPhase.COMPLETED,
        -> 1f

        else -> 0f
    }
    val animatedFill by animateFloatAsState(
        targetValue = filled,
        animationSpec = tween(durationMillis = 350),
        label = "row-download-progress",
    )
    val percent = remember {
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }
    }
    val label = when (state.phase) {
        BatchInstallPhase.QUEUED -> stringResource(R.string.installed_state_queued)
        BatchInstallPhase.PREPARING -> stringResource(R.string.installed_state_preparing)
        // 100% is the install stage's business; the transfer caps at 99 so a
        // half-finished frame cannot claim completion.
        BatchInstallPhase.DOWNLOADING ->
            percent.format(state.progress.coerceIn(0f, 0.99f).toDouble())

        BatchInstallPhase.WAITING_TO_INSTALL -> stringResource(R.string.installed_state_waiting)
        BatchInstallPhase.INSTALLING -> stringResource(R.string.installed_state_installing)
        BatchInstallPhase.PENDING_CONFIRMATION -> stringResource(R.string.pending_install)
        BatchInstallPhase.ALREADY_RUNNING -> stringResource(R.string.installed_state_waiting)
        BatchInstallPhase.COMPLETED -> stringResource(R.string.installed_state_done)
        BatchInstallPhase.FAILED -> stringResource(R.string.installed_state_failed)
        BatchInstallPhase.SKIPPED,
        BatchInstallPhase.CANCELLED,
        -> ""
    }
    val icon = when (state.phase) {
        BatchInstallPhase.COMPLETED -> Icons.Filled.Check
        BatchInstallPhase.FAILED -> Icons.Filled.Refresh
        else -> null
    }
    val actionLabel = when {
        retry -> stringResource(R.string.installed_retry_update)
        onCancel != null -> stringResource(R.string.installed_cancel_download)
        else -> null
    }
    // Same capsule as the small KomiButton this replaces.
    val shape = CircleShape
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp)
            .height(34.dp)
            .clip(shape)
            .background(container)
            .drawBehind {
                if (!failed && animatedFill > 0f) {
                    drawRect(
                        color = fillColor,
                        size = Size(width = size.width * animatedFill, height = size.height),
                    )
                }
            }
            .then(
                if (onCancel != null) {
                    Modifier.clickable(
                        onClickLabel = actionLabel,
                        role = Role.Button,
                        onClick = onCancel,
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                if (state.phase == BatchInstallPhase.DOWNLOADING) {
                    progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(14.dp),
                )
            }
            KomiText(
                text = label,
                role = KomiTextRole.Mono,
                color = content,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
