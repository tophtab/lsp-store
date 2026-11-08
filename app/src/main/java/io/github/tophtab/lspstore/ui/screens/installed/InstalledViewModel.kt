package io.github.tophtab.lspstore.ui.screens.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.data.install.UninstallOutcome
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
import io.github.tophtab.lspstore.domain.usecase.BatchInstallControl
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
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
import kotlin.coroutines.cancellation.CancellationException

/** How many failed modules a batch summary names before it stops listing. */
private const val MAX_LISTED_FAILURES = 3

/** How long a finished row keeps its check before the row disappears. */
private const val COMPLETED_NOTICE_MS = 1_200L

/** How long a failed row keeps the failure mark before returning to Update. */
private const val FAILED_NOTICE_MS = 3_000L

/** How often a visible screen re-checks pending installs that never landed. */
private const val PENDING_RECONCILE_MS = 30_000L

/** Row phases owned by a running chain; a tap on such a row must be ignored. */
private val ACTIVE_PHASES = setOf(
    BatchInstallPhase.QUEUED,
    BatchInstallPhase.PREPARING,
    BatchInstallPhase.DOWNLOADING,
    BatchInstallPhase.WAITING_TO_INSTALL,
    BatchInstallPhase.INSTALLING,
)

data class InstalledVariantPicker(
    val repoPackageName: String,
    val title: String,
    val selectedAssetName: String?,
    val candidates: List<ScoredAsset> = emptyList(),
    val needsConfirm: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set when the picker was opened by an update tap; picking continues that update. */
    val caller: UpdateInfo? = null,
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

class InstalledViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val installedRepository = container.installedRepository
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository
    private val pendingRepository: PendingInstallRepository = container.pendingInstallRepository
    private val installer = container.installer

    private val batchInstall = container.batchInstallUseCase
    private val coordinator = container.moduleInstallCoordinator

    /**
     * The store's own package. Its update runs last in "update all": the
     * installer replaces the APK this process is running from, so every other
     * module must have reached the installer before that hand-off.
     */
    private val selfPackage = container.appContext.packageName

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
    private var batchControl: BatchInstallControl? = null
    private var loadJob: Job? = null
    /**
     * Packages whose single update is resolving its detail but has not reached
     * the coordinator yet. Without this, a rapid second tap (or "update all")
     * can start a second chain for the same package.
     */
    private val startingUpdates = mutableSetOf<String>()
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
                val mapped = runs.mapValues { (_, run) -> run.asRowState() }
                _state.update { it.copy(detailRuns = mapped) }
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
    fun update(info: UpdateInfo) {
        val packageName = info.module.packageName
        // A module page or an earlier tap may already own this package; the
        // coordinator check is synchronous, so a double tap cannot slip between
        // the row check and the chain start.
        if (_state.value.detailRuns.containsKey(packageName)) return
        if (coordinator.isRunning(packageName) || packageName in startingUpdates) return
        // A busy row of a running batch keeps its pill; retrying a row that
        // already reached a terminal phase (failed, skipped, cancelled) is fine.
        val row = _state.value.rowStates[packageName]
        if (row != null && row.phase in ACTIVE_PHASES) return
        startSingleRun(info)
    }

    /**
     * One module is just a one-target run, so the row pill, cancellation and
     * failure handling have a single implementation.
     */
    fun updateAll() {
        // A module page install, an in-flight single update or a busy batch row
        // already owns its package; starting a second chain for it here would
        // download and install it twice.
        val busy = _state.value.detailRuns.keys +
            _state.value.rowStates.filterValues { it.phase in ACTIVE_PHASES }.keys +
            coordinator.runningPackages() +
            startingUpdates
        val targets = _state.value.updates.filterNot {
            it.module.packageName in busy
        }
        if (targets.isEmpty() || _state.value.batchRunning) return
        startRun(targets = targets)
    }

    /**
     * Single updates run through the app-scoped install coordinator (the same
     * chain a module page uses) instead of the batch: one module downloading no
     * longer disables the other rows, and a dismissed installer never parks the
     * screen in a waiting state — the row simply falls back to Update, and the
     * pending reconciliation restores it once the hand-off is abandoned.
     */
    private fun startSingleRun(info: UpdateInfo) {
        val repoPackage = info.module.packageName
        startingUpdates += repoPackage
        viewModelScope.launch {
            try {
                runCatching { moduleRepository.getDetail(repoPackage) }
                    .onSuccess { detail ->
                        val release = detail.latestReleaseFor(repoPackage in preReleases)
                        if (release == null) {
                            _state.update {
                                it.copy(
                                    message = container.appContext.getString(
                                        R.string.error_no_apk_asset,
                                    ),
                                )
                            }
                            return@launch
                        }
                        if (!release.matchesVersion(info.latest)) {
                            // Same guard the batch path has: a stale mirror detail
                            // would otherwise install an older build and leave the
                            // update prompt in place.
                            _state.update {
                                it.copy(
                                    message = container.appContext.getString(
                                        R.string.error_repository_stale,
                                    ),
                                )
                            }
                            return@launch
                        }
                        val state = variantStates[repoPackage]
                            ?: variantStates[info.installed.packageName]
                            ?: ModuleVariantState()
                        val resolution = container.resolveVariantUseCase.resolve(
                            release = release,
                            state = state,
                            device = container.deviceProfile,
                            expectedPackage = info.installed.packageName,
                        )
                        when (val decision = resolution.decision) {
                            is VariantDecision.Pick -> {
                                val version = release.version
                                coordinator.start(
                                    ModuleInstallRequest(
                                        repoPackageName = repoPackage,
                                        title = info.module.title,
                                        asset = decision.asset,
                                        installedPackage = info.installed.packageName,
                                        versionCode = version?.versionCode
                                            ?: info.latest.versionCode,
                                        versionName = version?.versionName
                                            ?: info.latest.versionName,
                                    ),
                                )
                            }

                            is VariantDecision.Choose -> openVariantPicker(repoPackage, info)

                            is VariantDecision.NotApplicable -> {
                                libraryRepository.setVariantState(
                                    repoPackage,
                                    state.onEvaluatedNoCandidate(decision.reason),
                                )
                                _state.update {
                                    it.copy(
                                        message = container.appContext.variantReasonText(
                                            decision.reason,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        _state.update {
                            it.copy(
                                message = error.toUserMessage(
                                    container.appContext,
                                    R.string.installed_check_failed,
                                ),
                            )
                        }
                    }
            } finally {
                startingUpdates -= repoPackage
            }
        }
    }

    private fun startRun(targets: List<UpdateInfo>) {
        val generation = ++batchGeneration
        val total = targets.size
        val control = batchInstall.newControl()
        batchControl = control
        val initialItems = targets.associate {
            it.module.packageName to BatchInstallState(phase = BatchInstallPhase.QUEUED)
        }
        // Publish the run before launching so a second tap cannot start it again.
        _state.update {
            it.copy(
                rowStates = initialItems,
                batchRunning = true,
                batchTotal = total,
                message = null,
            )
        }
        batchJob = viewModelScope.launch {
            val result = batchInstall.run(
                targets = targets,
                control = control,
                includePreReleases = { packageName -> preReleases.contains(packageName) },
                variantStates = variantStates,
                isActive = { isCurrentBatch(generation) },
                installLast = { info ->
                    info.module.packageName == selfPackage ||
                        info.installed.packageName == selfPackage
                },
                onItem = { packageName, transform ->
                    applyItem(generation, packageName, transform)
                },
            )
            reloadInstalled()
            if (!isCurrentBatch(generation)) return@launch
            val items = _state.value.rowStates.values
            val failed = items.count { it.phase == BatchInstallPhase.FAILED }
            val skipped = items.count { it.phase == BatchInstallPhase.SKIPPED }
            val cancelled = items.count { it.phase == BatchInstallPhase.CANCELLED }
            val message = when {
                failed > 0 -> failureMessage(result.failures, failed)

                skipped > 0 -> container.appContext.getString(
                    R.string.installed_finished_skipped,
                    skipped,
                )

                // Per-row cancels are not failures, but "all modules
                // updated" would be a lie when some were stopped.
                cancelled > 0 -> container.appContext.getString(
                    R.string.installed_finished_cancelled,
                    cancelled,
                )

                else -> container.appContext.getString(R.string.installed_all_updated)
            }
            _state.update {
                it.copy(
                    message = message,
                    batchRunning = false,
                    batchTotal = 0,
                    rowStates = lingeringStates(it.rowStates),
                )
            }
            maybeClearRowStates()
            batchControl = null
            batchJob = null
        }
    }

    /** Cancels one module of the running update; the row returns to Update. */
    fun cancelRow(packageName: String) {
        batchControl?.cancel(packageName)
        // The same pill also fronts an install started from a module page, and
        // that chain lives in the coordinator rather than in this batch.
        coordinator.cancel(packageName)
    }

    fun cancelBatch() {
        val job = batchJob ?: return
        // Invalidate the old job before cancelling: its cleanup must not wipe the
        // progress of a batch the user starts right after this one.
        batchGeneration++
        batchJob = null
        batchControl?.cancelAll()
        batchControl = null
        job.cancel()
        _state.update {
            it.copy(
                rowStates = emptyMap(),
                batchRunning = false,
                batchTotal = 0,
                message = container.appContext.getString(R.string.installed_batch_cancelled),
            )
        }
    }

    private fun isCurrentBatch(generation: Int): Boolean = batchGeneration == generation

    private fun applyItem(
        generation: Int,
        packageName: String,
        transform: (BatchInstallState) -> BatchInstallState,
    ) {
        if (!isCurrentBatch(generation)) return
        val before = _state.value.rowStates[packageName]?.phase
        _state.update { current ->
            if (!isCurrentBatch(generation)) return@update current
            val item = current.rowStates[packageName] ?: return@update current
            current.copy(rowStates = current.rowStates + (packageName to transform(item)))
        }
        val after = _state.value.rowStates[packageName]?.phase
        if (after == null || after == before) return
        val noticeMs = when (after) {
            BatchInstallPhase.COMPLETED -> COMPLETED_NOTICE_MS
            BatchInstallPhase.FAILED -> FAILED_NOTICE_MS
            else -> return
        }
        viewModelScope.launch {
            delay(noticeMs)
            if (!isCurrentBatch(generation)) return@launch
            _state.update { current ->
                val item = current.rowStates[packageName] ?: return@update current
                if (item.phase != after) return@update current
                current.copy(
                    rowStates = current.rowStates + (
                        packageName to item.copy(noticeVisible = false)
                        ),
                )
            }
            maybeClearRowStates()
        }
    }

    /** Keeps only the terminal marks the user still needs to see. */
    private fun lingeringStates(
        states: Map<String, BatchInstallState>,
    ): Map<String, BatchInstallState> = states.filterValues {
        it.noticeVisible &&
            (it.phase == BatchInstallPhase.COMPLETED || it.phase == BatchInstallPhase.FAILED)
    }

    private fun maybeClearRowStates() {
        val current = _state.value
        if (current.batchRunning) return
        if (current.rowStates.values.any { it.noticeVisible }) return
        if (current.rowStates.isNotEmpty()) {
            _state.update { it.copy(rowStates = emptyMap()) }
        }
    }

    private fun ModuleInstallRun.asRowState(): BatchInstallState = BatchInstallState(
        phase = when (stage) {
            InstallStage.QUEUED -> BatchInstallPhase.QUEUED
            InstallStage.DOWNLOADING -> BatchInstallPhase.DOWNLOADING
            InstallStage.INSTALLING -> BatchInstallPhase.INSTALLING
        },
        downloaded = downloaded,
        total = total,
    )

    private fun signatureOf(asset: ReleaseAsset): VariantSignature =
        VariantSignature.of(parseTraits(asset.name))

    private suspend fun reloadInstalled() {
        val modules = installedRepository.getInstalledModules(force = true)
        container.prunePendingInstalls(modules)
        installed = modules
        recompute()
    }

    fun dismissPending(packageName: String) {
        viewModelScope.launch { pendingRepository.clear(packageName) }
    }

    /**
     * Uninstalls [packageName] from a row's menu. With the root switch on, su
     * removes the package in-process and the list refreshes without leaving the
     * screen; otherwise the system uninstaller takes over, and its confirmation
     * page is the report.
     */
    fun uninstall(packageName: String, label: String) {
        viewModelScope.launch {
            when (val outcome = container.uninstallModule(packageName)) {
                UninstallOutcome.Completed -> {
                    reloadInstalled()
                    _state.update {
                        it.copy(
                            message = container.appContext.getString(
                                R.string.installed_uninstalled,
                                label,
                            ),
                        )
                    }
                }

                UninstallOutcome.DelegatedToSystem -> Unit

                is UninstallOutcome.Failure ->
                    _state.update { it.copy(message = outcome.message) }
            }
        }
    }

    /** Loads the latest release's variants so the user can pin the one updates use. */
    fun openVariantPicker(repoPackageName: String, caller: UpdateInfo? = null) {
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
                    caller = caller,
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
            // Picking from an update row continues that update with the chosen
            // APK right away, instead of asking the user to tap Update again.
            val caller = picker.caller
            if (asset != null && caller != null) {
                coordinator.start(
                    ModuleInstallRequest(
                        repoPackageName = picker.repoPackageName,
                        title = caller.module.title,
                        asset = asset,
                        installedPackage = caller.installed.packageName,
                        versionCode = caller.latest.versionCode,
                        versionName = caller.latest.versionName,
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
        // The entry load is automatic, but it still hits the network on the
        // first screen of the session, so it gets the same indicator as a
        // manual refresh. A screen that reuses the in-memory index shows none.
        val showRefresh = force || !moduleRepository.hasFetchedIndexThisSession()
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
                            isRefreshing = showRefresh,
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
                        isRefreshing = showRefresh && index.isNotEmpty(),
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
                            // Clear a failure from an earlier attempt, so a
                            // successful retry never leaves the error box up.
                            error = null,
                            refreshTick = it.refreshTick + 1,
                        )
                    }
                    recompute()
                }
                .onFailure { error ->
                    // A cancelled load was replaced by the one that cancelled
                    // it. Writing "failed" here would flash its indicator off
                    // and could leave an error over data the new load fetches.
                    if (error is CancellationException) return@onFailure
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
