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
    const val IMPORTS = "library/imports"
    const val ABOUT = "about"
    const val DIAGNOSTICS = "diagnostics"
    const val LICENSES = "licenses"
    const val MIRRORS = "mirrors"
    const val AUTHOR_ARG = "author"
    const val AUTHOR = "author/{$AUTHOR_ARG}"

    const val DETAIL_ARG = "packageName"
    const val DETAIL = "module/{$DETAIL_ARG}"

    fun detail(packageName: String): String = "module/$packageName"

    fun author(name: String): String = "author/${java.net.URLEncoder.encode(name, "UTF-8")}"
}
