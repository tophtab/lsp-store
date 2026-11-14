package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.ConfirmedInstallRecord
import io.github.tophtab.lspstore.domain.model.InstalledCatalog
import io.github.tophtab.lspstore.domain.model.InstalledCopies
import java.time.Instant
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.RepositoryIndex
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

object UpdateChecker {

    // Remembered variants affect asset selection, never update visibility.
    fun findUpdates(
        installed: List<InstalledModule>,
        index: List<Module>,
        preReleases: Set<String> = emptySet(),
        skipped: Map<String, SkippedVersion> = emptyMap(),
        links: Map<String, String> = emptyMap(),
        ignored: Set<String> = emptySet(),
        variantStates: Map<String, ModuleVariantState> = emptyMap(),
    ): List<UpdateInfo> {
        return findUpdates(
            InstalledCatalog.build(RepositoryIndex.build(index), InstalledCopies.build(installed, links), variantStates),
            preReleases, skipped, ignored,
        )
    }

    /** Reuses the page's catalog and installed-copy grouping. */
    fun findUpdates(
        catalog: InstalledCatalog,
        preReleases: Set<String> = emptySet(),
        skipped: Map<String, SkippedVersion> = emptyMap(),
        ignored: Set<String> = emptySet(),
    ): List<UpdateInfo> = catalog.modules.asSequence()
            .mapNotNull { installedModule ->
                val module = catalog.repoByInstalled[installedModule.packageName] ?: return@mapNotNull null
                // Skipped/ignored lists may be keyed by either the installed package
                // or the linked repository package. Honour both.
                if (installedModule.packageName in ignored || module.packageName in ignored) {
                    return@mapNotNull null
                }
                // Pre-release tracking is per module and keyed by either the
                // installed or the linked repository package.
                val includePreReleases = preReleases.tracksPreReleases(
                    installedPackage = installedModule.packageName,
                    repoPackage = module.packageName,
                )
                val latest = module.latestFor(includePreReleases) ?: return@mapNotNull null
                val skippedVersion = skipped[installedModule.packageName] ?: skipped[module.packageName]
                if (skippedVersion?.matches(latest) == true) return@mapNotNull null
                if (isUpdateAvailable(latest, installedModule, module.latestTimeFor(includePreReleases), catalog.variantState(module)?.confirmedInstall)) {
                    UpdateInfo(module = module, installed = installedModule, latest = latest)
                } else {
                    null
                }
            }
            .sortedByDescending { it.latest.versionCode }
            .toList()

    /**
     * Whether [latest] is an update over the installed copy.
     *
     * A higher versionCode is always an update. An equal versionCode only counts
     * when the author re-uploaded the release after the installed copy landed
     * (LSPosed treats "same code, different name" as an update, which is right
     * for re-uploads but loops forever on pure naming drift such as
     * `Cleaner.Royall` tag `4-4.7_AraafRoyall` vs APK `4.8`).
     *
     * The timestamp keeps both properties: the first check after a re-upload
     * reports it once, and the prompt disappears the moment it is installed.
     *
     * Public because the module page asks the same question for its "update
     * available" hint and the two answers must not drift apart.
     */
    fun isUpdateAvailable(
        latest: ModuleVersion,
        installed: InstalledModule,
        publishedRaw: String?,
        confirmed: ConfirmedInstallRecord? = null,
    ): Boolean {
        if (confirmed?.matches(installed) == true) {
            val source = confirmed.target
            val published = publishedRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val previous = source.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (latest.raw == source.releaseTag && published != null && previous != null) return published > previous
            val version = source.releaseVersion
            if (version != null) {
                if (latest.versionCode != version.versionCode) return latest.versionCode > version.versionCode
                if (published != null && previous != null) return published > previous
            }
        }
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
