package org.lspstore.domain.usecase

import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo

object UpdateChecker {

    fun findUpdates(
        installed: List<InstalledModule>,
        index: List<Module>,
        channel: UpdateChannel,
    ): List<UpdateInfo> {
        val byPackage = index.associateBy { it.packageName }
        return installed.asSequence()
            .filter { it.isXposedModule }
            .mapNotNull { installedModule ->
                val module = byPackage[installedModule.packageName] ?: return@mapNotNull null
                val latest = module.latestFor(channel) ?: return@mapNotNull null
                if (latest.isNewerThan(installedModule.versionCode, installedModule.versionName)) {
                    UpdateInfo(module = module, installed = installedModule, latest = latest)
                } else {
                    null
                }
            }
            .sortedByDescending { it.latest.versionCode }
            .toList()
    }
}
