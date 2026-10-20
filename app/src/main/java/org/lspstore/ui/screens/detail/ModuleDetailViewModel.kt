package org.lspstore.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.data.install.ApkGate
import org.lspstore.data.install.DownloadStatus
import org.lspstore.data.install.DownloadTask
import org.lspstore.data.install.GateResult
import org.lspstore.data.install.InstallOutcome
import org.lspstore.data.repository.InstalledModulesRepository
import org.lspstore.data.repository.LibraryRepository
import org.lspstore.data.repository.ModuleRepository
import org.lspstore.data.repository.PendingInstallRepository
import org.lspstore.data.repository.SettingsRepository
import org.lspstore.di.AppContainer
import org.lspstore.ui.toUserMessage
import org.lspstore.ui.variant.variantReasonText
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.ModuleVersion
import org.lspstore.domain.model.PendingInstall
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.variant.ApkMeta
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.Reason
import org.lspstore.domain.variant.ScoredAsset
import org.lspstore.domain.variant.VariantDecision
import org.lspstore.domain.variant.VariantSignature
import org.lspstore.domain.variant.onEvaluatedNoCandidate
import org.lspstore.domain.variant.onUserPicked
import org.lspstore.domain.variant.parseTraits
import java.io.File

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
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val isFavourite: Boolean = false,
    val skippedVersionCode: Long? = null,
    val isBusy: Boolean = false,
    val activeDownload: DownloadTask? = null,
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
    val latestVersionCode: Long?
        get() = detail?.module?.latestFor(channel)?.versionCode

    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val latest = detail?.module?.latestFor(channel) ?: return false
            // Same rule as UpdateChecker: a versionName-only difference caused by
            // author naming is not an update.
            return latest.versionCode > installed.versionCode
        }

    val isUpdateSkipped: Boolean
        get() = skippedVersionCode != null && skippedVersionCode == latestVersionCode

    val downloadProgress: Float
        get() = activeDownload?.progress ?: 0f
}

