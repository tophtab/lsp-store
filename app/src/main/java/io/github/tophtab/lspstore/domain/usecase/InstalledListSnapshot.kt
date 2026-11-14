package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.InstalledCatalog
import io.github.tophtab.lspstore.domain.model.InstalledCopies
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.RepositoryIndex
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.sortedByLabel
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

data class InstalledListPreferences(
    val skipped: Map<String, SkippedVersion> = emptyMap(),
    val preReleases: Set<String> = emptySet(),
    val ignoredUpdates: Set<String> = emptySet(),
    val variantStates: Map<String, ModuleVariantState> = emptyMap(),
    val pending: Map<String, PendingInstall> = emptyMap(),
)

data class InstalledListSnapshot(
    val updates: List<UpdateInfo>,
    val pendingInstalls: List<PendingInstall>,
    val upToDate: List<InstalledModule>,
    val unknown: List<InstalledModule>,
    val repoPackageByInstalled: Map<String, String>,
    val installedByRepo: Map<String, InstalledModule>,
    val installedCount: Int,
    val preReleases: Set<String>,
    val preReleaseCapable: Set<String>,
    val ignoredUpdates: Set<String>,
    val variantStates: Map<String, ModuleVariantState>,
    val refreshTick: Int,
) {
    companion object {
        fun build(catalog: InstalledCatalog, preferences: InstalledListPreferences): InstalledListSnapshot {
            val updates = UpdateChecker.findUpdates(
                catalog, preferences.preReleases, preferences.skipped, preferences.ignoredUpdates,
            ).filterNot { it.installed.packageName in preferences.pending }
            val updating = updates.map { it.module.packageName }.toSet()
            val upToDate = mutableListOf<InstalledModule>()
            val unknown = mutableListOf<InstalledModule>()
            catalog.modules.forEach { installed ->
                val repo = catalog.repoByInstalled[installed.packageName]
                if (repo == null) unknown += installed
                else if (repo.packageName !in updating) upToDate += installed
            }
            return InstalledListSnapshot(
                updates = updates,
                pendingInstalls = preferences.pending.values.sortedByDescending { it.createdAt },
                upToDate = upToDate.sortedByLabel(),
                unknown = unknown.sortedByLabel(),
                repoPackageByInstalled = catalog.repoByInstalled.mapValues { it.value.packageName },
                installedByRepo = catalog.repoByInstalled.map { (installed, module) ->
                    module.packageName to catalog.copies.byPackage.getValue(installed)
                }.toMap(),
                installedCount = catalog.modules.size,
                preReleases = preferences.preReleases +
                    catalog.copies.links.filterKeys { it in preferences.preReleases }.values,
                preReleaseCapable = catalog.repository.preReleaseCapable,
                ignoredUpdates = preferences.ignoredUpdates,
                variantStates = preferences.variantStates,
                refreshTick = catalog.repository.refreshTick,
            )
        }
    }
}

/** Cancels obsolete calculations before they can publish a page snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
fun installedListSnapshots(
    repository: Flow<RepositoryIndex>,
    copies: Flow<InstalledCopies>,
    preferences: Flow<InstalledListPreferences>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<InstalledListSnapshot> = combine(repository, copies, preferences) { index, installed, settings ->
    Triple(index, installed, settings)
}.mapLatest { (index, installed, settings) ->
    withContext(dispatcher) {
        InstalledListSnapshot.build(InstalledCatalog.build(index, installed, settings.variantStates), settings)
    }
}
