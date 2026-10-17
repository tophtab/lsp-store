package org.lspstore.ui.navigation

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val INSTALLED = "installed"
    const val SETTINGS = "settings"

    const val FAVOURITES = "library/favourites"
    const val RECENTLY_VIEWED = "library/recent"
    const val HIDDEN = "library/hidden"
    const val SKIPPED = "library/skipped"
    const val ABOUT = "about"

    const val DETAIL_ARG = "packageName"
    const val DETAIL = "module/{$DETAIL_ARG}"

    fun detail(packageName: String): String = "module/$packageName"
}
