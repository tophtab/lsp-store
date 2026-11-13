package io.github.tophtab.lspstore.ui.screens.detail

import io.github.tophtab.lspstore.domain.usecase.ReleaseProbeRequests
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.data.install.UninstallOutcome
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.translation.ContentLanguage
import io.github.tophtab.lspstore.data.translation.translationLanguage
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.usecase.UpdateChecker
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.data.repository.findInstalledCopy
import kotlin.coroutines.cancellation.CancellationException

data class ModuleDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val detail: ModuleDetail? = null,
    val installed: InstalledModule? = null,
    /**
     * Stream shown on this page. The three buttons above the resource picker pick
     * it; a channel choice also updates the persisted pre-release opt-in for the
     * streams that have an update-check meaning.
     */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    val isFavourite: Boolean = false,
    /** Whether the user hid this module from the repository's browsing lists. */
    val isModuleIgnored: Boolean = false,
    /**
     * Keyword rules that also hide this module. They are rules, not entries, so
     * this page cannot remove them; it only explains why the module is hidden.
     */
    val hiddenByKeywords: List<String> = emptyList(),
    /** Download/install started from this page, still running. */
    val activeInstall: ModuleInstallRun? = null,
    val message: String? = null,
    val translatedSummary: String? = null,
    /** README translated as HTML, with its markup preserved. */
    val translatedReadmeHtml: String? = null,
    /** Release notes translated as plain text, keyed by release tag. */
    val translatedNotes: Map<String, String> = emptyMap(),
    /** Whether the page currently shows translations instead of the originals. */
    val showTranslation: Boolean = false,
    /** The persisted auto-translate setting: the only source of automatic translation. */
    val autoTranslate: Boolean = false,
    /** Increments on every manual tap that starts translating the page. */
    val translateRequest: Int = 0,
    /** Number of translation requests currently in flight. */
    val translationsInFlight: Int = 0,
    val variantState: ModuleVariantState? = null,
    /** P2 probe results for [probedTag], shown in the picker/sheet. */
    val probes: Map<String, ApkMeta> = emptyMap(),
    val probedTag: String? = null,
    val probedRelease: Release? = null,
    val variantResolution: io.github.tophtab.lspstore.domain.usecase.VariantResolution? = null,
    val probeGeneration: Int = 0,
    val isProbing: Boolean = false,
    val pendingInstall: PendingInstall? = null,
    val installLogs: List<String> = emptyList(),
    /** Mirrors the root switch: a silent uninstall asks for confirmation first. */
    val rootInstall: Boolean = false,
) {
    val isTranslating: Boolean
        get() = translationsInFlight > 0

    val latestVersion: ModuleVersion?
        get() = detail?.module?.latestFor(channel)

    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val module = detail?.module ?: return false
            val latest = module.latestFor(channel) ?: return false
            // Same rule as the Installed tab (UpdateChecker), including the
            // same-code re-upload case: a versionName-only difference caused by
            // author naming is not an update, a re-upload after the installed
            // copy landed is.
            return UpdateChecker.isUpdateAvailable(
                latest = latest,
                installed = installed,
                publishedRaw = module.latestTimeFor(channel),
                confirmed = variantState?.confirmedInstall,
            )
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
    private var preReleaseModules: Set<String> = emptySet()

    private var summaryTranslationJob: Job? = null
    private var readmeTranslationJob: Job? = null
    private var loadJob: Job? = null
    internal val readmeDocument = ReadmeDocument()
    private var probeJob: Job? = null
    private val probeRequests = ReleaseProbeRequests()
    private val notesTranslationJobs = mutableMapOf<String, Job>()

    /**
     * Translations identified individually rather than counted: a cancelled job
     * may finish after a replacement has already started, and a plain counter
     * would then hide the replacement's spinner.
     */
    private val activeTranslations = mutableSetOf<Long>()
    private var translationSequence = 0L

    /** The user chose the original; auto-translate must not undo that choice. */
    private var autoTranslateSuppressed = false

    /** Keyword rules as last emitted by the library; matched against the loaded module. */
    private var hiddenKeywords: List<String> = emptyList()

    /**
     * The user turned translation on with the top-bar action. A reload keeps
     * translating content even when its language matches the target, because
     * this was an explicit request rather than the auto-translate setting.
     */
    private var manualTranslationActive = false

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
        probeRequests.invalidate()
        probeJob?.cancel()
        probeJob = null
        cancelTranslations()
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
                if (_state.value.showTranslation) {
                    translatePage(force = manualTranslationActive)
                } else {
                    maybeAutoTranslate()
                }
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

    /** Supplements recommendations and picker information when several APKs exist. */
    fun probeRelease(release: Release, forceProbe: Boolean = false) {
        probeRequests.invalidate()
        probeJob?.cancel()
        probeJob = null
        if (release.apkAssets.size <= 1) {
            _state.update {
                it.copy(
                    isProbing = false,
                    probes = emptyMap(),
                    probedTag = null,
                    probedRelease = null,
                    variantResolution = null,
                )
            }
            return
        }
        val request = probeRequests.begin(release)
        val state = stateForModule(variantStates, _state.value.installed)
        probeJob = viewModelScope.launch {
            _state.update { it.copy(isProbing = true, probes = emptyMap(), probedTag = release.tagName, probedRelease = release, variantResolution = null) }
            val resolution = container.resolveVariantUseCase.resolve(
                release = release,
                state = state,
                device = container.deviceProfile,
                expectedPackage = expectedPackage(
                    installedPackage = _state.value.installed?.packageName,
                    repoPackage = packageName,
                ),
                forceProbe = forceProbe,
                installed = _state.value.installed?.let(InstalledVariantHint::of),
            )
            if (!probeRequests.isCurrent(request)) return@launch
            _state.update {
                it.copy(
                    isProbing = false,
                    probes = resolution.probes,
                    variantResolution = resolution,
                    probedRelease = release,
                    probedTag = release.tagName,
                )
            }
        }
    }

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
            when (val outcome = container.uninstallModule(installed.packageName)) {
                UninstallOutcome.Completed -> {
                    resolveInstalled()
                    _state.update {
                        it.copy(
                            message = container.appContext.getString(
                                R.string.installed_uninstalled,
                                installed.label,
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

    /**
     * Shows or hides translated content. The first tap translates the module
     * summary and README; the page translates the release notes for the version
     * it is showing. This is a one-shot request for the current page: switching
     * to another version does not translate its notes unless the auto-translate
     * setting is on. A second tap returns to the originals without dropping the
     * translations.
     */
    fun toggleTranslation() {
        if (_state.value.showTranslation) {
            autoTranslateSuppressed = true
            _state.update { it.copy(showTranslation = false) }
            return
        }
        autoTranslateSuppressed = false
        manualTranslationActive = true
        _state.update {
            it.copy(showTranslation = true, translateRequest = it.translateRequest + 1)
        }
        translatePage(force = true)
    }

    /**
     * Translates the page-level content (summary and README) when needed. A
     * forced page skips the language check: the user asked for a translation
     * even when the content already looks like the target language.
     */
    private fun translatePage(force: Boolean) {
        val detail = _state.value.detail ?: return
        if (!_state.value.showTranslation) return
        translateSummary(detail, force)
        translateReadme(detail, force)
    }

    private fun translateSummary(detail: ModuleDetail, force: Boolean) {
        val summary = detail.module.summary
        if (summary.isNullOrBlank() || _state.value.translatedSummary != null) return
        if (!force && ContentLanguage.isLanguage(summary, translationLanguage())) return
        if (summaryTranslationJob?.isActive == true) return
        summaryTranslationJob = viewModelScope.launch {
            translateText(summary) { translated ->
                _state.update { it.copy(translatedSummary = translated) }
            }
        }
    }

    private fun translateReadme(detail: ModuleDetail, force: Boolean) {
        val html = detail.readmeHtml
        if (html.isNullOrBlank() || _state.value.translatedReadmeHtml != null) return
        if (!force && ContentLanguage.isLanguage(ContentLanguage.fromHtml(html), translationLanguage())) {
            return
        }
        if (readmeTranslationJob?.isActive == true) return
        readmeTranslationJob = viewModelScope.launch {
            translateText(html, translationRepository::translateHtml) { translated ->
                _state.update { it.copy(translatedReadmeHtml = translated) }
            }
        }
    }

    /**
     * Translates one release's notes. The page calls this for the release it is
     * showing, so switching versions while translation is on translates the new
     * notes on demand. Only the auto-translate setting skips notes that already
     * look like the target language; a manual request translates regardless.
     */
    fun translateNotes(tagName: String, text: String, manual: Boolean = false) {
        if (!_state.value.showTranslation) return
        if (text.isBlank() || _state.value.translatedNotes.containsKey(tagName)) return
        if (!manual && ContentLanguage.isLanguage(text, translationLanguage())) return
        if (notesTranslationJobs[tagName]?.isActive == true) return
        notesTranslationJobs[tagName] = viewModelScope.launch {
            translateText(text) { translated ->
                _state.update {
                    it.copy(translatedNotes = it.translatedNotes + (tagName to translated))
                }
            }
        }
    }

    private suspend fun translateText(
        text: String,
        translate: suspend (String) -> Result<String> = translationRepository::translate,
        onSuccess: (String) -> Unit,
    ) {
        val id = beginTranslation()
        try {
            translate(text)
                .onSuccess(onSuccess)
                .onFailure { showTranslationFailure() }
        } finally {
            endTranslation(id)
        }
    }

    private fun beginTranslation(): Long {
        val id = ++translationSequence
        activeTranslations += id
        _state.update { it.copy(translationsInFlight = activeTranslations.size) }
        return id
    }

    private fun endTranslation(id: Long) {
        activeTranslations -= id
        _state.update { it.copy(translationsInFlight = activeTranslations.size) }
    }

    private fun showTranslationFailure() {
        _state.update { current ->
            // Only the last in-flight request can decide this, and only when no
            // translation arrived: otherwise the button would claim a translated
            // page while it still shows the originals.
            val nothingTranslated = activeTranslations.size == 1 &&
                current.translatedSummary == null &&
                current.translatedReadmeHtml == null &&
                current.translatedNotes.isEmpty()
            current.copy(
                // One message is enough even when several parts fail together.
                message = current.message
                    ?: container.appContext.getString(R.string.detail_translate_failed),
                showTranslation = if (nothingTranslated) false else current.showTranslation,
            )
        }
    }

    private fun cancelTranslations() {
        summaryTranslationJob?.cancel()
        readmeTranslationJob?.cancel()
        notesTranslationJobs.values.forEach { it.cancel() }
        notesTranslationJobs.clear()
        // The cancelled jobs decrement the counter in their own `finally`, so it
        // must not be reset here: a fresh job may already have incremented it.
    }

    /**
     * Opt-in auto translation, Komi-style. The target is the app language, so
     * there is nothing else to read from settings. Content that already looks
     * like the target language is left alone: the page only switches to the
     * translated view when at least one block needs translating. A provider that
     * is not configured is skipped silently instead of showing a failure every
     * time a module page opens.
     */
    private fun maybeAutoTranslate() {
        if (autoTranslateSuppressed || _state.value.showTranslation) return
        val detail = _state.value.detail ?: return
        val target = translationLanguage()
        val notesHtml = detail.latestReleaseFor(_state.value.channel)?.descriptionHtml
        val needsTranslation = ContentLanguage.needsTranslation(
            target,
            detail.module.summary,
            detail.readmeHtml?.let(ContentLanguage::fromHtml),
            notesHtml?.let(ContentLanguage::fromHtml),
        )
        if (!needsTranslation) return
        viewModelScope.launch {
            val settings = settingsRepository.translationSettings.first()
            if (!settings.autoTranslate || !settings.isConfigured) return@launch
            _state.update { it.copy(showTranslation = true) }
            translatePage(force = false)
        }
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
