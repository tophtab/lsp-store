package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window insets for a screen that sits inside the app's navigation suite.
 *
 * The suite consumes the inset its container occupies: the bottom inset for a
 * navigation bar, the start inset for a rail. Everything else - including the
 * bottom inset when the rail is on screen - still has to be applied here, so
 * this is the full safe-drawing set rather than top and horizontal only.
 */
@Composable
fun rootContentWindowInsets(): WindowInsets = WindowInsets.safeDrawing

/** Shared spacing rhythm, matching komi-store. */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}
