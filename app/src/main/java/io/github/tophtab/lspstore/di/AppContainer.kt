package io.github.tophtab.lspstore.di

import io.github.tophtab.lspstore.domain.usecase.DEFAULT_PENDING_MAX_AGE_MS
import io.github.tophtab.lspstore.data.install.InstallConfirmation
import io.github.tophtab.lspstore.data.install.InstallExecution
import io.github.tophtab.lspstore.data.install.InstallTaskRegistry
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.data.backup.LibraryBackupStore
import io.github.tophtab.lspstore.data.device.DeviceProfileProvider
import io.github.tophtab.lspstore.data.install.ApkExportStore
import io.github.tophtab.lspstore.data.install.ApkInstaller
import io.github.tophtab.lspstore.data.install.DownloadManager
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.install.ModuleInstallCoordinator
import io.github.tophtab.lspstore.data.install.SystemInstallSerializer
import io.github.tophtab.lspstore.data.install.UninstallOutcome
import io.github.tophtab.lspstore.data.probe.RangeProbe
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.remote.LsposedRepoApi
import io.github.tophtab.lspstore.data.remote.MirrorState
import io.github.tophtab.lspstore.data.remote.MirrorTester
import io.github.tophtab.lspstore.data.remote.createHttpClient
import io.github.tophtab.lspstore.data.remote.createJson
import io.github.tophtab.lspstore.data.remote.createProbeHttpClient
import io.github.tophtab.lspstore.data.remote.createTranslationHttpClient
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepositoryImpl
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.LibraryRepositoryImpl
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepositoryImpl
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepositoryImpl
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.data.repository.SettingsRepositoryImpl
import io.github.tophtab.lspstore.data.translation.TranslationRepository
import io.github.tophtab.lspstore.data.update.UpdateNotifier
import io.github.tophtab.lspstore.domain.usecase.BackgroundUpdateCheck
import io.github.tophtab.lspstore.domain.usecase.BatchInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.InstallPreparedApkUseCase
import io.github.tophtab.lspstore.domain.usecase.PendingSettlement
import io.github.tophtab.lspstore.domain.usecase.PrepareModuleInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.usecase.pendingInstallLanded
import io.github.tophtab.lspstore.domain.usecase.checkPendingInstalls
import io.github.tophtab.lspstore.domain.usecase.settlePendingInstall
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import kotlin.coroutines.cancellation.CancellationException

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val json: Json = createJson()
    val mirrorState: MirrorState = MirrorState()
    val httpClient: HttpClient = createHttpClient(json)

    /** P1: SDK + ABI immediately, vendor OS / LSPosed API after the startup refresh. */
    val deviceProfileProvider: DeviceProfileProvider = DeviceProfileProvider(context)
    val deviceProfileFlow = deviceProfileProvider.profile
    val deviceProfile: DeviceProfile
        get() = deviceProfileProvider.profile.value

    /** P2: ranged probes with cache; failures degrade to the file-name layer. */
    private val probeHttpClient: HttpClient = createProbeHttpClient()
    val variantProbe: ApkProbe = RangeProbe(probeHttpClient, context.cacheDir, json)
    val resolveVariantUseCase: ResolveVariantUseCase = ResolveVariantUseCase(variantProbe)

    val moduleRepository: ModuleRepository =
        ModuleRepositoryImpl(context, LsposedRepoApi(httpClient, mirrorState), json)
    val installedRepository: InstalledModulesRepository = InstalledModulesRepositoryImpl(context, json)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)

    /**
     * Latest "max concurrent downloads" value, mirrored so [DownloadSlots] can
     * read it without suspending at every acquire.
     */
    private val _maxConcurrentDownloads = MutableStateFlow(DownloadSlots.DEFAULT_LIMIT)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    /**
     * Latest "download location" value, mirrored for [ApkExportStore] so an
     * export never waits on DataStore.
     */
    private val _downloadLocation = MutableStateFlow("")

    /** One gate for batch updates and module page installs. */
    val downloadSlots: DownloadSlots = DownloadSlots { _maxConcurrentDownloads.value }

    val libraryRepository: LibraryRepository = LibraryRepositoryImpl(context)
    val libraryBackupStore: LibraryBackupStore = LibraryBackupStore(appContext)
    val pendingInstallRepository: PendingInstallRepository =
        PendingInstallRepositoryImpl(context)
    val apkExportStore: ApkExportStore = ApkExportStore(appContext) { _downloadLocation.value }
    val downloadManager: DownloadManager = DownloadManager(context, apkExportStore)
    val installer: ApkInstaller = ApkInstaller(context, downloadManager)
    val systemInstallSerializer: SystemInstallSerializer = SystemInstallSerializer()

    /**
     * Application-lifetime scope for installs started from a module page. It is
     * deliberately not tied to any ViewModel: pressing back mid-download must not
     * cancel the install (komi keeps it running too).
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val installTaskRegistry = InstallTaskRegistry(initiallyReady = false)
    val installConfirmation = InstallConfirmation(
        libraryRepository, pendingInstallRepository, installTaskRegistry, systemInstallSerializer,
        readInstalled = { packageName ->
            try {
                installedRepository.getInstalledModule(packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        },
    )
    val installExecution = InstallExecution(
        installer, settingsRepository, libraryRepository, pendingInstallRepository,
        systemInstallSerializer, installTaskRegistry, installConfirmation,
    )

    init {
        installScope.launch {
            installTaskRegistry.restore(pendingInstallRepository.pending.first().values)
        }
        // Mirror the setting once at startup and whenever it changes. The gate
        // itself is a plain lambda read, so no download waits on DataStore.
        installScope.launch {
            settingsRepository.maxConcurrentDownloads.collect { value ->
                _maxConcurrentDownloads.value = value
            }
        }
        installScope.launch {
            settingsRepository.downloadLocation.collect { value ->
                _downloadLocation.value = value
            }
        }
    }

    /** Download → installer chain shared by every module page. */
    val moduleInstallCoordinator: ModuleInstallCoordinator = ModuleInstallCoordinator(
        context = appContext,
        scope = installScope,
        downloadManager = downloadManager,
        downloadSlots = downloadSlots,
        libraryRepository = libraryRepository,
        registry = installTaskRegistry,
        execution = installExecution,
    )

    private val translationHttpClient: HttpClient = createTranslationHttpClient()
    val translationRepository: TranslationRepository =
        TranslationRepository(translationHttpClient, settingsRepository)
    val mirrorTester: MirrorTester = MirrorTester(httpClient)

    /** Resolves the release asset for a device and downloads it (no install). */
    val prepareModuleInstallUseCase: PrepareModuleInstallUseCase = PrepareModuleInstallUseCase(
        moduleRepository = moduleRepository,
        libraryRepository = libraryRepository,
        downloadManager = downloadManager,
        resolveVariant = resolveVariantUseCase,
        deviceProfile = { deviceProfile },
        context = appContext,
    )

    /** Global install slot + outcome bookkeeping for one downloaded APK. */
    val installPreparedApkUseCase = InstallPreparedApkUseCase(installExecution)

    /** "Update all": concurrent downloads, serialized installs. */
    val batchInstallUseCase: BatchInstallUseCase = BatchInstallUseCase(
        prepareInstall = prepareModuleInstallUseCase,
        installPrepared = installPreparedApkUseCase,
        abandonDownload = { asset -> downloadManager.cancel(downloadManager.keyOf(asset)) },
        downloadSlots = downloadSlots,
        registry = installTaskRegistry,
    )

    /** Background twin: same bookkeeping, never opens an installer UI. */
    private val silentInstallPreparedApkUseCase = InstallPreparedApkUseCase(installExecution, allowUi = false)

    /** "Update all" for the background check: a non-root install just fails. */
    private val backgroundBatchInstallUseCase: BatchInstallUseCase = BatchInstallUseCase(
        prepareInstall = prepareModuleInstallUseCase,
        installPrepared = silentInstallPreparedApkUseCase,
        abandonDownload = { asset -> downloadManager.cancel(downloadManager.keyOf(asset)) },
        downloadSlots = downloadSlots,
        registry = installTaskRegistry,
    )

    val updateNotifier = UpdateNotifier(appContext)

    /** One periodic cycle: refresh, find updates, install silently or notify. */
    val backgroundUpdateCheck: BackgroundUpdateCheck = BackgroundUpdateCheck(
        moduleRepository = moduleRepository,
        installedRepository = installedRepository,
        libraryRepository = libraryRepository,
        pendingRepository = pendingInstallRepository,
        settingsRepository = settingsRepository,
        installer = installer,
        batchInstall = backgroundBatchInstallUseCase,
        coordinator = moduleInstallCoordinator,
        notifier = updateNotifier,
        selfPackage = appContext.packageName,
        prunePending = { installed -> prunePendingInstalls(installed) },
    )

    /**
     * Finishes one delegated install: records the variant outcome, releases the
     * install slot and persisted pending state, then tells the UI to refresh.
     *
     * The record is written here, from the persisted [io.github.tophtab.lspstore.domain.model.PendingInstall],
     * and not from a screen: the completion signal may arrive while no detail or
     * installed screen is composed (background broadcast, resume after a
     * system-installer round trip).
     */
    suspend fun completePendingInstall(packageName: String, expected: PendingInstall? = null) {
        val pending = expected ?: pendingInstallRepository.pending.first()[packageName] ?: return
        if (installConfirmation.confirm(pending)) installer.confirmInstalled(packageName)
    }

    suspend fun cancelPendingInstall(packageName: String, expected: PendingInstall? = null) {
        val pending = expected ?: pendingInstallRepository.pending.first()[packageName] ?: return
        installConfirmation.cancel(pending) {
            pending.downloadKey?.let { downloadManager.invalidate(it) }
        }
    }

    /**
     * Removes a package through su when the root switch is on, otherwise (or
     * when su fails) through the system uninstaller's confirmation page. Both
     * the installed list and the module page share this decision so the two
     * uninstall buttons cannot drift apart.
     */
    suspend fun uninstallModule(packageName: String): UninstallOutcome =
        installer.uninstall(packageName, settingsRepository.rootInstall.first())

    /**
     * Handles one package broadcast. Only a pending package is interesting:
     * [PACKAGE_ADDED]/[PACKAGE_REPLACED] settle it when the device now runs the
     * target, [PACKAGE_FULLY_REMOVED] drops it without recording success, and an
     * unrelated package must not release the install slot.
     */
    suspend fun onPackageEvent(packageName: String, removed: Boolean) {
        val pending = runCatching { pendingInstallRepository.pending.first()[packageName] }
            .getOrNull()
        if (removed) {
            if (pending != null) cancelPendingInstall(packageName, pending)
            installer.notifyPackageChanged(packageName)
            return
        }
        if (pending == null) {
            // Some other app installed or replaced: refresh the list, but never
            // touch the pending hand-off or the install slot.
            installer.notifyPackageChanged(packageName)
            return
        }
        // A package broadcast is about one package, so a cheap PackageManager
        // lookup is enough; a full installed scan here would run on every event
        // of an update-all.
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        if (
            pendingInstallLanded(
                pending = pending,
                installedVersionCode = packageInfo?.longVersionCode,
                installedLastUpdateTime = packageInfo?.lastUpdateTime,
                installedVersionName = packageInfo?.versionName,
            )
        ) {
            completePendingInstall(packageName, pending)
        } else {
            if (
                installer.foregroundState.isResumed &&
                pending.createdAt > 0L &&
                System.currentTimeMillis() - pending.createdAt > ABANDONED_PENDING_MS
            ) {
                cancelPendingInstall(packageName, pending)
            }
            // PackageManager can lag the broadcast, so the installed list is
            // worth rescanning even while the hand-off stays pending.
            installer.notifyPackageChanged(packageName)
        }
        // The broadcast is the installer's own proof that it closed, so the slot
        // is free even when PackageManager has not caught up yet.
        systemInstallSerializer.markCompleted(packageName, InstallTaskRegistry.idOf(pending))
    }

    /** Re-checks a persisted hand-off through the shared confirmation path. */
    suspend fun recordPendingInstallResult(pending: PendingInstall) {
        completePendingInstall(pending.packageName, pending)
    }

    suspend fun prunePendingInstalls(installed: List<InstalledModule>): List<PendingInstall> {
        val entries = pendingInstallRepository.pending.first().values.toList()
        val states = libraryRepository.variantStates.first()
        return settleAndPrune(entries, states, installed)
    }

    /** Opening a detail page only reconciles hand-offs and records for that module. */
    suspend fun prunePendingInstallsForModule(repoPackage: String): List<PendingInstall> {
        val entries = pendingInstallRepository.pending.first().values.filter {
            (it.repoPackageName ?: it.packageName) == repoPackage
        }
        val states = libraryRepository.variantStates.first().filterKeys { it == repoPackage }
        val names = (entries.map { it.packageName } + states.values.mapNotNull {
            it.confirmedInstall?.packageName
        }).toSet()
        val installed = names.mapNotNull { installedRepository.getInstalledModule(it) }
        return settleAndPrune(entries, states, installed)
    }

    private suspend fun settleAndPrune(
        entries: List<PendingInstall>,
        states: Map<String, ModuleVariantState>,
        installed: List<InstalledModule>,
    ): List<PendingInstall> {
        val byPackage = installed.associateBy { it.packageName }
        val reached = mutableListOf<PendingInstall>()
        entries.forEach { entry ->
            when (settlePendingInstall(entry, byPackage[entry.packageName], System.currentTimeMillis(), DEFAULT_PENDING_MAX_AGE_MS, installer.foregroundState.isResumed)) {
                PendingSettlement.LANDED -> {
                    completePendingInstall(entry.packageName, entry)
                    reached += entry
                }
                PendingSettlement.ABANDONED -> cancelPendingInstall(entry.packageName, entry)
                PendingSettlement.PENDING -> Unit
            }
        }
        // Drop invalid local source records as soon as a scan observes a replacement.
        states.forEach { (repo, state) ->
            val record = state.confirmedInstall ?: return@forEach
            val match = byPackage[record.packageName]
            if (!record.matches(match) && !record.matches(installConfirmation.installed(record.packageName))) {
                libraryRepository.invalidateConfirmedInstall(repo, record)
            }
        }
        return reached
    }

    /**
     * Finishes delegated installs when the app comes back to the foreground.
     *
     * `PACKAGE_ADDED` is an implicit broadcast: manifest receivers do not get it
     * while the app is backgrounded (Android 8+), which is exactly the state an
     * external installer puts us in. Querying target packages on resume is the
     * only reliable completion signal, so pending installs are reconciled here.
     */
    suspend fun reconcilePendingInstalls(
        retries: Int = 3,
        retryDelayMs: Long = 1200L,
        abandonAfterMs: Long = ABANDONED_PENDING_MS,
        abandonUnresolved: Boolean = false,
        snapshot: Collection<PendingInstall>? = null,
    ) {
        val entries = snapshot ?: pendingInstallRepository.pending.first().values.toList()
        var unresolved = emptyList<PendingInstall>()
        var unreadable = emptySet<String>()
        repeat(retries) { attempt ->
            val current = pendingInstallRepository.pending.first()
            val pendingInstalls = entries.filter { current[it.packageName] == it }
            if (pendingInstalls.isEmpty()) return
            val stillOpen = mutableListOf<PendingInstall>()
            val checks = checkPendingInstalls(
                pendingInstalls,
                installedRepository::getInstalledModule,
                System.currentTimeMillis(),
                abandonAfterMs,
                installer.foregroundState.isResumed,
            )
            unreadable = checks.filterNot { it.readable }.map { it.pending.packageName }.toSet()
            checks.forEach { check ->
                val pending = check.pending
                when (check.settlement) {
                    PendingSettlement.LANDED -> completePendingInstall(pending.packageName, pending)
                    PendingSettlement.ABANDONED -> {
                        if (installer.foregroundState.isResumed) {
                            cancelPendingInstall(pending.packageName, pending)
                        }
                    }
                    PendingSettlement.PENDING -> stillOpen += pending
                }
            }
            unresolved = stillOpen
            if (stillOpen.isEmpty()) return
            if (attempt < retries - 1) kotlinx.coroutines.delay(retryDelayMs)
        }
        // The device never reported the target, so the hand-off was dismissed or
        // cancelled. Keeping the entry would hide the update prompt (pending
        // packages are filtered out of the update list) until it ages out, so it
        // is dropped here: no result is recorded and the module keeps its state.
        // `abandonUnresolved` is used after the installer returns to the app,
        // where "still not installed" can only mean the dialog was cancelled.
        val now = System.currentTimeMillis()
        unresolved.forEach { pending ->
            if (pending.packageName in unreadable) return@forEach
            val expired = abandonUnresolved ||
                (pending.createdAt > 0L && now - pending.createdAt > abandonAfterMs)
            if (expired && installer.foregroundState.isResumed) {
                cancelPendingInstall(pending.packageName, pending)
            }
        }
    }

    /**
     * Runs when the app comes back to the foreground after an installer round
     * trip. A cancelled installer screen sends no broadcast but does close, so
     * this is where its slot and pending entry are released.
     */
    suspend fun reconcileAfterInstallerReturn(returningTasks: Set<String>) {
        if (!installer.foregroundState.isResumed) return
        val entries = pendingInstallRepository.pending.first().values.filter { InstallTaskRegistry.idOf(it) in returningTasks }
        entries.forEach {
            if (!installer.foregroundState.isResumed) return
            systemInstallSerializer.markCompleted(it.packageName, InstallTaskRegistry.idOf(it))
        }
        reconcilePendingInstalls(
            retries = 4,
            retryDelayMs = 800L,
            abandonUnresolved = true,
            snapshot = entries,
        )
    }

    private companion object {
        /**
         * Grace period before an unresolved pending install is treated as
         * abandoned. Long enough to cover PackageManager lag on a slow device
         * right after the installer returns.
         */
        const val ABANDONED_PENDING_MS: Long = 60_000L
    }
}
