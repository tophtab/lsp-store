package org.lspstore.ui.navigation

object Routes {
    const val DISCOVER = "discover"
    const val UPDATES = "updates"
    const val SETTINGS = "settings"
    const val DETAIL_ARG = "packageName"
    const val DETAIL = "module/{$DETAIL_ARG}"

    fun detail(packageName: String): String = "module/$packageName"
}
