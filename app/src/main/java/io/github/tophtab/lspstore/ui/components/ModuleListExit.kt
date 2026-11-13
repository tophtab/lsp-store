package io.github.tophtab.lspstore.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

internal const val MODULE_ITEM_FADE_MILLIS = 180
internal const val MODULE_ITEM_PLACEMENT_MILLIS = 250

internal data class ModuleListExitState<T>(
    val items: List<T>,
    val visibility: MutableTransitionState<Boolean>,
    val interactionsEnabled: Boolean,
) {
    val hasContent: Boolean
        get() = items.isNotEmpty() &&
            (visibility.currentState || visibility.targetState || !visibility.isIdle)
}

/**
 * Retain the last displayed dataset until its exit actually finishes. Watching
 * the transition, rather than delaying 180ms, also handles disabled or scaled
 * system animations. A new dataset interrupts the exit at full opacity.
 */
@Composable
internal fun <T> rememberModuleListExitState(
    items: List<T>,
    key: Any? = Unit,
): ModuleListExitState<T> {
    var snapshot by remember(key) { mutableStateOf(items) }
    var savedVisibility by remember(key) {
        mutableStateOf(MutableTransitionState(items.isNotEmpty()))
    }
    val visible = items.isNotEmpty()
    val visibility = if (visible && !savedVisibility.targetState) {
        MutableTransitionState(true)
    } else {
        savedVisibility
    }
    val displayed = if (visible) items else snapshot

    SideEffect {
        savedVisibility = visibility
        visibility.targetState = visible
        if (visible) {
            snapshot = items
        } else if (visibility.isIdle && !visibility.currentState) {
            snapshot = emptyList()
        }
    }

    return ModuleListExitState(displayed, visibility, interactionsEnabled = visible)
}

/** Used only for the final-item exit; animateItem handles ordinary removals. */
@Composable
internal fun <T> ModuleListExit(
    state: ModuleListExitState<T>,
    modifier: Modifier = Modifier,
    content: @Composable (List<T>) -> Unit,
) {
    AnimatedVisibility(
        visibleState = state.visibility,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = fadeOut(tween(MODULE_ITEM_FADE_MILLIS, easing = FastOutSlowInEasing)),
    ) {
        CompositionLocalProvider(
            LocalModuleCardInteractionsEnabled provides state.interactionsEnabled,
        ) {
            content(state.items)
        }
    }
}
