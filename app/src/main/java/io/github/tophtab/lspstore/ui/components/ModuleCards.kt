@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.design.KomiChipRow
import io.github.tophtab.lspstore.ui.design.KomiDiamond
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiStarValue
import io.github.tophtab.lspstore.ui.design.KomiStat
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/** Wall-clock hold time; independent of the system's animation duration scale. */
internal const val IGNORE_HOLD_MILLIS = 1_200L
private const val IGNORE_HOLD_SCALE = 1.025f
private const val IGNORE_CANCEL_MILLIS = 150

/** Exit snapshots display the card without allowing another gesture or action. */
internal val LocalModuleCardInteractionsEnabled = compositionLocalOf { true }

/**
 * The pointer adapter and JVM tests share the same commit gate. Capturing a
 * gesture is latched until every finger lifts, even when that hold is cancelled.
 * Click suppression survives release until Surface processes the same up event;
 * afterwards keyboard and accessibility clicks can open the card normally.
 */
internal class IgnoreHoldGesture(
    platformLongPressMillis: Long,
    private val onOpen: () -> Unit,
    private val onIgnore: () -> Unit,
    private val onReady: () -> Unit = {},
    private val onReject: () -> Unit = {},
) {
    private enum class Phase { Idle, Pressed, Holding, Ready, Cancelled }

    private var phase by mutableStateOf(Phase.Idle)
    private var startedAtMillis = 0L
    private var suppressClick = false
    private val captureMillis = platformLongPressMillis.coerceIn(0L, IGNORE_HOLD_MILLIS)

    var captured = false
        private set

    val isPressed: Boolean
        get() = phase == Phase.Pressed || phase == Phase.Holding || phase == Phase.Ready

    val isReady: Boolean
        get() = phase == Phase.Ready

    val nextDeadlineMillis: Long?
        get() = when (phase) {
            Phase.Pressed -> startedAtMillis + captureMillis
            Phase.Holding -> startedAtMillis + IGNORE_HOLD_MILLIS
            else -> null
        }

    fun press(nowMillis: Long) {
        startedAtMillis = nowMillis
        captured = false
        suppressClick = false
        phase = Phase.Pressed
    }

    fun advance(nowMillis: Long) {
        val elapsed = nowMillis - startedAtMillis
        if (phase == Phase.Pressed && elapsed >= captureMillis) {
            captured = true
            suppressClick = true
            phase = Phase.Holding
        }
        if (phase == Phase.Holding && elapsed >= IGNORE_HOLD_MILLIS) {
            phase = Phase.Ready
            onReady()
        }
    }

    fun updatePointer(
        nowMillis: Long,
        insideCard: Boolean,
        hasOtherPointers: Boolean = false,
        interrupted: Boolean = false,
    ) {
        if (!insideCard) cancel(movedOutside = true)
        if (hasOtherPointers || interrupted) cancel()
        advance(nowMillis)
    }

    fun release(nowMillis: Long, insideCard: Boolean) {
        // An up event on the deadline can arrive before the timeout callback.
        // Check bounds first so an outside release cannot briefly become ready.
        if (!insideCard) cancel(movedOutside = true)
        advance(nowMillis)
        val commit = isReady
        phase = Phase.Idle
        if (commit) onIgnore()
    }

    fun cancel(movedOutside: Boolean = false) {
        if (!isPressed) return
        val reject = isReady && movedOutside
        suppressClick = true
        phase = Phase.Cancelled
        if (reject) onReject()
    }

    fun open() {
        if (!suppressClick) onOpen()
    }

    fun finish() {
        phase = Phase.Idle
        captured = false
        suppressClick = false
    }
}

