package io.github.tophtab.lspstore.ui.screens.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.model.installedCopyForRepo
import io.github.tophtab.lspstore.domain.model.repoModuleFor
import io.github.tophtab.lspstore.domain.model.sortedByLabel
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.usecase.UpdateChecker
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.ui.CatalogLoader
import io.github.tophtab.lspstore.ui.RefreshScrollGate
import io.github.tophtab.lspstore.ui.reportUninstallOutcome
import io.github.tophtab.lspstore.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How often a visible screen re-checks pending installs that never landed. */
private const val PENDING_RECONCILE_MS = 30_000L

class InstalledViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val catalogLoader = CatalogLoader(moduleRepository, installedRepository)
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository
    private val pendingRepository: PendingInstallRepository = container.pendingInstallRepository
    private val installer = container.installer

    private val coordinator = container.moduleInstallCoordinator

    private val _state = MutableStateFlow(InstalledUiState())
    val state = _state.asStateFlow()

    private var index: List<Module> = emptyList()
    private var installed: List<InstalledModule> = emptyList()
    private var skipped: Map<String, SkippedVersion> = emptyMap()
    private var ignoredUpdates: Set<String> = emptySet()
    private var links: Map<String, String> = emptyMap()
    private var variantStates: Map<String, ModuleVariantState> = emptyMap()
    private var preReleases: Set<String> = emptySet()
    private var pending: Map<String, PendingInstall> = emptyMap()
    private var loadJob: Job? = null
    private val refreshScrollGate = RefreshScrollGate()

    private val updateController = InstalledUpdateController(
        scope = viewModelScope,
        readState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        batchInstall = container.batchInstallUseCase,
        registry = container.installTaskRegistry,
        getDetail = moduleRepository::getDetail,
        resolveVariant = { release, state, installed ->
            container.resolveVariantUseCase.resolve(
                release = release,
                state = state,
                device = container.deviceProfile,
                expectedPackage = installed.packageName,
                installed = InstalledVariantHint.of(installed),
            )
        },
        recordFailure = { libraryRepository.recordInstallResult(it, ok = false) },
        startInstall = coordinator::start,
        cancelInstall = coordinator::cancel,
        reloadInstalled = ::reloadInstalled,
        selfPackage = container.appContext.packageName,
        text = installedUpdateText(container.appContext),
    )

    private val variantController = InstalledVariantController(
        scope = viewModelScope,
        readState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        getDetail = moduleRepository::getDetail,
        titleFor = { repoPackage ->
            index.firstOrNull { it.packageName == repoPackage }?.title
                ?: installed.firstOrNull { it.packageName == repoPackage }?.label
                ?: repoPackage
        },
        installedFor = { repoPackage ->
            installedCopyForRepo(repoPackage, installed, links, variantStates[repoPackage]?.activePackage)
        },
        resolveVariant = container.resolveVariantUseCase,
        device = container.deviceProfile,
        clearPreference = libraryRepository::clearVariantState,
        savePreference = libraryRepository::setExplicitPreference,
        startInstall = coordinator::start,
        text = InstalledVariantText(
            empty = { container.appContext.getString(R.string.installed_variant_empty) },
            loadFailed = { container.appContext.getString(R.string.installed_variant_load_failed) },
            saved = { automatic ->
                container.appContext.getString(
                    if (automatic) R.string.installed_variant_auto_saved else R.string.installed_variant_saved,
                )
            },
        ),
    )

    init {
        viewModelScope.launch {
            settingsRepository.rootInstall.collect { enabled ->
                _state.update { it.copy(rootInstall = enabled) }
            }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { value -> skipped = value; recompute() }
        }
        viewModelScope.launch {
            libraryRepository.preReleaseModules.collect { value -> preReleases = value; recompute() }
        }
        viewModelScope.launch {
            libraryRepository.ignoredUpdates.collect { value ->
                ignoredUpdates = value
                recompute()
            }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value -> links = value; recompute() }
        }
        viewModelScope.launch {
            libraryRepository.variantStates.collect { value ->
                variantStates = value
                recompute()
            }
        }
        viewModelScope.launch {
            // Persisted delegated installs, mirroring komi-store's
            // isPendingInstall flag on installed-app records.
            pendingRepository.pending.collect { value ->
                pending = value
                recompute()
            }
        }
        viewModelScope.launch {
            // Delegated installs (InstallerX, system installer, ...) finish in
            // another app. The completion signal (package broadcast, or the resume
            // reconcile) already recorded the variant outcome and cleared the
            // pending state; here we only refresh what is on screen.
            installer.installedPackages.collect { _ ->
                reloadInstalled()
                _state.update {
                    it.copy(
                        // During a batch these arrive once per module; the batch
                        // owns the status UI, so don't spam the snackbar.
                        message = if (!it.batchRunning) {
                            container.appContext.getString(R.string.detail_install_finished)
                        } else {
                            it.message
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            // Removals and external installs (another app, system settings) do
            // not complete a hand-off of ours, but the list should still follow
            // what is on the device. No snackbar: nothing was installed from here.
            installer.packageChanges.collect { reloadInstalled() }
        }
        viewModelScope.launch {
            // Installs started from a module page keep running after returning
            // here, so the row has to show their progress too (and not offer a
            // second update while they are active).
            coordinator.runs.collect { runs ->
                updateController.onRunsChanged(runs)
            }
        }
        viewModelScope.launch {
            // Single updates started from this screen ride the same coordinator
            // chain as module pages, so their outcome text lands here too.
            coordinator.messages.collect { message ->
                _state.update { it.copy(message = message) }
            }
        }
        viewModelScope.launch {
            // A delegated install whose installer dialog the user dismissed never
            // produces a package event. The resume hook only runs on foreground
            // transitions, so without a periodic re-check the pending entry would
            // keep the row's Update button hidden until the app is restarted.
            while (true) {
                delay(PENDING_RECONCILE_MS)
                if (pending.isEmpty()) continue
                val current = _state.value
                // A batch may still be waiting for its own hand-off; abandoning
                // the pending entry now would release the install slot too early.
                if (current.batchRunning || current.detailRuns.isNotEmpty()) continue
                container.reconcilePendingInstalls()
            }
        }
        load(force = false)
    }

    fun refresh() {
        load(force = true)
        // Pull-to-refresh is another chance to settle a hand-off the installer
        // never completed (same reconciliation the resume hook runs).
        viewModelScope.launch { container.reconcilePendingInstalls() }
    }

    /**
     * Whether the finished refresh that produced the current
     * [InstalledUiState.refreshTick] still owes the list a scroll back to the
     * top. Asking the view model (which outlives the composition) rather than
     * remembering the tick in the screen is what keeps a trip to a module page
     * from scrolling the list: the screen comes back as a fresh composition and
     * launches its effect with the same tick again.
     */
    fun consumeRefreshScroll(): Boolean = refreshScrollGate.consume(_state.value.refreshTick)

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun update(info: UpdateInfo) = updateController.update(info)

    fun updateAll() = updateController.updateAll()

    fun cancelRow(packageName: String) = updateController.cancelRow(packageName)

    fun cancelBatch() = updateController.cancelBatch()

    private suspend fun reloadInstalled() {
        // A package broadcast, an installer hand-off or a root uninstall can
        // land here while PackageManager is still settling, so a failed scan
        // must not escape into viewModelScope: that would crash the app over a
        // refresh the list performs on its own. Keep the current list instead.
        val modules = runCatching {
            installedRepository.getInstalledModules(force = true)
        }.getOrNull() ?: return
        container.prunePendingInstalls(modules)
        installed = modules
        recompute()
    }

    fun dismissPending(packageName: String) {
        viewModelScope.launch { container.cancelPendingInstall(packageName) }
    }

    /**
     * Uninstalls [packageName] from a row's menu. With the root switch on, su
     * removes the package in-process and the list refreshes without leaving the
     * screen; otherwise the system uninstaller takes over, and its confirmation
     * page is the report.
     */
    fun uninstall(packageName: String, label: String) {
        viewModelScope.launch {
            reportUninstallOutcome(
                outcome = container.uninstallModule(packageName),
                completedMessage = container.appContext.getString(R.string.installed_uninstalled, label),
                onCompleted = ::reloadInstalled,
                onMessage = { message -> _state.update { it.copy(message = message) } },
            )
        }
    }

    fun openVariantPicker(repoPackageName: String, caller: UpdateInfo? = null) =
        variantController.openVariantPicker(repoPackageName, caller)

    fun selectVariant(assetName: String?) = variantController.selectVariant(assetName)

    fun dismissVariantPicker() = variantController.dismissVariantPicker()

    /**
     * Skips only the version currently shown in [info]. Stored as the repository
     * tag, not the numeric code: many modules reuse one versionCode for every
     * release, and a code-wide skip would hide all future updates.
     */
    fun skipVersion(info: UpdateInfo) {
        viewModelScope.launch {
            libraryRepository.skipUpdate(info.module.packageName, info.latest)
            _state.update {
                it.copy(
                    message = container.appContext.getString(
                        R.string.installed_update_skipped,
                        info.module.title,
                    ),
                )
            }
        }
    }

    /**
     * Toggles pre-release tracking for one module: while it is on, the update
     * check follows that module's pre-release stream instead of only its stable
     * releases. When the stable build is newer than the newest pre-release it
     * still wins, so tracking pre-releases never hides a stable update.
     */
    fun togglePreReleases(repoPackageName: String) {
        val module = index.firstOrNull { it.packageName == repoPackageName } ?: return
        val installedPackage = installed.firstOrNull { it.packageName == repoPackageName }?.packageName
            ?: links.entries.firstOrNull { it.value == repoPackageName }?.key
        val enabled = !preReleases.tracksPreReleases(installedPackage, repoPackageName)
        viewModelScope.launch {
            libraryRepository.setPreReleasesEnabled(repoPackageName, enabled)
            if (installedPackage != null && installedPackage != repoPackageName) {
                // Older data may hold the preference under the installed package
                // name; a toggle must update both so the read-back cannot keep a
                // stale "on" entry.
                libraryRepository.setPreReleasesEnabled(installedPackage, false)
            }
            _state.update {
                it.copy(
                    message = container.appContext.getString(
                        if (enabled) {
                            R.string.installed_pre_releases_on
                        } else {
                            R.string.installed_pre_releases_off
                        },
                        module.title,
                    ),
                )
            }
        }
    }

    /**
     * Toggles the per-module update check. Ignored modules stay installed but
     * are left out of the updates list until the user restores them.
     */
    fun toggleIgnoreUpdate(repoPackageName: String) {
        val module = index.firstOrNull { it.packageName == repoPackageName } ?: return
        val installedPackage = installed.firstOrNull { it.packageName == repoPackageName }?.packageName
            ?: links.entries.firstOrNull { it.value == repoPackageName }?.key
            ?: repoPackageName
        val ignored = installedPackage in ignoredUpdates || repoPackageName in ignoredUpdates
        viewModelScope.launch {
            libraryRepository.setUpdateCheckEnabled(installedPackage, enabled = ignored)
            if (repoPackageName != installedPackage) {
                libraryRepository.setUpdateCheckEnabled(repoPackageName, enabled = ignored)
            }
            ignoredUpdates = if (ignored) {
                ignoredUpdates - installedPackage - repoPackageName
            } else {
                ignoredUpdates + installedPackage
            }
            _state.update {
                it.copy(
                    ignoredUpdates = ignoredUpdates,
                    message = container.appContext.getString(
                        if (ignored) {
                            R.string.installed_update_unignored
                        } else {
                            R.string.installed_update_ignored
                        },
                        module.title,
                    ),
                )
            }
            recompute()
        }
    }

    private fun recompute() {
        if (index.isEmpty() && installed.isEmpty() && pending.isEmpty()) return
        val byPackage = index.associateBy { it.packageName }
        val chosenPackages = variantStates.values.mapNotNull { it.activePackage }.toSet()
        val xposed = installed.filter {
            it.isXposedModule || it.packageName in links || it.packageName in chosenPackages
        }.filter { module ->
            val repo = repoModuleFor(module.packageName, byPackage, links)
            repo == null || installedCopyForRepo(
                repo.packageName, installed, links, variantStates[repo.packageName]?.activePackage,
            )?.packageName == module.packageName
        }
        val pendingPackages = pending.keys
        val repoPackageByInstalled = xposed.mapNotNull { module ->
            repoModuleFor(module.packageName, byPackage, links)
                ?.let { module.packageName to it.packageName }
        }.toMap()
        // Pending installs move out of updates/idle, as in komi-store's apps list.
        val updates = UpdateChecker
            .findUpdates(
                installed = installed,
                index = index,
                preReleases = preReleases,
                skipped = skipped,
                links = links,
                ignored = ignoredUpdates,
                variantStates = variantStates,
            )
            .filterNot { it.installed.packageName in pendingPackages }
        val updating = updates.map { it.module.packageName }.toSet()

        val upToDate = xposed.filter { module ->
            val repoPackage = repoPackageByInstalled[module.packageName] ?: return@filter false
            repoPackage !in updating
        }.sortedByLabel()
        val unknown = xposed.filter {
            it.packageName !in repoPackageByInstalled
        }.sortedByLabel()
        val pendingInstalls = pending.values
            .sortedByDescending { it.createdAt }

        // The UI keys rows by repository package. A legacy preference stored
        // under the installed package must still light the toggle, so project
        // those entries onto their linked repository module.
        val effectivePreReleases = preReleases +
            links.filterKeys { it in preReleases }.values

        _state.update {
            it.copy(
                preReleases = effectivePreReleases,
                preReleaseCapable = index.filter { it.hasPreReleases }.map { it.packageName }.toSet(),
                updates = updates,
                pendingInstalls = pendingInstalls,
                upToDate = upToDate,
                unknown = unknown,
                repoPackageByInstalled = repoPackageByInstalled,
                installedCount = xposed.size,
                ignoredUpdates = ignoredUpdates,
                variantStates = variantStates,
            )
        }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            catalogLoader.load(
                hasIndex = index.isNotEmpty(),
                forceRefresh = force,
                forceScan = force,
                onCache = { modules, cachedInstalled ->
                    index = modules
                    cachedInstalled?.let { installed = it }
                    recompute()
                },
                onLoading = { loading, refreshing ->
                    _state.update {
                        it.copy(isLoading = loading, isRefreshing = refreshing, error = null)
                    }
                },
                onScanned = { modules ->
                    container.prunePendingInstalls(modules)
                    currentCoroutineContext().ensureActive()
                    installed = modules
                    recompute()
                },
                onRefreshed = { modules ->
                    index = modules
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                            refreshTick = it.refreshTick + 1,
                        )
                    }
                    recompute()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = error?.toUserMessage(container.appContext, R.string.installed_check_failed),
                        )
                    }
                },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { InstalledViewModel(container) }
        }
    }
}
