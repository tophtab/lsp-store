package io.github.tophtab.lspstore.domain.usecase

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.readInstallIdentity
import io.github.tophtab.lspstore.data.install.assetCacheKey
import io.github.tophtab.lspstore.data.install.ApkInstallDriver
import io.github.tophtab.lspstore.data.install.installWithSlot
import io.github.tophtab.lspstore.data.install.DownloadManager
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.data.install.SystemInstallSerializer
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onEvaluatedNoCandidate
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import java.io.File

/** Result of resolving a release asset and downloading it. */
sealed interface PreparedInstall {
    val info: UpdateInfo

    data class Ready(
        override val info: UpdateInfo,
        val file: File,
        val asset: ReleaseAsset,
        val state: ModuleVariantState,
    ) : PreparedInstall

    data class Failed(override val info: UpdateInfo, val message: String) : PreparedInstall

    /**
     * Low confidence and no remembered choice: the user must pick a variant.
     * The resolver always returns a default, so this is a safety net for a future
     * decision type rather than a state the current resolver produces.
     */
    data class NeedsVariant(override val info: UpdateInfo) : PreparedInstall
}

/**
 * Resolves which APK of the latest release fits this device and downloads it,
 * without touching the global install slot. Shared by the single-module update
 * button, the batch queue and (through it) the detail screen.
 */
class PrepareModuleInstallUseCase(
    private val moduleRepository: ModuleRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadManager: DownloadManager,
    private val resolveVariant: ResolveVariantUseCase,
    private val deviceProfile: () -> DeviceProfile,
    private val context: Context,
) : ModuleInstallPreparer {

    override suspend fun prepare(
        info: UpdateInfo,
        includePreReleases: Boolean,
        state: ModuleVariantState,
        onAssetResolved: (ReleaseAsset) -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PreparedInstall = try {
        val detail = moduleRepository.getDetail(info.module.packageName)
        val release = detail.releaseForUpdate(info.latest, includePreReleases)
        if (release == null) {
            PreparedInstall.Failed(info, context.getString(R.string.error_repository_stale))
        } else {
            // Probe ambiguous defaults without changing remembered choices.
            val resolution = resolveVariant.resolve(
                release = release,
                state = state,
                device = deviceProfile(),
                expectedPackage = info.installed.packageName,
                // An update follows the copy that is already on the device; this
                // is the one path where the installed build, not the repository's
                // most popular one, is the reference.
                installed = InstalledVariantHint.of(info.installed),
            )
            when (val decision = resolution.decision) {
                is VariantDecision.Pick -> {
                    // Every decision carries a default now, including the ones that
                    // only the preference ladder decided: "update all" and the
                    // single update button download it instead of stopping to ask.
                    // The module page's resource picker stays the override.
                    val asset = decision.asset
                    onAssetResolved(asset)
                    val file = downloadManager.downloadBlocking(asset, onProgress)
                    PreparedInstall.Ready(info, file, asset, state)
                }

                is VariantDecision.NotApplicable -> {
                    libraryRepository.setVariantState(
                        info.module.packageName,
                        state.onEvaluatedNoCandidate(decision.reason),
                    )
                    PreparedInstall.Failed(info, context.variantReasonText(decision.reason))
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        PreparedInstall.Failed(
            info,
            error.message ?: context.getString(R.string.error_install_failed),
        )
    }
}

/**
 * Installs one already-downloaded APK: global install slot, then the
 * outcome bookkeeping (pending state, variant memory, install result).
 */
class InstallPreparedApkUseCase(
    private val installer: ApkInstallDriver,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val systemInstallSerializer: SystemInstallSerializer,
    private val failureMessage: (Throwable) -> String,
    private val allowUi: Boolean = true,
) : ModuleInstaller {

    override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
        val info = prepared.info
        val identity = readInstallIdentity(prepared.file)
        val installTarget = identity?.packageName ?: info.installed.packageName
        val linkAfterInstall = installTarget.takeIf { it != info.module.packageName }
            ?.let { it to info.module.packageName }
        val outcome = try {
            installWithSlot(
                installer, systemInstallSerializer, prepared.file, installTarget,
                useRoot = settingsRepository.rootInstall.first(), allowUi = allowUi,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            InstallOutcome.Failure(failureMessage(error))
        }
        when (outcome) {
            InstallOutcome.Completed -> {
                systemInstallSerializer.markCompleted(installTarget)
                pendingRepository.clear(installTarget)
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
                libraryRepository.recordInstallResult(
                    info.module.packageName,
                    ok = true,
                    signature = VariantSignature.ofAsset(prepared.asset.name, installTarget),
                )
            }
            // Delegated installs keep the slot until the package broadcast or the
            // serializer timeout releases it.
            InstallOutcome.DelegatedToSystem -> {
                pendingRepository.markPending(
                    PendingInstall(
                        packageName = installTarget,
                        repoPackageName = info.module.packageName,
                        title = info.module.title,
                        versionCode = identity?.versionCode ?: info.latest.versionCode,
                        versionName = identity?.versionName ?: info.latest.versionName,
                        assetName = prepared.asset.name,
                        createdAt = System.currentTimeMillis(),
                        downloadKey = assetCacheKey(prepared.asset),
                    ),
                )
            }
            is InstallOutcome.Failure -> {
                systemInstallSerializer.markCompleted(installTarget)
                pendingRepository.clear(installTarget)
                installer.invalidateDownload(prepared.asset)
                libraryRepository.recordInstallResult(info.module.packageName, ok = false)
            }
        }
        return outcome
    }
}
