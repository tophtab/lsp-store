package io.github.tophtab.lspstore.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

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
