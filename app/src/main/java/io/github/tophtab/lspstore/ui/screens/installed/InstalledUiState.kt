package io.github.tophtab.lspstore.ui.screens.installed

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.searchTerms
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.domain.usecase.InstalledListSnapshot
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.ScoredAsset

data class InstalledVariantPicker(
    val repoPackageName: String,
    val title: String,
    val selectedAssetName: String?,
    val candidates: List<ScoredAsset> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set when the picker was opened by an update tap; picking continues that update. */
    val caller: UpdateInfo? = null,
    val release: Release? = null,
)

data class InstalledUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updates: List<UpdateInfo> = emptyList(),
    val pendingInstalls: List<PendingInstall> = emptyList(),
    val upToDate: List<InstalledModule> = emptyList(),
    val unknown: List<InstalledModule> = emptyList(),
    val repoPackageByInstalled: Map<String, String> = emptyMap(),
    /** Repository entry behind each installed copy, so search can use its title, package and summary. */
    val repoModulesByInstalled: Map<String, Module> = emptyMap(),
    val installedCount: Int = 0,
    /** Mirrors the root switch: a silent uninstall asks for confirmation first. */
    val rootInstall: Boolean = false,
    /** Repository packages whose updates follow pre-releases too. */
    val preReleases: Set<String> = emptySet(),
    /** Repository packages that publish pre-releases at all (menu visibility). */
    val preReleaseCapable: Set<String> = emptySet(),
    val ignoredUpdates: Set<String> = emptySet(),
    val variantStates: Map<String, ModuleVariantState> = emptyMap(),
    val variantPicker: InstalledVariantPicker? = null,
    /** Row states owned by the update run started on this screen. */
    val rowStates: Map<String, BatchInstallState> = emptyMap(),
    /** Installs started from a module page, mirrored so the row shows them too. */
    val detailRuns: Map<String, BatchInstallState> = emptyMap(),
    val batchRunning: Boolean = false,
    val batchTotal: Int = 0,
    val message: String? = null,
    /**
     * Bumped whenever a load finishes, including the first load of the process.
     * A completed load is a new dataset, so the screen shows it from its first
     * item instead of keeping an offset that belonged to the previous data.
     */
    val refreshTick: Int = 0,
)

/** Controllers retain byte counters; the page only observes structural changes. */
internal fun InstalledUiState.withoutProgress(): InstalledUiState = copy(
    rowStates = rowStates.mapValues { (_, state) -> state.copy(downloaded = 0, total = 0) },
    detailRuns = detailRuns.mapValues { (_, state) -> state.copy(downloaded = 0, total = 0) },
)

internal fun InstalledUiState.withSnapshot(snapshot: InstalledListSnapshot): InstalledUiState = copy(
    updates = snapshot.updates,
    pendingInstalls = snapshot.pendingInstalls,
    upToDate = snapshot.upToDate,
    unknown = snapshot.unknown,
    repoPackageByInstalled = snapshot.repoPackageByInstalled,
    repoModulesByInstalled = snapshot.repoModulesByInstalled,
    installedCount = snapshot.installedCount,
    preReleases = snapshot.preReleases,
    preReleaseCapable = snapshot.preReleaseCapable,
    ignoredUpdates = snapshot.ignoredUpdates,
    variantStates = snapshot.variantStates,
    refreshTick = snapshot.refreshTick,
)

/**
 * One rule for every installed-page row: a term matches the repository entry's
 * title, package or summary, or the installed copy's label, package or
 * description. Terms may draw from either side, so `english 小黑盒` can combine
 * the installed label with the repository title. When a link renamed the
 * package, both package names are searched.
 */
private fun installedRowMatches(
    terms: List<String>,
    repository: Module?,
    installed: InstalledModule,
): Boolean =
    terms.isNotEmpty() && terms.all { term ->
        repository?.title?.contains(term, ignoreCase = true) == true ||
            repository?.packageName?.contains(term, ignoreCase = true) == true ||
            repository?.summary?.contains(term, ignoreCase = true) == true ||
            installed.label.contains(term, ignoreCase = true) ||
            installed.packageName.contains(term, ignoreCase = true) ||
            installed.description?.contains(term, ignoreCase = true) == true
    }

/** Installed rows use [installedRowMatches] with the repository entry they link to, if any. */
internal fun List<InstalledModule>.matching(
    query: String,
    repository: Map<String, Module> = emptyMap(),
): List<InstalledModule> {
    val terms = searchTerms(query)
    return if (terms.isEmpty()) this else filter { module ->
        installedRowMatches(terms, repository[module.packageName], module)
    }
}

/**
 * Update rows use the same [installedRowMatches] rule as every other installed
 * row. [completed] rows stay hidden even when they match.
 */
internal fun List<UpdateInfo>.matching(query: String, completed: Set<String>): List<UpdateInfo> {
    val terms = searchTerms(query)
    return filter { info ->
        info.module.packageName !in completed &&
            (terms.isEmpty() || installedRowMatches(terms, info.module, info.installed))
    }
}
