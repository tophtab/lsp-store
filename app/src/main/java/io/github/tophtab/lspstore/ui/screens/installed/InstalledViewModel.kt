package io.github.tophtab.lspstore.ui.screens.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.ApkGate
import io.github.tophtab.lspstore.data.install.GateResult
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.sortedByLabel
import io.github.tophtab.lspstore.domain.usecase.BatchFailure
import io.github.tophtab.lspstore.domain.usecase.BatchFailureKind
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.domain.usecase.PreparedInstall
import io.github.tophtab.lspstore.domain.usecase.UpdateChecker
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onEvaluatedNoCandidate
import io.github.tophtab.lspstore.domain.variant.onUserPicked
import io.github.tophtab.lspstore.domain.variant.parseTraits
import io.github.tophtab.lspstore.ui.RefreshScrollGate
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import java.io.File

/** How many failed modules a batch summary names before it stops listing. */
private const val MAX_LISTED_FAILURES = 3

data class InstalledVariantPicker(
    val repoPackageName: String,
    val title: String,
    val selectedAssetName: String?,
    val candidates: List<ScoredAsset> = emptyList(),
    val needsConfirm: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
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
    /** Repository packages whose updates follow pre-releases too. */
    val preReleases: Set<String> = emptySet(),
    /** Repository packages that publish pre-releases at all (menu visibility). */
    val preReleaseCapable: Set<String> = emptySet(),
    val ignoredUpdates: Set<String> = emptySet(),
    val variantStates: Map<String, ModuleVariantState> = emptyMap(),
    val variantPicker: InstalledVariantPicker? = null,
    val busyPackage: String? = null,
    val batchProgress: String? = null,
    val batchItems: Map<String, BatchInstallState> = emptyMap(),
    val message: String? = null,
    /**
     * Bumped whenever a load finishes, silent startup refresh included, so the
     * list jumps back to the top: the newest updates are the reason the refresh
     * exists, and leaving the viewport where it was made it look like a no-op.
     */
    val refreshTick: Int = 0,
)

class InstalledViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository
    private val pendingRepository: PendingInstallRepository = container.pendingInstallRepository
    private val installer = container.installer

    private val prepareInstall = container.prepareModuleInstallUseCase
    private val installApk = container.installPreparedApkUseCase
    private val batchInstall = container.batchInstallUseCase

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
    private var batchJob: Job? = null
    private var loadJob: Job? = null
    private val refreshScrollGate = RefreshScrollGate()

    /**
     * Invalidates state writes from a cancelled/replaced batch. Batch
     * coroutines run concurrently and may be cancelled while they are still
     * reporting progress.
     */
    @Volatile
    private var batchGeneration: Int = 0

    init {
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
                        busyPackage = null,
                        // During a batch these arrive once per module; the batch
                        // owns the status UI, so don't spam the snackbar.
                        message = if (it.batchProgress == null) {
                            container.appContext.getString(R.string.detail_install_finished)
                        } else {
                            it.message
                        },
                    )
                }
            }
        }
        load(force = false)
    }

    fun refresh() = load(force = true)

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

    /**
     * "Failures: 1" on its own told the user nothing they could act on. The
     * reasons now ride along in the same transient message, one line per
     * module. The list is capped so the toast stays readable; the count in the
     * first line always states the real total.
     */
    private fun failureMessage(failures: List<BatchFailure>, failedCount: Int): String {
        val header = container.appContext.getString(R.string.installed_finished_failures, failedCount)
        if (failures.isEmpty()) return header
        return buildString {
            append(header)
            failures.take(MAX_LISTED_FAILURES).forEach { failure ->
                append('\n')
                append(failure.title)
                append(": ")
                append(batchFailureReason(failure))
            }
        }
    }

    private fun batchFailureReason(failure: BatchFailure): String = when (failure.kind) {
        BatchFailureKind.DOWNLOAD -> failure.detail
            ?: container.appContext.getString(R.string.installed_batch_failure_download)
        BatchFailureKind.INSTALL -> failure.detail
            ?: container.appContext.getString(R.string.error_install_failed)
    }
    private fun includePreReleases(info: UpdateInfo): Boolean =
        info.module.packageName in preReleases || info.installed.packageName in preReleases

    fun update(info: UpdateInfo) {
        if (_state.value.busyPackage != null || _state.value.batchProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyPackage = info.module.packageName, message = null) }
            val prepared = prepareInstall.prepare(
                info = info,
                includePreReleases = includePreReleases(info),
                state = variantStateFor(info),
                onAssetResolved = {},
                onProgress = { _, _ -> },
            )
            if (prepared is PreparedInstall.NeedsVariant) {
                // No remembered choice and the name layer is ambiguous: ask.
                _state.update { it.copy(busyPackage = null) }
                openVariantPicker(info.module.packageName)
                _state.update {
                    it.copy(message = container.appContext.getString(R.string.installed_choose_variant))
                }
                return@launch
            }
            val outcome = when (prepared) {
                is PreparedInstall.Ready -> installApk.install(prepared)
                is PreparedInstall.Failed -> InstallOutcome.Failure(prepared.message)
                is PreparedInstall.NeedsVariant -> InstallOutcome.Failure("")
            }
            reloadInstalled()
            _state.update { it.copy(busyPackage = null, message = describe(outcome, info)) }
        }
    }

    /**
     * Downloads run concurrently, installs stay serialized (installer dialogs
     * must not stack), and the install queue follows download completion order
     * rather than repository order. A slow download therefore no longer holds up
     * modules whose APKs are already on disk. The queue itself lives in
     * [BatchInstallUseCase]; this method only maps its progress to UI state.
     */
    fun updateAll() {
        val targets = _state.value.updates
        if (targets.isEmpty() || _state.value.batchProgress != null) return
        val generation = ++batchGeneration
        val total = targets.size
        val initialItems = targets.associate {
            it.module.packageName to BatchInstallState(phase = BatchInstallPhase.QUEUED)
        }
        // Publish the batch before launching so a second tap cannot start it again.
        _state.update {
            it.copy(
                batchProgress = container.appContext.getString(
                    R.string.installed_batch_progress,
                    1,
                    total,
                    targets.first().module.title,
                ),
                batchItems = initialItems,
                message = null,
            )
        }
        batchJob = viewModelScope.launch {
            val result = batchInstall.run(
                targets = targets,
                includePreReleases = { packageName -> preReleases.contains(packageName) },
                variantStates = variantStates,
                isActive = { isCurrentBatch(generation) },
                onItem = { packageName, transform ->
                    updateBatchItem(generation, packageName, transform)
                },
                onCurrent = { info, all ->
                    showBatchInstallProgress(generation, info, all)
                },
            )
            if (result.installInterrupted) {
                // Whatever the worker never dequeued stays installed at its old
                // version; report it as skipped so the summary is honest.
                _state.value.batchItems.keys.forEach { packageName ->
                    updateBatchItem(generation, packageName) { item ->
                        when (item.phase) {
                            BatchInstallPhase.QUEUED,
                            BatchInstallPhase.DOWNLOADING,
                            BatchInstallPhase.WAITING_TO_INSTALL,
                            -> item.copy(phase = BatchInstallPhase.SKIPPED)

                            else -> item
                        }
                    }
                }
            }
            reloadInstalled()
            if (isCurrentBatch(generation)) {
                val items = _state.value.batchItems.values
                val failures = items.count { it.phase == BatchInstallPhase.FAILED }
                val skipped = items.count { it.phase == BatchInstallPhase.SKIPPED }
                val message = when {
                    result.installInterrupted -> container.appContext.getString(
                        R.string.installed_batch_installer_busy,
                        skipped,
                    )

                    failures > 0 -> failureMessage(result.failures, failures)

                    skipped > 0 -> container.appContext.getString(
                        R.string.installed_finished_skipped,
                        skipped,
                    )

                    else -> container.appContext.getString(R.string.installed_all_updated)
                }
                _state.update {
                    it.copy(
                        message = message,
                        batchProgress = null,
                        batchItems = emptyMap(),
                    )
                }
                batchJob = null
            }
        }
    }

    fun cancelBatch() {
        val job = batchJob ?: return
        // Invalidate the old job before cancelling: its cleanup must not wipe the
        // progress of a batch the user starts right after this one.
        batchGeneration++
        batchJob = null
        job.cancel()
        _state.update {
            it.copy(
                batchProgress = null,
                batchItems = emptyMap(),
                message = container.appContext.getString(R.string.installed_batch_cancelled),
            )
        }
    }

    private fun isCurrentBatch(generation: Int): Boolean = batchGeneration == generation

    private fun updateBatchItem(
        generation: Int,
        packageName: String,
        transform: (BatchInstallState) -> BatchInstallState,
    ) {
        if (!isCurrentBatch(generation)) return
        _state.update { current ->
            if (!isCurrentBatch(generation)) return@update current
            val item = current.batchItems[packageName] ?: return@update current
            current.copy(batchItems = current.batchItems + (packageName to transform(item)))
        }
    }

    private fun showBatchInstallProgress(
        generation: Int,
        info: UpdateInfo,
        total: Int,
    ) {
        if (!isCurrentBatch(generation)) return
        val finished = _state.value.batchItems.values.count {
            it.phase == BatchInstallPhase.COMPLETED || it.phase == BatchInstallPhase.FAILED
        }
        _state.update { current ->
            if (!isCurrentBatch(generation)) return@update current
            current.copy(
                batchProgress = container.appContext.getString(
                    R.string.installed_batch_progress,
                    (finished + 1).coerceAtMost(total),
                    total,
                    info.module.title,
                ),
            )
        }
    }

    private fun variantStateFor(info: UpdateInfo): ModuleVariantState =
        variantStates[info.module.packageName]
            ?: variantStates[info.installed.packageName]
            ?: ModuleVariantState()

    private fun signatureOf(asset: ReleaseAsset): VariantSignature =
        VariantSignature.of(parseTraits(asset.name))

    private fun describe(outcome: InstallOutcome, info: UpdateInfo): String? = when (outcome) {
        InstallOutcome.Completed -> container.appContext.getString(
            R.string.installed_updated_one,
            info.module.title,
            info.latest.versionName,
        )
        // Like komi-store, a delegated install is shown as a pending row rather
        // than a snackbar; the package broadcast reports completion.
        InstallOutcome.DelegatedToSystem -> null
        is InstallOutcome.Failure -> outcome.message.ifBlank {
            container.appContext.getString(R.string.error_install_failed)
        }
    }

    private suspend fun reloadInstalled() {
        val modules = installedRepository.getInstalledModules(force = true)
        container.prunePendingInstalls(modules)
        installed = modules
        recompute()
    }

    fun dismissPending(packageName: String) {
        viewModelScope.launch { pendingRepository.clear(packageName) }
    }

    /** Loads the latest release's variants so the user can pin the one updates use. */
    fun openVariantPicker(repoPackageName: String) {
        if (_state.value.variantPicker != null) return
        val title = index.firstOrNull { it.packageName == repoPackageName }?.title
            ?: installed.firstOrNull { it.packageName == repoPackageName }?.label
            ?: repoPackageName
        _state.update {
            it.copy(
                variantPicker = InstalledVariantPicker(
                    repoPackageName = repoPackageName,
                    title = title,
                    selectedAssetName = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { moduleRepository.getDetail(repoPackageName) }
                .onSuccess { detail ->
                    val release = detail.latestReleaseFor(repoPackageName in preReleases)
                    if (release == null) {
                        _state.update { current ->
                            val picker = current.variantPicker ?: return@update current
                            current.copy(
                                variantPicker = picker.copy(
                                    isLoading = false,
                                    error = container.appContext.getString(
                                        R.string.installed_variant_empty,
                                    ),
                                ),
                            )
                        }
                        return@launch
                    }
                    val resolver = VariantResolver(container.deviceProfile)
                    val state = variantStates[repoPackageName] ?: ModuleVariantState()
                    // The user explicitly opened the picker: probing is allowed
                    // even when the module is currently NOT_APPLICABLE.
                    val resolution = container.resolveVariantUseCase.resolve(
                        release = release,
                        state = state,
                        device = container.deviceProfile,
                        expectedPackage = repoPackageName,
                        forceProbe = true,
                    )
                    val recommendedName = when (val decision = resolution.decision) {
                        is VariantDecision.Pick -> decision.asset.name
                        is VariantDecision.Choose ->
                            decision.candidates.firstOrNull { it.recommended }?.asset?.name

                        else -> null
                    }
                    val candidates = resolver.candidates(
                        release,
                        probes = resolution.probes,
                        expectedPackage = repoPackageName,
                    ).map { it.copy(recommended = it.asset.name == recommendedName) }
                    val selectedName = state.signature?.let { signature ->
                        candidates.firstOrNull { signature.matches(it.traits) }?.asset?.name
                    }
                    _state.update { current ->
                        val picker = current.variantPicker ?: return@update current
                        if (picker.repoPackageName != repoPackageName) return@update current
                        current.copy(
                            variantPicker = picker.copy(
                                candidates = candidates,
                                selectedAssetName = selectedName,
                                needsConfirm = resolution.decision is VariantDecision.Choose,
                                isLoading = false,
                                error = if (candidates.isEmpty()) {
                                    container.appContext.getString(R.string.installed_variant_empty)
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                }
                .onFailure {
                    _state.update { current ->
                        val picker = current.variantPicker ?: return@update current
                        if (picker.repoPackageName != repoPackageName) return@update current
                        current.copy(
                            variantPicker = picker.copy(
                                isLoading = false,
                                error = container.appContext.getString(
                                    R.string.installed_variant_load_failed,
                                ),
                            ),
                        )
                    }
                }
        }
    }

    fun selectVariant(assetName: String?) {
        val picker = _state.value.variantPicker ?: return
        if (picker.isLoading || picker.error != null) return
        val normalized = assetName?.takeIf { name ->
            picker.candidates.any { it.asset.name == name && it.blocked == null }
        }
        viewModelScope.launch {
            val asset = normalized?.let { name ->
                picker.candidates.firstOrNull { it.asset.name == name }?.asset
            }
            if (asset == null) {
                libraryRepository.clearVariantState(picker.repoPackageName)
            } else {
                libraryRepository.setVariantState(
                    picker.repoPackageName,
                    ModuleVariantState().onUserPicked(signatureOf(asset)),
                )
            }
            _state.update {
                it.copy(
                    variantPicker = null,
                    message = container.appContext.getString(
                        if (asset == null) {
                            R.string.installed_variant_auto_saved
                        } else {
                            R.string.installed_variant_saved
                        },
                    ),
                )
            }
        }
    }

    fun dismissVariantPicker() {
        _state.update { it.copy(variantPicker = null) }
    }

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
        val enabled = repoPackageName !in preReleases
        viewModelScope.launch {
            libraryRepository.setPreReleasesEnabled(repoPackageName, enabled)
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
        val xposed = installed.filter { it.isXposedModule }
        val pendingPackages = pending.keys
        val repoPackageByInstalled = xposed.mapNotNull { module ->
            val repo = byPackage[module.packageName] ?: links[module.packageName]?.let(byPackage::get)
            repo?.let { module.packageName to it.packageName }
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

        _state.update {
            it.copy(
                preReleases = preReleases,
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
            var paintedFromCache = false

            // 1. Paint the repository index and installed list saved by a
            // previous session immediately.
            if (index.isEmpty()) {
                val cachedIndex = moduleRepository.getCachedIndex()
                if (cachedIndex != null) {
                    index = cachedIndex
                    installedRepository.getCachedInstalledModules()?.let { installed = it }
                    paintedFromCache = true
                    _state.update {
                        it.copy(
                            isLoading = false,
                            // Automatic startup refresh is silent; only a user
                            // pull-to-refresh shows the spinner.
                            isRefreshing = force,
                            error = null,
                        )
                    }
                    recompute()
                }
            }

            if (!paintedFromCache) {
                _state.update {
                    it.copy(
                        isLoading = index.isEmpty(),
                        isRefreshing = force && index.isNotEmpty(),
                        error = null,
                    )
                }
            }

            // 2. Scan installed packages and refresh the repository in parallel.
            // PackageManager is local, but scanning hundreds of packages can still
            // take a while; the network should not wait behind it.
            launch {
                runCatching { installedRepository.getInstalledModules(force) }
                    .onSuccess { installedModules ->
                        container.prunePendingInstalls(installedModules)
                        installed = installedModules
                        recompute()
                    }
            }

            // 3. Refresh the repository in the background of the cached UI.
            // The first screen of the session performs the fetch; later screens
            // reuse the refreshed in-memory index. Pull-to-refresh forces it.
            runCatching { moduleRepository.refreshIndexIfNeeded(force) }
                .onSuccess { modules ->
                    index = modules
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            refreshTick = it.refreshTick + 1,
                        )
                    }
                    recompute()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            // Keep showing cached content when the refresh fails.
                            error = if (index.isEmpty()) {
                                error.toUserMessage(
                                    container.appContext,
                                    R.string.installed_check_failed,
                                )
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { InstalledViewModel(container) }
        }
    }
}
