package org.lspstore.data.repository

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lspstore.domain.model.InstalledModule

class InstalledModulesRepositoryImpl(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun getInstalledModules(): List<InstalledModule> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        packages.mapNotNull { pkg ->
            val app = pkg.applicationInfo ?: return@mapNotNull null
            val meta = app.metaData
            val isXposed = meta != null &&
                (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion"))
            InstalledModule(
                packageName = pkg.packageName,
                versionCode = pkg.longVersionCode,
                versionName = pkg.versionName,
                label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(pkg.packageName),
                isXposedModule = isXposed,
                description = meta?.getString("xposeddescription"),
            )
        }
    }

    suspend fun getInstalledModule(packageName: String): InstalledModule? =
        getInstalledModules().firstOrNull { it.packageName == packageName }
}