private suspend fun PointerInputScope.detectIgnoreHold(
    gesture: IgnoreHoldGesture,
    coordinator: IgnoreHoldCoordinator?,
) {
    try {
        awaitEachGesture {
            // The detector sits outside graphicsLayer: size and positions refer
            // to the original card rectangle, rather than the enlarged visual.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.press(down.uptimeMillis)
            if (down.isConsumed || down.isOutOfBounds(size, Size.Zero) ||
                coordinator?.claim(gesture, down.id.value) == false
            ) {
                gesture.cancel()
            }

            try {
                while (true) {
                    val timeout = gesture.nextDeadlineMillis?.let {
                        (it - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                    }
                    val event = if (timeout != null) {
                        withTimeoutOrNull(timeout) { awaitPointerEvent(PointerEventPass.Initial) }
                    } else {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                    if (event == null) {
                        gesture.advance(SystemClock.uptimeMillis())
                        continue
                    }

                    val primary = event.changes.firstOrNull { it.id == down.id }
                    val inside = primary?.let { !it.isOutOfBounds(size, Size.Zero) } ?: true
                    gesture.updatePointer(
                        nowMillis = primary?.uptimeMillis ?: SystemClock.uptimeMillis(),
                        insideCard = inside,
                        hasOtherPointers = event.changes.any {
                            it.id != down.id && (it.pressed || it.previousPressed)
                        },
                        interrupted = primary == null || event.changes.any { it.isConsumed },
                    )
                    if (!gesture.captured && primary != null &&
                        (primary.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        gesture.cancel()
                    }

                    if (primary?.changedToUpIgnoreConsumed() == true) {
                        gesture.release(primary.uptimeMillis, insideCard = inside)
                    }

                    // Initial runs before the card click and the list's scroll
                    // detector. Drain all fingers here after capture, including
                    // an outside or multi-touch cancellation, to prevent refresh.
                    if (gesture.captured) event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) {
                        // Let Surface process this up before clearing the click
                        // guard. A later keyboard/accessibility click is fresh.
                        awaitPointerEvent(PointerEventPass.Final)
                        break
                    }

                    if (!gesture.captured) {
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any {
                                it.isConsumed && it.position != it.previousPosition
                            }
                        ) {
                            gesture.cancel()
                        }
                    }
                }
            } finally {
                gesture.cancel()
                coordinator?.detach(gesture)
                gesture.finish()
            }
        }
    } finally {
        // Pointer interruption, a package-key change and disposal all get here.
        gesture.cancel()
        coordinator?.detach(gesture)
        gesture.finish()
    }
}

/**
 * Full-width module card, modelled on komi's repo card: icon tile, title,
 * owner/package line, two-line summary, chips, then a divider and a stats row.
 *
 * Holding for 1.2 seconds arms [onIgnore]; releasing inside commits it. Moving
 * outside cancels permanently for this press. A normal short tap opens the
 * module, while an early release after long-press capture is swallowed.
 */
@Composable
fun ModuleCard(
    module: Module,
    installed: Boolean,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val interactionsEnabled = LocalModuleCardInteractionsEnabled.current
    val coordinator = LocalIgnoreHoldCoordinator.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnIgnore by rememberUpdatedState(onIgnore)
    val currentHaptics by rememberUpdatedState(haptics)
    val gesture = remember(module.packageName, viewConfiguration.longPressTimeoutMillis) {
        IgnoreHoldGesture(
            platformLongPressMillis = viewConfiguration.longPressTimeoutMillis,
            onOpen = { currentOnOpen() },
            onIgnore = { currentOnIgnore() },
            onReady = { currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onReject = { currentHaptics.performHapticFeedback(HapticFeedbackType.Reject) },
        )
    }
    val scale = remember(module.packageName) { Animatable(1f) }
    LaunchedEffect(gesture, gesture.isPressed, interactionsEnabled) {
        if (gesture.isPressed && interactionsEnabled) {
            scale.animateTo(
                IGNORE_HOLD_SCALE,
                tween(IGNORE_HOLD_MILLIS.toInt(), easing = LinearEasing),
            )
        } else {
            scale.animateTo(1f, tween(IGNORE_CANCEL_MILLIS))
        }
    }
    DisposableEffect(gesture, interactionsEnabled, coordinator) {
        onDispose {
            gesture.cancel()
            coordinator?.detach(gesture)
        }
    }
    val ready = gesture.isReady && interactionsEnabled
    KomiSurface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (interactionsEnabled) {
                    Modifier.pointerInput(module.packageName, gesture, coordinator) {
                        detectIgnoreHold(gesture, coordinator)
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            )
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        onClick = if (interactionsEnabled) gesture::open else null,
        contentPadding = PaddingValues(15.dp),
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Feed and search cards never show a launcher icon: the LSPosed
                // repository only exposes the author's avatar, and swapping in
                // the installed APK icon made installed modules look different
                // from the rest of the feed. The Installed tab keeps the APK
                // icon. The installed state is still flagged by the chip below.
                Column(modifier = Modifier.weight(1f)) {
                    KomiText(
                        text = module.title,
                        role = KomiTextRole.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KomiText(
                            text = "@${module.contributor ?: "LSPosed"}",
                            role = KomiTextRole.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        KomiDiamond()
                        KomiText(
                            text = module.packageName,
                            role = KomiTextRole.Mono,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            module.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                KomiText(
                    text = summary,
                    role = KomiTextRole.Body,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 11.dp),
                )
            }

            // Version chips are intentionally omitted: the LSPosed module
            // version is shown on the detail page, not in the feed.
            if (installed) {
                FlowRow(
                    modifier = Modifier.padding(top = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KomiInfoChip(stringResource(R.string.label_installed))
                }
            }

            KomiDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )

            // The stats still measure this box while hidden. The ready hint
            // occupies exactly that height, including with larger system fonts.
            Box(modifier = Modifier.fillMaxWidth().padding(top = 11.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (ready) 0f else 1f)
                        .then(if (ready) Modifier.clearAndSetSemantics { } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KomiStarValue(module.stars)
                        formatRelativeTime(module.lastPublishedAt).takeIf { it.isNotBlank() }?.let { relative ->
                            KomiStat(
                                icon = Icons.Outlined.Schedule,
                                value = stringResource(R.string.module_updated_relative, relative),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (ready) {
                    KomiText(
                        text = stringResource(R.string.module_ignore_ready),
                        role = KomiTextRole.Body,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.matchParentSize().wrapContentHeight(),
                    )
                }
            }
        }
    }
}

/** Compact card kept for horizontal shelves. */
@Composable
fun ModuleCardCompact(
    module: Module,
    installed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KomiSurface(
        modifier = modifier.width(196.dp),
        onClick = onClick,
        contentPadding = PaddingValues(13.dp),
    ) {
        Column {
            ModuleAvatar(
                module,
                size = 40,
            )
            Spacer(Modifier.height(9.dp))
            KomiText(
                text = module.title,
                role = KomiTextRole.Title,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            KomiText(
                text = module.packageName,
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (installed) {
                    KomiInfoChip(stringResource(R.string.label_installed))
                }
                module.latestRelease?.let { KomiInfoChip(it.versionName) }
            }
            KomiDivider(
                modifier = Modifier.padding(top = 9.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Row(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KomiStarValue(module.stars)
                formatRelativeTime(module.lastPublishedAt).takeIf { it.isNotBlank() }?.let {
                    KomiText(
                        text = it,
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun KomiChipSpacer() {
    Spacer(Modifier.width(6.dp))
}
