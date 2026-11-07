package io.github.tophtab.lspstore.data.device

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Shows or hides the app's launcher entry.
 *
 * The launcher intent filter lives on [LAUNCHER_ALIAS], not on MainActivity, so
 * toggling that one component removes the icon while the activity itself stays
 * enabled for notifications. The separate MODULE_SETTINGS alias in the manifest
 * is never touched, which keeps the store reachable from LSPosed Manager once
 * the icon is gone.
 */
object LauncherIcon {

    private const val LAUNCHER_ALIAS = ".LauncherActivity"

    /** Applies [hidden] to the launcher alias. */
    fun setHidden(context: Context, hidden: Boolean) {
        val component = ComponentName(context.packageName, context.packageName + LAUNCHER_ALIAS)
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
