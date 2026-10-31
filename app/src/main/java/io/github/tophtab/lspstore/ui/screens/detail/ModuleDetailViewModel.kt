package io.github.tophtab.lspstore.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onEvaluatedNoCandidate
import io.github.tophtab.lspstore.domain.variant.onUserPicked
import io.github.tophtab.lspstore.domain.variant.parseTraits

data class VariantPicker(
    val release: Release,
    val candidates: List<ScoredAsset>,
)

data class ModuleDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val detail: ModuleDetail? = null,
    val installed: InstalledModule? = null,
    /** Whether this module's update checks also follow pre-releases. */
    val preRelease: Boolean = false,
    /**
     * Stream shown on this page. The three buttons above the resource picker pick
     * it; a channel choice also updates [preRelease] for the streams that have an
     * update-check meaning.
     */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    val isFavourite: Boolean = false,
    val skippedVersion: SkippedVersion? = null,
    /** Resolving the variant: no download has started yet. */
    val isBusy: Boolean = false,
    /** Download/install started from this page, still running. */
    val activeInstall: ModuleInstallRun? = null,
    val message: String? = null,
    val translatedSummary: String? = null,
    val isTranslating: Boolean = false,
    val variantPicker: VariantPicker? = null,
    val variantState: ModuleVariantState? = null,
    /** P2 probe results for [probedTag], shown in the picker/sheet. */
    val probes: Map<String, ApkMeta> = emptyMap(),
    val probedTag: String? = null,
    val isProbing: Boolean = false,
    val pendingInstall: PendingInstall? = null,
    val installLogs: List<String> = emptyList(),
) {
    val latestVersion: ModuleVersion?
        get() = detail?.module?.latestFor(channel)

    val latestVersionCode: Long?
        get() = latestVersion?.versionCode

    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val latest = detail?.module?.latestFor(channel) ?: return false
            // Same rule as UpdateChecker: a versionName-only difference caused by
            // author naming is not an update.
            return latest.versionCode > installed.versionCode
        }

    val isUpdateSkipped: Boolean
        get() {
            val latest = latestVersion ?: return false
            return skippedVersion?.matches(latest) == true
        }
}

