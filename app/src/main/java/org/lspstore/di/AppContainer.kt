package org.lspstore.di

import android.content.Context
import kotlinx.coroutines.flow.first
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.lspstore.data.device.DeviceProfileProvider
import org.lspstore.data.install.ApkInstaller
import org.lspstore.data.install.DownloadManager
import org.lspstore.data.install.SystemInstallSerializer
import org.lspstore.data.probe.RangeProbe
import org.lspstore.data.remote.LsposedRepoApi
import org.lspstore.data.remote.MirrorState
import org.lspstore.data.remote.MirrorTester
import org.lspstore.data.remote.createHttpClient
import org.lspstore.data.remote.createJson
import org.lspstore.data.remote.createProbeHttpClient
import org.lspstore.data.repository.InstalledModulesRepository
import org.lspstore.data.repository.InstalledModulesRepositoryImpl
import org.lspstore.data.repository.LibraryRepository
import org.lspstore.data.repository.LibraryRepositoryImpl
import org.lspstore.data.repository.ModuleRepository
import org.lspstore.data.repository.ModuleRepositoryImpl
import org.lspstore.data.repository.PendingInstallRepository
import org.lspstore.data.repository.PendingInstallRepositoryImpl
import org.lspstore.data.repository.SettingsRepository
import org.lspstore.data.repository.SettingsRepositoryImpl
import org.lspstore.data.translation.TranslationRepository
import org.lspstore.domain.usecase.BatchInstallUseCase
import org.lspstore.domain.usecase.InstallProbe
import org.lspstore.domain.usecase.InstallSettlement
import org.lspstore.domain.usecase.InstallPreparedApkUseCase
import org.lspstore.domain.usecase.PrepareModuleInstallUseCase
import org.lspstore.domain.usecase.ResolveVariantUseCase
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.PendingInstall
import org.lspstore.domain.variant.ApkProbe
import org.lspstore.domain.variant.DeviceProfile
import org.lspstore.domain.variant.VariantSignature
import org.lspstore.domain.variant.parseTraits

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
    val libraryRepository: LibraryRepository = LibraryRepositoryImpl(context)
    val pendingInstallRepository: PendingInstallRepository =
        PendingInstallRepositoryImpl(context)
    val downloadManager: DownloadManager = DownloadManager(context)
    val installer: ApkInstaller = ApkInstaller(context, downloadManager)
    val systemInstallSerializer: SystemInstallSerializer = SystemInstallSerializer()
    val translationRepository: TranslationRepository = TranslationRepository(httpClient, settingsRepository)
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
    /** Landing detection for delegated installs (polling, no package broadcast). */
    val installSettlement: InstallSettlement = InstallSettlement()

    val batchInstallUseCase: BatchInstallUseCase = BatchInstallUseCase(
        prepareInstall = prepareModuleInstallUseCase,
        installPrepared = installPreparedApkUseCase,
        awaitInstallSettled = ::awaitPendingInstallSettled,
        releaseInstallSlot = { packageName -> systemInstallSerializer.markCompleted(packageName) },
        abandonDownload = { asset -> downloadManager.cancel(downloadManager.keyOf(asset)) },
    )

    /**
     * Finishes one delegated install: records the variant outcome, releases the
     * install slot and persisted pending state, then tells the UI to refresh.
     *
     * The record is written here, from the persisted [org.lspstore.domain.model.PendingInstall],
     * and not from a screen: the completion signal may arrive while no detail or
     * installed screen is composed (background broadcast, resume after a
     * system-installer round trip).
     */
    suspend fun completePendingInstall(packageName: String) {
        val pending = runCatching { pendingInstallRepository.pending.first()[packageName] }
            .getOrNull()
        if (pending != null) recordPendingInstallResult(pending)
        pendingInstallRepository.clear(packageName)
        systemInstallSerializer.markCompleted(packageName)
        installer.confirmInstalled(packageName)
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
                val landed = match != null &&
                    (pending.versionCode == 0L || match.versionCode >= pending.versionCode)
                if (landed) {
                    completePendingInstall(pending.packageName)
                } else {
                    // PackageManager can still report the old version for a moment
                    // after the installer returns; retry shortly.
                    stillOpen += pending
                }
            }
            unresolved = stillOpen
            if (stillOpen.isEmpty()) return
            if (attempt < retries - 1) kotlinx.coroutines.delay(retryDelayMs)
        }
        // The installed version never moved, so the hand-off was dismissed or
        // cancelled. Keeping the entry would hide the update prompt (pending
        // packages are filtered out of the update list) until it ages out, so it
        // is dropped here: no result is recorded and the module keeps its state.
        val now = System.currentTimeMillis()
        unresolved.forEach { pending ->
            if (pending.createdAt > 0L && now - pending.createdAt > abandonAfterMs) {
                pendingInstallRepository.clear(pending.packageName)
                systemInstallSerializer.markCompleted(pending.packageName)
            }
        }
    }

    /**
     * Waits for a delegated install to actually land on the device.
     *
     * `PACKAGE_ADDED` never reaches the receiver while another app is on screen,
     * and the system installer keeps its "App installed" screen open until the
     * user dismisses it, so the batch cannot rely on the resume hook: it polls
     * PackageManager instead and records the outcome as soon as the target
     * version shows up. Returns false when the hand-off never produced a package.
     */
    suspend fun awaitPendingInstallSettled(
        packageName: String,
        timeoutMs: Long = InstallSettlement.DEFAULT_TIMEOUT_MS,
        pollMs: Long = InstallSettlement.DEFAULT_POLL_MS,
    ): Boolean {
        val outcome = installSettlement.await(timeoutMs = timeoutMs, pollMs = pollMs) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val pending = runCatching {
                    pendingInstallRepository.pending.first()[packageName]
                }.getOrNull()
                InstallProbe(
                    pending = pending,
                    installedVersionCode = runCatching { installedVersionCode(packageName) }
                        .getOrNull(),
                )
            }
        }
        if (outcome == InstallSettlement.Outcome.SETTLED) {
            completePendingInstall(packageName)
            return true
        }
        return false
    }

    /** The cheap PackageManager lookup the settlement poll uses. */
    @Suppress("DEPRECATION")
    private fun installedVersionCode(packageName: String): Long {
        val info = appContext.packageManager.getPackageInfo(packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
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
