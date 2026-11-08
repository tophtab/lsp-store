package io.github.tophtab.lspstore.di

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
import io.github.tophtab.lspstore.domain.usecase.settlePendingInstall
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.parseTraits

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

    /** One gate for batch updates and module page installs. */
    val downloadSlots: DownloadSlots = DownloadSlots { _maxConcurrentDownloads.value }

    val libraryRepository: LibraryRepository = LibraryRepositoryImpl(context)
    val libraryBackupStore: LibraryBackupStore = LibraryBackupStore(appContext)
    val pendingInstallRepository: PendingInstallRepository =
        PendingInstallRepositoryImpl(context)
    val apkExportStore: ApkExportStore = ApkExportStore(appContext)
    val downloadManager: DownloadManager = DownloadManager(context, apkExportStore)
    val installer: ApkInstaller = ApkInstaller(context, downloadManager)
    val systemInstallSerializer: SystemInstallSerializer = SystemInstallSerializer()

    /**
     * Application-lifetime scope for installs started from a module page. It is
     * deliberately not tied to any ViewModel: pressing back mid-download must not
     * cancel the install (komi keeps it running too).
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Mirror the setting once at startup and whenever it changes. The gate
        // itself is a plain lambda read, so no download waits on DataStore.
        installScope.launch {
            settingsRepository.maxConcurrentDownloads.collect { value ->
                _maxConcurrentDownloads.value = value
            }
        }
    }

    /** Download → gate → installer chain shared by every module page. */
    val moduleInstallCoordinator: ModuleInstallCoordinator = ModuleInstallCoordinator(
        context = appContext,
        scope = installScope,
        downloadManager = downloadManager,
        downloadSlots = downloadSlots,
        installer = installer,
        settingsRepository = settingsRepository,
        libraryRepository = libraryRepository,
        pendingRepository = pendingInstallRepository,
        systemInstallSerializer = systemInstallSerializer,
        deviceProfile = { deviceProfile },
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

    /** Gate + global install slot + outcome bookkeeping for one downloaded APK. */
    val installPreparedApkUseCase: InstallPreparedApkUseCase = InstallPreparedApkUseCase(
        installer = installer,
        settingsRepository = settingsRepository,
        libraryRepository = libraryRepository,
        pendingRepository = pendingInstallRepository,
        systemInstallSerializer = systemInstallSerializer,
        deviceProfile = { deviceProfile },
        context = appContext,
    )

    /** "Update all": concurrent downloads, serialized installs. */
    val batchInstallUseCase: BatchInstallUseCase = BatchInstallUseCase(
        prepareInstall = prepareModuleInstallUseCase,
        installPrepared = installPreparedApkUseCase,
        abandonDownload = { asset -> downloadManager.cancel(downloadManager.keyOf(asset)) },
        downloadSlots = downloadSlots,
    )

    /** Background twin: same bookkeeping, never opens an installer UI. */
    private val silentInstallPreparedApkUseCase: InstallPreparedApkUseCase = InstallPreparedApkUseCase(
        installer = installer,
        settingsRepository = settingsRepository,
        libraryRepository = libraryRepository,
        pendingRepository = pendingInstallRepository,
        systemInstallSerializer = systemInstallSerializer,
        deviceProfile = { deviceProfile },
        context = appContext,
        allowUi = false,
    )

    /** "Update all" for the background check: a non-root install just fails. */
    private val backgroundBatchInstallUseCase: BatchInstallUseCase = BatchInstallUseCase(
        prepareInstall = prepareModuleInstallUseCase,
        installPrepared = silentInstallPreparedApkUseCase,
        abandonDownload = { asset -> downloadManager.cancel(downloadManager.keyOf(asset)) },
        downloadSlots = downloadSlots,
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
    suspend fun completePendingInstall(packageName: String) {
        val pending = runCatching { pendingInstallRepository.pending.first()[packageName] }
            .getOrNull() ?: return
        recordPendingInstallResult(pending)
        pendingInstallRepository.clear(packageName)
        systemInstallSerializer.markCompleted(packageName)
        installer.confirmInstalled(packageName)
    }

    /**
     * Drops a hand-off that never landed (installer dialog dismissed, package
     * uninstalled). Nothing is recorded, so the module keeps its update and the
     * variant memory is not marked as a successful install.
     */
    suspend fun cancelPendingInstall(packageName: String) {
        pendingInstallRepository.clear(packageName)
        systemInstallSerializer.markCompleted(packageName)
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
            if (pending != null) cancelPendingInstall(packageName)
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
            )
        ) {
            completePendingInstall(packageName)
        } else {
            if (
                pending.createdAt > 0L &&
                System.currentTimeMillis() - pending.createdAt > ABANDONED_PENDING_MS
            ) {
                cancelPendingInstall(packageName)
            }
            // PackageManager can lag the broadcast, so the installed list is
            // worth rescanning even while the hand-off stays pending.
            installer.notifyPackageChanged(packageName)
        }
        // The broadcast is the installer's own proof that it closed, so the slot
        // is free even when PackageManager has not caught up yet.
        systemInstallSerializer.markCompleted(packageName)
    }

    /**
     * Records a delegated install as successful. The asset name that was handed
     * to the installer is the only trace of which variant landed, so it becomes
     * the remembered signature.
     */
    suspend fun recordPendingInstallResult(pending: PendingInstall) {
        val packageName = pending.repoPackageName ?: pending.packageName
        libraryRepository.recordInstallResult(
            packageName,
            ok = true,
            signature = pending.assetName
                .takeIf { it.isNotBlank() }
                ?.let { VariantSignature.of(parseTraits(it)) },
        )
    }

    /**
     * Prunes pending installs after an installed scan and records the ones whose
     * target version has landed. Callers use this instead of touching the
     * repository directly so a completed install is never dropped unrecorded.
     */
    suspend fun prunePendingInstalls(installed: List<InstalledModule>): List<PendingInstall> {
        val reached = runCatching { pendingInstallRepository.prune(installed) }
            .getOrDefault(emptyList())
        reached.forEach { recordPendingInstallResult(it) }
        return reached
    }

    /**
     * Finishes delegated installs when the app comes back to the foreground.
     *
     * `PACKAGE_ADDED` is an implicit broadcast: manifest receivers do not get it
     * while the app is backgrounded (Android 8+), which is exactly the state an
     * external installer puts us in. Rescanning PackageManager on resume is the
     * only reliable completion signal, so pending installs are reconciled here.
     */
    suspend fun reconcilePendingInstalls(
        retries: Int = 3,
        retryDelayMs: Long = 1200L,
        abandonAfterMs: Long = ABANDONED_PENDING_MS,
        abandonUnresolved: Boolean = false,
    ) {
        var unresolved = emptyList<PendingInstall>()
        repeat(retries) { attempt ->
            val pendingInstalls = runCatching { pendingInstallRepository.pending.first() }
                .getOrDefault(emptyMap())
            if (pendingInstalls.isEmpty()) return
            val installed = runCatching { installedRepository.getInstalledModules(force = true) }
                .getOrNull() ?: return
            val stillOpen = mutableListOf<PendingInstall>()
            pendingInstalls.values.forEach { pending ->
                val match = installed.firstOrNull { it.packageName == pending.packageName }
                when (
                    settlePendingInstall(
                        pending = pending,
                        installed = match,
                        now = System.currentTimeMillis(),
                        abandonAfterMs = abandonAfterMs,
                    )
                ) {
                    PendingSettlement.LANDED -> completePendingInstall(pending.packageName)
                    PendingSettlement.ABANDONED -> cancelPendingInstall(pending.packageName)
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
            val expired = abandonUnresolved ||
                (pending.createdAt > 0L && now - pending.createdAt > abandonAfterMs)
            if (expired) cancelPendingInstall(pending.packageName)
        }
    }

    /**
     * Runs when the app comes back to the foreground after an installer round
     * trip. A cancelled installer screen sends no broadcast but does close, so
     * this is where its slot and pending entry are released.
     */
    suspend fun reconcileAfterInstallerReturn() {
        val pendingKeys = runCatching { pendingInstallRepository.pending.first().keys }
            .getOrDefault(emptySet())
        // Release the gate before reconciling: a cancelled dialog would otherwise
        // make the next install wait for its full timeout.
        pendingKeys.forEach { systemInstallSerializer.markCompleted(it) }
        reconcilePendingInstalls(
            retries = 4,
            retryDelayMs = 800L,
            abandonUnresolved = true,
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
