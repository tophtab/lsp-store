package org.lspstore.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val LocalStatusColors = staticCompositionLocalOf { statusColors(dark = false) }

/** 16dp panels, 10dp controls — the classic komi geometry. */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

fun cyanColorScheme(
    dark: Boolean,
    amoled: Boolean = false,
): androidx.compose.material3.ColorScheme = when {
    !dark -> CyanLightScheme
    amoled -> CyanAmoledScheme
    else -> CyanDarkScheme
}

@Composable
fun LspStoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> cyanColorScheme(dark = darkTheme, amoled = amoled)
    }
    // AMOLED is an independent preference: it also applies on top of a dynamic
    // (Material You) dark scheme, not only the cyan palette.
    val colorScheme = if (darkTheme && amoled && dynamicColor) {
        baseScheme.copy(
            background = CyanAmoledScheme.background,
            surface = CyanAmoledScheme.surface,
            surfaceDim = CyanAmoledScheme.surfaceDim,
            surfaceContainerLowest = CyanAmoledScheme.surfaceContainerLowest,
            surfaceContainerLow = CyanAmoledScheme.surfaceContainerLow,
            surfaceContainer = CyanAmoledScheme.surfaceContainer,
            surfaceContainerHigh = CyanAmoledScheme.surfaceContainerHigh,
            surfaceContainerHighest = CyanAmoledScheme.surfaceContainerHighest,
        )
    } else {
        baseScheme
    }

    CompositionLocalProvider(
        LocalStatusColors provides statusColors(darkTheme),
        LocalKomiType provides ClassicType,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}