class ModuleDetailViewModel(
    private val container: AppContainer,
    private val packageName: String,
) : ViewModel() {

    private val moduleRepository: ModuleRepository = container.moduleRepository
    private val installedRepository: InstalledModulesRepository = container.installedRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository
    private val libraryRepository: LibraryRepository = container.libraryRepository
    private val pendingRepository: PendingInstallRepository = container.pendingInstallRepository
    private val installer = container.installer
    private val downloadManager = container.downloadManager
    private val translationRepository = container.translationRepository

    private val _state = MutableStateFlow(ModuleDetailUiState())
    val state = _state.asStateFlow()

    private var pendingInstall: Pair<File, ReleaseAsset>? = null
    private var pendingTargetPackage: String? = null
    private var pendingSignature: VariantSignature? = null
    private var announceRemembered = false
    private var links: Map<String, String> = emptyMap()
    private var variantStates: Map<String, ModuleVariantState> = emptyMap()
    private var pendingInstalls: Map<String, PendingInstall> = emptyMap()

    init {
        viewModelScope.launch {
            settingsRepository.updateChannel.collect { channel ->
                _state.update { it.copy(channel = channel) }
            }
        }
        viewModelScope.launch {
            libraryRepository.favourites.collect { favourites ->
                _state.update { it.copy(isFavourite = packageName in favourites) }
            }
        }
        viewModelScope.launch {
            libraryRepository.skippedUpdates.collect { skipped ->
                _state.update { it.copy(skippedVersionCode = skipped[packageName]) }
            }
        }
        viewModelScope.launch {
            libraryRepository.links.collect { value ->
                links = value
                resolveInstalled()
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
            // Delegated installs finish in another app; the package broadcast is
            // the completion signal (komi's PackageEventReceiver equivalent).
            installer.installedPackages.collect { installedPackage ->
                val expected = pendingTargetPackage ?: _state.value.installed?.packageName ?: packageName
                if (installedPackage != expected && installedPackage != packageName) return@collect
                pendingTargetPackage = null
                // Records from the persisted pending entry (which survives death),
                // so a delegated install is remembered even if this screen was
                // recreated while the installer was in the foreground.
                container.completePendingInstall(installedPackage)
                pendingSignature = null
                val installed = findInstalled()
                val rememberedMessage = if (announceRemembered) {
                    container.appContext.getString(R.string.variant_remembered)
                } else {
                    container.appContext.getString(R.string.detail_install_finished)
                }
                announceRemembered = false
                _state.update {
                    it.copy(
                        isBusy = false,
                        activeDownload = null,
                        installed = installed,
                        message = rememberedMessage,
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
                    )
                }
                updatePendingState()
                libraryRepository.addRecentlyViewed(packageName)
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

    fun toggleFavourite() {
        viewModelScope.launch { libraryRepository.toggleFavourite(packageName) }
    }

    fun setChannel(channel: UpdateChannel) {
        viewModelScope.launch { settingsRepository.setUpdateChannel(channel) }
    }

    fun skipThisVersion() {
        val versionCode = _state.value.latestVersionCode ?: return
        viewModelScope.launch { libraryRepository.skipUpdate(packageName, versionCode) }
    }

    fun unskipVersion() {
        viewModelScope.launch { libraryRepository.unskipUpdate(packageName) }
    }

    fun installLatest() {
        val detail = _state.value.detail ?: return
        val release = detail.latestReleaseFor(_state.value.channel) ?: return
        if (_state.value.isBusy) return
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
                is VariantDecision.Pick -> beginDownload(decision.asset)
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
        announceRemembered = current.status != ModuleVariantState.Status.TRUSTED ||
            current.signature != signature
        pendingSignature = signature
        viewModelScope.launch {
            libraryRepository.setVariantState(packageName, current.onUserPicked(signature))
        }
        beginDownload(asset)
    }

    /** Discards a delegated install that never produced a package event. */
    fun dismissPending() {
        val target = _state.value.pendingInstall?.packageName ?: return
        viewModelScope.launch { pendingRepository.clear(target) }
    }

    fun dismissVariantPicker() {
        _state.update { it.copy(variantPicker = null) }
    }

    fun pauseDownload() {
        val task = _state.value.activeDownload ?: return
        downloadManager.pause(task.key)
    }

    fun resumeDownload() {
        val asset = pendingInstall?.second ?: return
        downloadManager.resume(asset)
    }

    fun cancelDownload() {
        val task = _state.value.activeDownload ?: return
        downloadManager.cancel(task.key)
        pendingInstall = null
        _state.update {
            it.copy(
                isBusy = false,
                activeDownload = null,
                message = container.appContext.getString(R.string.download_cancelled),
            )
        }
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
                .onFailure { error ->
                    val message = if (error.message?.contains("disabled", ignoreCase = true) == true) {
                        container.appContext.getString(R.string.detail_translation_disabled)
                    } else {
                        container.appContext.getString(R.string.detail_translate_failed)
                    }
                    _state.update { it.copy(isTranslating = false, message = message) }
                }
        }
    }

    fun clearTranslation() {
        _state.update { it.copy(translatedSummary = null) }
    }

    private fun beginDownload(asset: ReleaseAsset) {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                isBusy = true,
                message = null,
                variantPicker = null,
                installLogs = listOf("Downloading ${asset.name}"),
            )
        }
        val key = downloadManager.start(asset)
        viewModelScope.launch {
            val progressJob = launch {
                downloadManager.tasks.collect { tasks ->
                    tasks[key]?.let { task -> _state.update { it.copy(activeDownload = task) } }
                }
            }
            val task = downloadManager.tasks
                .mapNotNull { it[key] }
                .first {
                    it.status == DownloadStatus.COMPLETED ||
                        it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELLED
                }
            progressJob.cancel()

            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    val file = task.file
                    if (file == null) {
                        _state.update { it.copy(isBusy = false, activeDownload = null) }
                        return@launch
                    }
                    log("Download complete")
                    pendingInstall = file to asset
                    performInstall(file, asset)
                }
                DownloadStatus.CANCELLED -> {
                    pendingInstall = null
                    log("Download cancelled")
                    _state.update {
                        it.copy(
                            isBusy = false,
                            activeDownload = null,
                            message = container.appContext.getString(R.string.download_cancelled),
                        )
                    }
                }
                else -> {
                    pendingInstall = null
                    log(task.error ?: "Download failed")
                    _state.update {
                        it.copy(
                            isBusy = false,
                            activeDownload = null,
                            message = task.error ?: container.appContext.getString(R.string.detail_download_failed),
                        )
                    }
                }
            }
        }
    }

    private suspend fun performInstall(
        file: File,
        asset: ReleaseAsset,
        targetPackage: String? = null,
    ) {
        val useRoot = settingsRepository.rootInstall.first()
        val target = targetPackage ?: _state.value.installed?.packageName ?: packageName
        var installTarget = target
        // A repository listing may publish an APK whose package was renamed
        // (`dev.wtonec` -> `dev.wtonec.final`). The gate allows it when the APK
        // still declares itself as an Xposed module; install it under its own
        // package and remember the link so updates keep matching afterwards.
        var linkAfterInstall: Pair<String, String>? = null
        pendingTargetPackage = target
        // Local gate: zero network, runs before the APK reaches the installer.
        when (val gate = ApkGate.check(container.appContext, file, target, container.deviceProfile)) {
            is GateResult.Reject -> {
                // One failed attempt, nothing persisted: the module keeps its
                // place in the update list and can be retried after a re-upload.
                val message = container.appContext.variantReasonText(gate.reason)
                log(message)
                libraryRepository.recordInstallResult(packageName, ok = false)
                pendingInstall = null
                pendingTargetPackage = null
                finishInstall(message)
                return
            }

            is GateResult.Warn -> {
                log(container.appContext.variantReasonText(gate.reason))
                val archivePackage = gate.reason.args.firstOrNull()
                if (gate.reason.kind == Reason.Kind.PACKAGE_MISMATCH &&
                    _state.value.installed == null &&
                    !archivePackage.isNullOrBlank() &&
                    archivePackage != target
                ) {
                    installTarget = archivePackage
                    linkAfterInstall = archivePackage to packageName
                }
            }
            GateResult.Ok -> Unit
        }
        pendingTargetPackage = installTarget
        log("Installing ${asset.name}")
        val serializer = container.systemInstallSerializer
        val outcome = runCatching {
            // komi's orchestrator order: take the slot right before handing over.
            serializer.awaitFreeAndMarkPending(installTarget)
            installer.install(file, installTarget, useRoot)
        }.getOrElse { InstallOutcome.Failure(it.message ?: "install failed") }
        when (outcome) {
            InstallOutcome.Completed -> {
                serializer.markCompleted(installTarget)
                pendingRepository.clear(installTarget)
                pendingInstall = null
                pendingTargetPackage = null
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
                libraryRepository.recordInstallResult(
                    packageName,
                    ok = true,
                    signature = pendingSignature ?: signatureOf(asset),
                )
                pendingSignature = null
                log("Installed")
                val remembered = announceRemembered
                announceRemembered = false
                finishInstall(
                    if (remembered) {
                        container.appContext.getString(R.string.variant_remembered)
                    } else {
                        container.appContext.getString(R.string.detail_installed_apk, asset.name)
                    },
                )
            }
            InstallOutcome.DelegatedToSystem -> {
                // The installer app owns the APK from here on. Like komi-store,
                // persist a pending state until the package broadcast arrives
                // instead of showing a one-off message.
                val version = versionFor(asset)
                pendingRepository.markPending(
                    PendingInstall(
                        packageName = installTarget,
                        repoPackageName = packageName,
                        title = _state.value.detail?.module?.title ?: installTarget,
                        versionCode = version?.versionCode ?: 0L,
                        versionName = version?.versionName ?: asset.name,
                        assetName = asset.name,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
                pendingInstall = null
                log("Handed off to the system installer")
                finishInstall(null)
            }
            is InstallOutcome.Failure -> {
                serializer.markCompleted(installTarget)
                pendingInstall = null
                pendingTargetPackage = null
                libraryRepository.recordInstallResult(packageName, ok = false)
                log(outcome.message)
                finishInstall(outcome.message)
            }
        }
    }

    private fun log(line: String) {
        _state.update { it.copy(installLogs = it.installLogs + line) }
    }

    private suspend fun finishInstall(message: String?) {
        val installed = findInstalled()
        _state.update {
            it.copy(
                isBusy = false,
                activeDownload = null,
                message = message,
                installed = installed,
                variantState = stateForModule(variantStates, installed),
            )
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
    private fun versionFor(asset: ReleaseAsset): ModuleVersion? =        _state.value.detail
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
        val all = installedRepository.getInstalledModules()
        return all.firstOrNull { it.packageName == packageName }
            ?: all.firstOrNull { activeLinks[it.packageName] == packageName }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer { ModuleDetailViewModel(container, packageName) }
        }
    }
}
