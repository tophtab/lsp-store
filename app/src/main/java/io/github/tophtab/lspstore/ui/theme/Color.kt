package io.github.tophtab.lspstore.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 "icon cyan" scheme.
 *
 * The brand colour is the launcher icon's background itself (#CDF2F0): it is
 * mapped to `primary`, paired with a dark teal `onPrimary` so buttons, chips
 * and selected indicators carry the exact icon cyan while text stays readable.
 *
 * Material's stock TonalSpot generator cannot express this: it reserves
 * `primary` for tone 40 (dark teal) and keeps `surface` nearly neutral. The
 * supporting roles here still come from Material Color Utilities tonal
 * palettes anchored to the icon hue (HCT 194.8), with low-chroma neutrals so
 * backgrounds and containers share the cyan cast (surface = tone 98).
 *
 * Regenerate supporting roles from tonal palettes; `primary` and `onPrimary`
 * are intentionally fixed to the icon colour and its readable on-colour.
 */

private val IconCyanLight = lightColorScheme(
    primary = Color(0xFFCDF2F0),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFFCDF2F0),
    onPrimaryContainer = Color(0xFF00363A),
    secondary = Color(0xFF006A68),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDF2F0),
    onSecondaryContainer = Color(0xFF00363A),
    tertiary = Color(0xFF4A607B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E4FF),
    onTertiaryContainer = Color(0xFF031D35),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF1FBFA),
    onBackground = Color(0xFF141D1D),
    surface = Color(0xFFF1FBFA),
    onSurface = Color(0xFF141D1D),
    surfaceVariant = Color(0xFFCCE8E6),
    onSurfaceVariant = Color(0xFF324B4A),
    outline = Color(0xFF627C7B),
    outlineVariant = Color(0xFFB0CCCA),
    inverseSurface = Color(0xFF293232),
    inverseOnSurface = Color(0xFFE8F3F2),
    inversePrimary = Color(0xFFCDF2F0),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFCDF2F0),
    surfaceBright = Color(0xFFF1FBFA),
    surfaceDim = Color(0xFFD2DCDB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEBF6F4),
    surfaceContainer = Color(0xFFE6F0EF),
    surfaceContainerHigh = Color(0xFFE0EAE9),
    surfaceContainerHighest = Color(0xFFDAE5E3),
)

private val IconCyanDark = darkColorScheme(
    primary = Color(0xFFCDF2F0),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFFCDF2F0),
    secondary = Color(0xFFA8EFED),
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF00504E),
    onSecondaryContainer = Color(0xFFCDF2F0),
    tertiary = Color(0xFFB2C8E8),
    onTertiary = Color(0xFF1B324B),
    tertiaryContainer = Color(0xFF324863),
    onTertiaryContainer = Color(0xFFD2E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0C1515),
    onBackground = Color(0xFFDAE5E3),
    surface = Color(0xFF0C1515),
    onSurface = Color(0xFFDAE5E3),
    surfaceVariant = Color(0xFF324B4A),
    onSurfaceVariant = Color(0xFFB0CCCA),
    outline = Color(0xFF7B9694),
    outlineVariant = Color(0xFF324B4A),
    inverseSurface = Color(0xFFDAE5E3),
    inverseOnSurface = Color(0xFF293232),
    inversePrimary = Color(0xFF006A68),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFCDF2F0),
    surfaceBright = Color(0xFF313B3A),
    surfaceDim = Color(0xFF0C1515),
    surfaceContainerLowest = Color(0xFF07100F),
    surfaceContainerLow = Color(0xFF141D1D),
    surfaceContainer = Color(0xFF182121),
    surfaceContainerHigh = Color(0xFF222C2B),
    surfaceContainerHighest = Color(0xFF2D3736),
)

/** AMOLED black keeps the generated tonal roles and only darkens surfaces. */
val CyanAmoledScheme = IconCyanDark.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF081210),
    surfaceContainer = Color(0xFF0E1817),
    surfaceContainerHigh = Color(0xFF182221),
    surfaceContainerHighest = Color(0xFF222C2B),
)

val CyanLightScheme = IconCyanLight
val CyanDarkScheme = IconCyanDark

/** GitHub-style semantic status colours, matching komi-store's palette. */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val danger: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF1A7F37),
    warning = Color(0xFF9A6700),
    info = Color(0xFF0969DA),
    danger = Color(0xFFCF222E),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF3FB950),
    warning = Color(0xFFD29922),
    info = Color(0xFF58A6FF),
    danger = Color(0xFFF85149),
)

fun statusColors(dark: Boolean): StatusColors = if (dark) DarkStatus else LightStatus
