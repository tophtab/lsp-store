package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window insets for screens shown above the app's bottom bar. The outer
 * scaffold owns the bottom inset (the bar pads for the navigation bar itself),
 * so these screens only need the status bar and horizontal cutouts.
 */
@Composable
fun topContentWindowInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** Shared spacing rhythm, matching komi-store. */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}
