package io.github.tophtab.lspstore.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Shows a view model message once and clears it when the screen leaves
 * composition.
 *
 * The show call suspends for the snackbar's lifetime. Navigating away cancels
 * it before the following clear, so without the dispose clear the message
 * would stay in the view model and the snackbar would replay on return.
 */
@Composable
fun SnackbarMessageEffect(
    message: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
    duration: SnackbarDuration = SnackbarDuration.Short,
) {
    val consumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        hostState.showSnackbar(text, duration = duration)
        consumed()
    }
    DisposableEffect(Unit) {
        onDispose { consumed() }
    }
}
