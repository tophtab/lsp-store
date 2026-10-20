package org.lspstore.domain.usecase

import java.time.Instant
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.Module
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.variant.ModuleVariantState

object UpdateChecker {

    fun findUpdates(
        installed: List<InstalledModule>,
        index: List<Module>,
        channel: UpdateChannel,
        skipped: Map<String, Long> = emptyMap(),
        links: Map<String, String> = emptyMap(),
        ignored: Set<String> = emptySet(),
        variantStates: Map<String, ModuleVariantState> = emptyMap(),
    ): List<UpdateInfo> {
        val byPackage = index.associateBy { it.packageName }
        return installed.asSequence()
            .filter { it.isXposedModule }
            .mapNotNull { installedModule ->
                val module = byPackage[installedModule.packageName]
                    ?: links[installedModule.packageName]?.let { byPackage[it] }
                    ?: return@mapNotNull null
                // Skipped/ignored lists may be keyed by either the installed package
                // or the linked repository package. Honour both.
                if (installedModule.packageName in ignored || module.packageName in ignored) {
                    return@mapNotNull null
                }
                val variantState = variantStates[module.packageName]
                    ?: variantStates[installedModule.packageName]
                // No installable variant on this device: stay out of updates,
                // notifications, batch installs and downloads.
                if (variantState?.isNotApplicable == true) return@mapNotNull null

                val latest = module.latestFor(channel) ?: return@mapNotNull null
                val skippedVersion = skipped[installedModule.packageName] ?: skipped[module.packageName]
                if (skippedVersion == latest.versionCode) return@mapNotNull null
                if (isUpdate(latest, installedModule, module.latestTimeFor(channel))) {
                    UpdateInfo(module = module, installed = installedModule, latest = latest)
                } else {
                    null
                }
            }
            .sortedByDescending { it.latest.versionCode }
            .toList()
    }

    /**
     * A higher versionCode is always an update. An equal versionCode only counts
     * when the author re-uploaded the release after the installed copy landed
     * (LSPosed treats "same code, different name" as an update, which is right
     * for re-uploads but loops forever on pure naming drift such as
     * `Cleaner.Royall` tag `4-4.7_AraafRoyall` vs APK `4.8`).
     *
     * The timestamp keeps both properties: the first check after a re-upload
     * reports it once, and the prompt disappears the moment it is installed.
     */
    private fun isUpdate(
        latest: ModuleVersion,
        installed: InstalledModule,
        publishedRaw: String?,
    ): Boolean {
        if (latest.versionCode != installed.versionCode) {
            return latest.versionCode > installed.versionCode
        }
        val sameName = installed.versionName.orEmpty().replace(' ', '_') ==
            latest.versionName.replace(' ', '_')
        if (sameName || installed.lastUpdateTime <= 0L) return false
        val published = publishedRaw
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.toEpochMilli()
            ?: return false
        return published > installed.lastUpdateTime
    }
}
