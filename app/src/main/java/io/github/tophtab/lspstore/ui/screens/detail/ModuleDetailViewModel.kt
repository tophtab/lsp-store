package io.github.tophtab.lspstore.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.findInstalledCopy
import io.github.tophtab.lspstore.data.translation.translationLanguage
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.ui.reportUninstallOutcome
import io.github.tophtab.lspstore.ui.toUserMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val pageState = state.map(ModuleDetailUiState::withoutProgress).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModuleDetailUiState())
    val installProgress = state.map { it.activeInstall }.distinctUntilChanged()

    /** The user picked a channel; stop mirroring the persisted pre-release flag. */
    private var channelChosen = false
    private var links: Map<String, String> = emptyMap()
    private var variantStates: Map<String, ModuleVariantState> = emptyMap()
    private var pendingInstalls: Map<String, PendingInstall> = emptyMap()
    private var preReleaseModules: Set<String> = emptySet()

    private var loadJob: Job? = null
    internal val readmeDocument = ReadmeDocument()
    private var hiddenKeywords: List<String> = emptyList()

    private val translationController = DetailTranslationController(
        scope = viewModelScope,
        readState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        translate = translationRepository::translate,
        translateHtml = translationRepository::translateHtml,
        settings = settingsRepository.translationSettings,
        targetLanguage = ::translationLanguage,
        failureMessage = { container.appContext.getString(R.string.detail_translate_failed) },
    )
    private val probeController = DetailProbeController(
        scope = viewModelScope,
        readState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        repoPackageName = packageName,
        resolveVariant = container.resolveVariantUseCase,
        device = container.deviceProfile,
        variantState = { stateForModule(variantStates, _state.value.installed) },
    )

    init {
        viewModelScope.launch {
            libraryRepository.favourites.collect { favourites ->
                _state.update { it.copy(isFavourite = packageName in favourites) }
            }
        }
        viewModelScope.launch {
            // Only the setting may translate content the user did not ask for;
            // the manual top-bar action stays a one-shot request.
            settingsRepository.translationSettings.collect { settings ->
                _state.update { it.copy(autoTranslate = settings.autoTranslate) }
            }
        }
        viewModelScope.launch {
            settingsRepository.rootInstall.collect { enabled ->
                _state.update { it.copy(rootInstall = enabled) }
            }
        }
        viewModelScope.launch {
            libraryRepository.hiddenModules.collect { hidden ->
                hiddenKeywords = hidden.keywords
                _state.update {
                    it.copy(
                        isModuleIgnored = packageName in hidden.packages,
                        hiddenByKeywords = it.detail?.let { detail ->
                            hiddenKeywords.filter(detail.module::matchesText)
                        }.orEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value ->
                val previous = links.filterValues { it == packageName }
                links = value
                if (previous != value.filterValues { it == packageName }) resolveInstalled()
            }
        }
        viewModelScope.launch {
            libraryRepository.preReleaseModules.collect { value ->
                preReleaseModules = value
                _state.update {
                    val tracked = isPreReleaseTracked(it.installed)
                    it.copy(
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
                val previousPackage = variantStates[packageName]?.activePackage
                variantStates = value
                _state.update { it.copy(variantState = stateForModule(value, it.installed)) }
                if (value[packageName]?.activePackage != previousPackage) resolveInstalled()
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
            try {
                container.prunePendingInstallsForModule(packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A later resume or package event retries unreadable packages.
            }
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
                    val tracked = isPreReleaseTracked(installed)
                    it.copy(
                        installed = installed,
                        channel = if (channelChosen) it.channel else defaultChannel(it.detail, tracked),
                        message = container.appContext.getString(R.string.detail_install_finished),
                    )
                }
                updatePendingState()
            }
        }
        load()
    }

    fun load() {
        probeController.cancel()
        translationController.cancel()
        // A second refresh replaces the first one: two loads writing `detail`
        // and the translation state would otherwise race each other.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = it.detail == null,
                    isRefreshing = it.detail != null,
                    isProbing = false,
                    error = null,
                    translatedSummary = null,
                    translatedReadmeHtml = null,
                    translatedNotes = emptyMap(),
                )
            }
            runCatching {
                val detail = moduleRepository.getDetail(packageName)
                val installed = findInstalled()
                detail to installed
            }.onSuccess { (detail, installed) ->
                _state.update {
                    val tracked = isPreReleaseTracked(installed)
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        detail = detail,
                        hiddenByKeywords = hiddenKeywords.filter(detail.module::matchesText),
                        probeGeneration = it.probeGeneration + 1,
                        installed = installed,
                        variantState = stateForModule(variantStates, installed),
                        // The persisted opt-in may point at a stream this module
                        // no longer publishes; fall back to stable in that case.
                        channel = if (channelChosen) it.channel else defaultChannel(detail, tracked),
                    )
                }
                updatePendingState()
                libraryRepository.addRecentlyViewed(packageName)
                translationController.onDetailLoaded()
            }.onFailure { error ->
                // A cancelled load was replaced by the one that cancelled it;
                // writing its error would flash a failure over fresher data.
                if (error is CancellationException) return@onFailure
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

    /** Canonical repo key, with the installed-package key accepted for old data. */
    private fun isPreReleaseTracked(installed: InstalledModule?): Boolean =
        preReleaseModules.tracksPreReleases(installed?.packageName, packageName)

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
            ReleaseChannel.STABLE -> setPreReleaseTracking(false)
            ReleaseChannel.PRE_RELEASE -> setPreReleaseTracking(true)
            ReleaseChannel.ALL -> Unit
        }
    }

    /**
     * Writes the canonical repository key and drops the legacy installed-package
     * alias. Both identities are read, so an old backup keeps working, but a
     * toggle must turn the preference off everywhere instead of leaving a stale
     * installed-package entry behind.
     */
    private fun setPreReleaseTracking(enabled: Boolean) {
        viewModelScope.launch {
            libraryRepository.setPreReleasesEnabled(packageName, enabled)
            val installedPackage = _state.value.installed?.packageName
            if (installedPackage != null && installedPackage != packageName) {
                libraryRepository.setPreReleasesEnabled(installedPackage, false)
            }
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    /**
     * Hides the module from the repository's browsing lists, or restores it.
     * The detail page stays open either way; the change lands in the lists the
     * user came from.
     */
    fun setModuleIgnored(ignored: Boolean) {
        viewModelScope.launch {
            libraryRepository.setModuleIgnored(packageName, ignored)
            _state.update {
                it.copy(
                    message = container.appContext.getString(
                        if (ignored) R.string.detail_module_ignored
                        else R.string.detail_module_unignored,
                    ),
                )
            }
        }
    }

    fun probeRelease(release: Release, forceProbe: Boolean = false) =
        probeController.probeRelease(release, forceProbe)

    /** Hands the picked asset to the app-scoped coordinator so it survives leaving this page. */
    fun installAsset(target: ResolvedInstallTarget) {
        val current = _state.value
        if (current.activeInstall != null || current.pendingInstall != null) return
        coordinator.start(ModuleInstallRequest(
            repoPackageName = packageName, title = current.detail?.module?.title ?: packageName,
            asset = target.asset, installedPackage = current.installed?.packageName,
            versionCode = target.releaseVersion?.versionCode ?: 0L,
            versionName = target.releaseVersion?.versionName ?: target.asset.name,
            target = target,
            announceRemembered = target.explicitSelection && current.variantState?.migrated()?.explicitPreference != target.signature,
        ))
    }

    /** Discards a delegated install that never produced a package event. */
    fun dismissPending() {
        val target = _state.value.pendingInstall?.packageName ?: return
        viewModelScope.launch { container.cancelPendingInstall(target) }
    }

    /**
     * Uninstalls the copy this page found. With the root switch on, su removes
     * it in-process, the page rescans the device and the snackbar confirms;
     * otherwise the system uninstaller confirmation page takes over.
     */
    fun uninstall() {
        val installed = _state.value.installed ?: return
        viewModelScope.launch {
            reportUninstallOutcome(
                outcome = container.uninstallModule(installed.packageName),
                completedMessage = container.appContext.getString(R.string.installed_uninstalled, installed.label),
                onCompleted = ::resolveInstalled,
                onMessage = { message -> _state.update { it.copy(message = message) } },
            )
        }
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

    fun toggleTranslation() = translationController.toggleTranslation()

    fun translateNotes(tagName: String, text: String, manual: Boolean = false) =
        translationController.translateNotes(tagName, text, manual)

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
                val tracked = isPreReleaseTracked(installed)
                it.copy(
                    installed = installed,
                    variantState = stateForModule(variantStates, installed),
                    channel = if (channelChosen) it.channel else defaultChannel(it.detail, tracked),
                )
            }
            updatePendingState()
        }
    }

    private suspend fun resolveInstalled() {
        val installed = findInstalled()
        _state.update {
            val tracked = isPreReleaseTracked(installed)
            it.copy(
                installed = installed,
                variantState = stateForModule(variantStates, installed),
                channel = if (channelChosen) it.channel else defaultChannel(it.detail, tracked),
            )
        }
        updatePendingState()
    }

    private fun updatePendingState() {
        val target = _state.value.installed?.packageName ?: packageName
        val pending = pendingInstalls.values.firstOrNull { it.repoPackageName == packageName }
            ?: pendingInstalls[target] ?: pendingInstalls[packageName]
        _state.update { it.copy(pendingInstall = pending) }
    }

    /** Variant state is keyed by repository package, with the installed package as fallback. */
    private fun stateForModule(
        states: Map<String, ModuleVariantState>,
        installed: InstalledModule?,
    ): ModuleVariantState = (states[packageName]
        ?: installed?.packageName?.let { states[it] }
        ?: ModuleVariantState()).forInstalled(installed)

    /** Direct install, or an imported module linked to this repository entry. */
    private suspend fun findInstalled(): InstalledModule? {
        val activeLinks = links.ifEmpty { libraryRepository.links.first() }
        val activePackage = variantStates[packageName]?.activePackage
        return try {
            installedRepository.findInstalledCopy(packageName, activeLinks, activePackage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _state.value.installed
        }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer { ModuleDetailViewModel(container, packageName) }
        }
    }
}
