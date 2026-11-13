package io.github.tophtab.lspstore.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput

internal val LocalIgnoreHoldCoordinator = staticCompositionLocalOf<IgnoreHoldCoordinator?> { null }

/**
 * A card only receives pointers on its own Compose hit path. The app ancestor
 * observes every pointer and cancels the owner when another finger lands on a
 * different card, the header or navigation. Capture outlives the owning card:
 * disposing it or lifting its finger must not release the remaining fingers
 * to a list's scroll/refresh detector.
 */
internal class IgnoreHoldCoordinator {
    private var sequenceActive = false
    private var primaryPointerId: Long? = null
    private var pressedPointerIds = emptySet<Long>()
    private var cancelled = false
    private var owner: IgnoreHoldGesture? = null
    private var retainedCapture = false

    val captured: Boolean
        get() = retainedCapture || owner?.captured == true

    fun observePointers(pressedIds: Set<Long>, participatingIds: Set<Long>) {
        if (!sequenceActive) {
            sequenceActive = true
            primaryPointerId = participatingIds.singleOrNull()
        }
        pressedPointerIds = pressedIds
        if (primaryPointerId == null || participatingIds.any { it != primaryPointerId }) {
            cancelled = true
            owner?.cancel()
        }
    }

    fun claim(gesture: IgnoreHoldGesture, pointerId: Long): Boolean {
        if (!sequenceActive || cancelled || captured || owner != null ||
            pointerId != primaryPointerId || pressedPointerIds != setOf(pointerId)
        ) {
            return false
        }
        owner = gesture
        return true
    }

    fun owns(gesture: IgnoreHoldGesture): Boolean = owner === gesture

    fun detach(gesture: IgnoreHoldGesture) {
        if (owner !== gesture) return
        retainedCapture = captured
        gesture.cancel()
        owner = null
        cancelled = true
    }

    /** Called after the all-up event's Main pass, or on touch interruption. */
    fun finish() {
        owner?.cancel()
        owner = null
        sequenceActive = false
        primaryPointerId = null
        pressedPointerIds = emptySet()
        cancelled = false
        retainedCapture = false
    }
}

private suspend fun PointerInputScope.observeIgnoreHolds(coordinator: IgnoreHoldCoordinator) {
    try {
        awaitEachGesture {
            try {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var event = currentEvent
                while (true) {
                    coordinator.observePointers(
                        pressedIds = event.changes.filter { it.pressed }.map { it.id.value }.toSet(),
                        participatingIds = event.changes
                            .filter { it.pressed || it.previousPressed }
                            .map { it.id.value }.toSet(),
                    )
                    // Initial reaches the ancestor before every card, scroll
                    // detector and navigation control, including other hit paths.
                    if (coordinator.captured) event.changes.forEach { it.consume() }
                    val allUp = event.changes.none { it.pressed }
                    awaitPointerEvent(PointerEventPass.Final)
                    if (allUp) break
                    event = awaitPointerEvent(PointerEventPass.Initial)
                }
            } finally {
                // Keep ownership through the up's click dispatch. ACTION_CANCEL
                // also gets here even when no all-up pointer event is delivered.
                coordinator.finish()
            }
        }
    } finally {
        coordinator.finish()
    }
}

/** One host around the app content, including both forms of navigation. */
@Composable
internal fun ModuleIgnoreGestureHost(content: @Composable () -> Unit) {
    val coordinator = remember { IgnoreHoldCoordinator() }
    CompositionLocalProvider(LocalIgnoreHoldCoordinator provides coordinator) {
        Box(Modifier.fillMaxSize().pointerInput(coordinator) { observeIgnoreHolds(coordinator) }) {
            content()
        }
    }
}
