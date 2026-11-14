package io.github.tophtab.lspstore.ui.screens.installed

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
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
