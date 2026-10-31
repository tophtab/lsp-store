package io.github.tophtab.lspstore.ui.navigation

/** Which navigation container the app should use for the current window. */
enum class NavPlacement {
    /** Compact width: bottom navigation bar. */
    CompactBar,

    /** Wide but short window, portrait window, or tabletop posture: bottom bar. */
    MediumBar,

    /** Wide, landscape window: Material 3 navigation rail on the start edge. */
    Rail,
}

/**
 * Mirrors the window-size rules of Material 3's adaptive navigation guidance:
 * compact width always gets a bottom bar; a compact height or tabletop posture
 * keeps a bottom bar even when the window is wide; a tablet held in portrait
 * keeps the phone layout too. Only a wide, landscape window gets the rail.
 */
fun navPlacement(
    widthDp: Int,
    heightDp: Int,
    tabletop: Boolean,
): NavPlacement = when {
    widthDp < 600 -> NavPlacement.CompactBar
    heightDp < 480 || tabletop -> NavPlacement.MediumBar
    widthDp <= heightDp -> NavPlacement.MediumBar
    else -> NavPlacement.Rail
}

/**
 * The root tab that should stay highlighted while a non-root page (a module
 * detail, author page, library list, ...) is on top of the back stack.
 */
fun selectedTopLevelRoute(
    currentRoute: String?,
    lastRootRoute: String?,
    roots: Set<String>,
): String? = currentRoute?.takeIf { it in roots } ?: lastRootRoute