class ModuleDetailViewModel(
    private val container: AppContainer,
    private val packageName: String,
) : ViewModel() {

    private val moduleRepository: ModuleRepository = container.moduleRepository
    private val installedRepository: InstalledModulesRepository = container.installedRepository
    private val libraryRepository: LibraryRepository = container.libraryRepository
    private val pendingRepository: PendingInstallRepository = container.pendingInstallRepository
    private val installer = container.installer
    private val coordinator = container.moduleInstallCoordinator
    private val translationRepository = container.translationRepository
    private val settingsRepository = container.settingsRepository

    private val _state = MutableStateFlow(ModuleDetailUiState())
    val state = _state.asStateFlow()

    /** The user picked a channel; stop mirroring the persisted pre-release flag. */
    private var channelChosen = false
    private var links: Map<String, String> = emptyMap()
    private var variantStates: Map<String, ModuleVariantState> = emptyMap()
    private var pendingInstalls: Map<String, PendingInstall> = emptyMap()

    init {
        viewModelScope.launch {
            libraryRepository.favourites.collect { favourites ->
                _state.update { it.copy(isFavourite = packageName in favourites) }
            }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { skipped ->
                _state.update { it.copy(skippedVersion = skipped[packageName]) }
            }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value ->
                links = value
                resolveInstalled()
            }
        }
        viewModelScope.launch {
            libraryRepository.preReleaseModules.collect { value ->
                val tracked = packageName in value
                _state.update {
                    it.copy(
                        preRelease = tracked,
                        // Until the user touches the channel buttons, the page
                        // follows the persisted preference, so a module tracked
                        // for pre-releases opens on that stream.
                        channel = if (channelChosen) it.channel else defaultChannel(it.detail, tracked),
                    )
                }
            }
        }
        viewModelScope.launch {
            libraryRepository.variantStates.collect { value ->
                variantStates = value
                _state.update { it.copy(variantState = stateForModule(value, it.installed)) }
            }
        }
        viewModelScope.launch {
            // Persisted by PendingInstallRepositoryImpl: like komi-store, a
            // delegated install is a visible state until the package lands.
            pendingRepository.pending.collect { value ->
                pendingInstalls = value
                updatePendingState()
            }
        }
        viewModelScope.launch {
            container.prunePendingInstalls(installedRepository.getInstalledModules())
        }
        viewModelScope.launch {
            // The install chain lives on the app scope, so this screen can be
            // recreated (or stay closed) while it runs: whatever instance of the
            // page is open shows the same progress.
            coordinator.runs.collect { runs -> updateRunState(runs[packageName]) }
        }
        viewModelScope.launch {
            coordinator.messages.collect { message ->
                _state.update { it.copy(message = message) }
            }
        }
        viewModelScope.launch {
            // Delegated installs finish in another app; the package broadcast is
            // the completion signal (komi's PackageEventReceiver equivalent).
            installer.installedPackages.collect { installedPackage ->
                // The APK may declare a different package than the repository
                // entry (renamed module); the pending record carries that link.
                val expectedPackage = _state.value.installed?.packageName ?: packageName
                val knownTarget = installedPackage == expectedPackage ||
                    installedPackage == packageName ||
                    pendingInstalls[installedPackage]?.repoPackageName == packageName
                if (!knownTarget) return@collect
                // Records from the persisted pending entry (which survives death),
                // so a delegated install is remembered even if this screen was
                // recreated while the installer was in the foreground.
                container.completePendingInstall(installedPackage)
                val installed = findInstalled()
                _state.update {
                    it.copy(
                        installed = installed,
                        message = container.appContext.getString(R.string.detail_install_finished),
                    )
                }
                updatePendingState()
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = it.detail == null,
                    isRefreshing = it.detail != null,
                    error = null,
                    translatedSummary = null,
                )
            }
            runCatching {
                val detail = moduleRepository.getDetail(packageName)
                val installed = findInstalled()
                detail to installed
            }.onSuccess { (detail, installed) ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        detail = detail,
                        installed = installed,
                        variantState = stateForModule(variantStates, installed),
                        // The persisted opt-in may point at a stream this module
                        // no longer publishes; fall back to stable in that case.
                        channel = if (channelChosen) it.channel else defaultChannel(detail, it.preRelease),
                    )
                }
                updatePendingState()
                libraryRepository.addRecentlyViewed(packageName)
                maybeAutoTranslate()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.toUserMessage(container.appContext, R.string.detail_load_failed),
                    )
                }
            }
        }
    }

    /** Stream a page opens on when the user has not picked one yet. */
    private fun defaultChannel(detail: ModuleDetail?, tracked: Boolean): ReleaseChannel =
        if (tracked && detail?.module?.hasPreReleases != false) {
            ReleaseChannel.PRE_RELEASE
        } else {
            ReleaseChannel.STABLE
        }

    /**
     * Switches the stream the page shows (stable / pre-release / all).
     *
     * The choice is view state, but the two streams that update checks understand
     * also drive the persisted per-module pre-release opt-in, so the Installed tab
     * and background checks keep agreeing with what this page offers. "All" adds
     * snapshots, which update checks never follow, so it leaves the flag alone.
     */
    fun selectChannel(channel: ReleaseChannel) {
        val current = _state.value
        if (current.channel == channel) return
        val detail = current.detail
        // A module without pre-releases offers nothing there: the button is greyed
        // out, and this guard keeps a stale click from switching anyway.
        if (channel == ReleaseChannel.PRE_RELEASE && detail != null && !detail.module.hasPreReleases) return
        channelChosen = true
        _state.update { it.copy(channel = channel) }
        when (channel) {
            ReleaseChannel.STABLE -> if (current.preRelease) {
                viewModelScope.launch { libraryRepository.setPreReleasesEnabled(packageName, false) }
            }

            ReleaseChannel.PRE_RELEASE -> if (!current.preRelease) {
                viewModelScope.launch { libraryRepository.setPreReleasesEnabled(packageName, true) }
            }

            ReleaseChannel.ALL -> Unit
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    fun skipThisVersion() {
        val version = _state.value.latestVersion ?: return
        viewModelScope.launch { libraryRepository.skipUpdate(packageName, version) }
    }

    fun unskipVersion() {
        viewModelScope.launch { libraryRepository.unskipUpdate(packageName) }
    }

    fun installLatest() {
        val detail = _state.value.detail ?: return
        val release = detail.latestReleaseFor(_state.value.channel) ?: return
        if (_state.value.isBusy || _state.value.activeInstall != null) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val state = stateForModule(variantStates, _state.value.installed) ?: ModuleVariantState()
            // Lazy validation: TRUSTED never probes; UNKNOWN / ambiguous do.
            val resolution = container.resolveVariantUseCase.resolve(
                release = release,
                state = state,
                device = container.deviceProfile,
                expectedPackage = packageName,
            )
            _state.update {
                it.copy(
                    isBusy = false,
                    probes = resolution.probes,
                    probedTag = release.tagName,
                )
            }
            when (val decision = resolution.decision) {
                is VariantDecision.Pick -> startInstall(decision.asset, announceRemembered = false)
                is VariantDecision.Choose -> _state.update {
                    it.copy(variantPicker = VariantPicker(release, decision.candidates))
                }

                is VariantDecision.NotApplicable -> {
                    val message = container.appContext.variantReasonText(decision.reason)
                    libraryRepository.setVariantState(
                        packageName,
                        state.onEvaluatedNoCandidate(decision.reason),
                    )
                    _state.update { it.copy(message = message) }
                }
            }
        }
    }

    /** Explicit user action: fill the picker/sheet with probed metadata when allowed. */
    fun probeRelease(release: Release) {
        if (_state.value.probedTag == release.tagName && _state.value.probes.isNotEmpty()) return
        val state = stateForModule(variantStates, _state.value.installed) ?: ModuleVariantState()
        viewModelScope.launch {
            _state.update { it.copy(isProbing = true) }
            val resolution = container.resolveVariantUseCase.resolve(
                release = release,
                state = state,
                device = container.deviceProfile,
                expectedPackage = packageName,
                forceProbe = true,
            )
            _state.update {
                it.copy(
                    isProbing = false,
                    probes = resolution.probes,
                    probedTag = release.tagName,
                )
            }
        }
    }

    fun installAsset(asset: ReleaseAsset) {
        // Picking an asset (picker or manual sheet) is an explicit user choice.
        val signature = signatureOf(asset)
        val current = stateForModule(variantStates, _state.value.installed) ?: ModuleVariantState()
        // The remembered-choice toast is only news when the pick was not already
        // the trusted signature; read before the state below overwrites it.
        val announceRemembered = current.status != ModuleVariantState.Status.TRUSTED ||
            current.signature != signature
        viewModelScope.launch {
            libraryRepository.setVariantState(packageName, current.onUserPicked(signature))
        }
        startInstall(asset, announceRemembered)
    }

    /** Discards a delegated install that never produced a package event. */
    fun dismissPending() {
        val target = _state.value.pendingInstall?.packageName ?: return
        viewModelScope.launch { pendingRepository.clear(target) }
    }

    fun dismissVariantPicker() {
        _state.update { it.copy(variantPicker = null) }
    }

    /**
     * Cancels the download. The install button doubles as the cancel button, so
     * there is no separate pause/cancel row any more.
     */
    fun cancelInstall() {
        coordinator.cancel(packageName)
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun translateSummary() {
        val summary = _state.value.detail?.module?.summary ?: return
        if (summary.isBlank() || _state.value.isTranslating) return
        viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, message = null) }
            translationRepository.translate(summary)
                .onSuccess { translated ->
                    _state.update { it.copy(isTranslating = false, translatedSummary = translated) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isTranslating = false,
                            message = container.appContext.getString(R.string.detail_translate_failed),
                        )
                    }
                }
        }
    }

    /**
     * Opt-in auto translation of the description, Komi-style. The target is the
     * app language, so there is nothing else to read from settings. A provider
     * that is not configured is skipped silently instead of showing a failure
     * every time a module page opens.
     */
    private fun maybeAutoTranslate() {
        val summary = _state.value.detail?.module?.summary
        if (summary.isNullOrBlank() || _state.value.translatedSummary != null) return
        viewModelScope.launch {
            val settings = settingsRepository.translationSettings.first()
            if (!settings.autoTranslate || !settings.isConfigured) return@launch
            translateSummary()
        }
    }

    fun clearTranslation() {
        _state.update { it.copy(translatedSummary = null) }
    }

    /**
     * Hands the picked asset to the app-scoped [ModuleInstallCoordinator].
     *
     * The chain deliberately does not run in `viewModelScope`: leaving the page
     * must not abort an install that is already downloading (komi keeps it too).
     */
    private fun startInstall(asset: ReleaseAsset, announceRemembered: Boolean) {
        val current = _state.value
        if (current.isBusy || current.activeInstall != null) return
        val version = versionFor(asset)
        _state.update { it.copy(message = null, variantPicker = null) }
        coordinator.start(
            ModuleInstallRequest(
                repoPackageName = packageName,
                title = current.detail?.module?.title ?: packageName,
                asset = asset,
                installedPackage = current.installed?.packageName,
                versionCode = version?.versionCode ?: 0L,
                versionName = version?.versionName ?: asset.name,
                announceRemembered = announceRemembered,
            ),
        )
    }

    /**
     * Mirrors the coordinator run into UI state. A run that disappeared while this
     * page was showing it has finished, so the installed copy is rescanned.
     */
    private suspend fun updateRunState(run: ModuleInstallRun?) {
        val hadRun = _state.value.activeInstall != null
        _state.update {
            it.copy(
                activeInstall = run,
                // Keep the last log lines after the run is gone; they are the
                // record of what this page did.
                installLogs = run?.logs ?: it.installLogs,
            )
        }
        if (hadRun && run == null) {
            val installed = findInstalled()
            _state.update {
                it.copy(
                    installed = installed,
                    variantState = stateForModule(variantStates, installed),
                )
            }
            updatePendingState()
        }
    }

    private suspend fun resolveInstalled() {
        val installed = findInstalled()
        _state.update {
            it.copy(installed = installed, variantState = stateForModule(variantStates, installed))
        }
        updatePendingState()
    }

    private fun updatePendingState() {
        val target = _state.value.installed?.packageName ?: packageName
        val pending = pendingInstalls[target] ?: pendingInstalls[packageName]
        _state.update { it.copy(pendingInstall = pending) }
    }

    /** Version of the release that [asset] belongs to, when it can be resolved. */
    private fun versionFor(asset: ReleaseAsset): ModuleVersion? = _state.value.detail
        ?.let { detail ->
            detail.releases + detail.betaReleases + detail.snapshotReleases
        }
        ?.asSequence()
        ?.filter { release -> release.assets.any { it == asset } }
        ?.mapNotNull { it.version }
        ?.firstOrNull()

    private fun signatureOf(asset: ReleaseAsset): VariantSignature =
        VariantSignature.of(parseTraits(asset.name))

    /** Variant state is keyed by repository package, with the installed package as fallback. */
    private fun stateForModule(
        states: Map<String, ModuleVariantState>,
        installed: InstalledModule?,
    ): ModuleVariantState? = states[packageName]
        ?: installed?.packageName?.let { states[it] }

    /** Direct install, or an imported module linked to this repository entry. */
    private suspend fun findInstalled(): InstalledModule? {
        val activeLinks = links.ifEmpty { libraryRepository.links.first() }
        // Detail is the screen that reacts to an install finishing, so it must
        // not read the 30 s scan cache used by browsing screens.
        val all = installedRepository.getInstalledModules(force = true)
        return all.firstOrNull { it.packageName == packageName }
            ?: all.firstOrNull { activeLinks[it.packageName] == packageName }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer { ModuleDetailViewModel(container, packageName) }
        }
    }
}
