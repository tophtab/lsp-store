package org.lspstore.domain.usecase

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import org.lspstore.R
import org.lspstore.data.install.ApkGate
import org.lspstore.data.install.ApkInstaller
import org.lspstore.data.install.DownloadManager
import org.lspstore.data.install.GateResult
import org.lspstore.data.install.InstallOutcome
import org.lspstore.data.install.SystemInstallSerializer
import org.lspstore.data.repository.LibraryRepository
import org.lspstore.data.repository.ModuleRepository
import org.lspstore.data.repository.PendingInstallRepository
import org.lspstore.data.repository.SettingsRepository
import org.lspstore.domain.model.PendingInstall
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.variant.DeviceProfile
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.VariantDecision
import org.lspstore.domain.variant.VariantSignature
import org.lspstore.domain.variant.onEvaluatedNoCandidate
import org.lspstore.domain.variant.parseTraits
import org.lspstore.ui.variant.variantReasonText
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

    /** Low confidence and no remembered choice: the user must pick a variant. */
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
        channel: UpdateChannel,
        state: ModuleVariantState,
        onAssetResolved: (ReleaseAsset) -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PreparedInstall = try {
        val detail = moduleRepository.getDetail(info.module.packageName)
        val release = detail.latestReleaseFor(channel)
        if (release == null) {
            PreparedInstall.Failed(info, context.getString(R.string.error_no_apk_asset))
        } else {
            // Lazy validation: TRUSTED skips probing; UNKNOWN/SUSPECT/ambiguous probe.
            val resolution = resolveVariant.resolve(
                release = release,
                state = state,
                device = deviceProfile(),
                expectedPackage = info.installed.packageName,
            )
            when (val decision = resolution.decision) {
                is VariantDecision.Pick -> {
                    val asset = decision.asset
                    onAssetResolved(asset)
                    val file = downloadManager.downloadBlocking(asset, onProgress)
                    PreparedInstall.Ready(info, file, asset, state)
                }

                is VariantDecision.Choose -> PreparedInstall.NeedsVariant(info)

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
 * Installs one already-downloaded APK: local gate, global install slot, then the
 * outcome bookkeeping (pending state, variant memory, install result).
 */
class InstallPreparedApkUseCase(
    private val installer: ApkInstaller,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val systemInstallSerializer: SystemInstallSerializer,
    private val deviceProfile: () -> DeviceProfile,
    private val context: Context,
) : ModuleInstaller {

    override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
        val info = prepared.info
        val packageName = info.installed.packageName
        // Local gate: zero network, before the APK reaches the installer.
        when (val gate = ApkGate.check(context, prepared.file, packageName, deviceProfile())) {
            is GateResult.Reject -> {
                // One failed attempt, nothing persisted: a gate hit does not make
                // the module unfit, and it must stay in the update list.
                libraryRepository.recordInstallResult(info.module.packageName, ok = false)
                return InstallOutcome.Failure(context.variantReasonText(gate.reason))
            }

            is GateResult.Warn -> Unit
            GateResult.Ok -> Unit
        }
        val outcome = try {
            val useRoot = settingsRepository.rootInstall.first()
            // komi's orchestrator order: everything is ready before the single
            // install slot is taken, then the APK is handed over.
            systemInstallSerializer.awaitFreeAndMarkPending(packageName)
            // Verify and update the installed package, which may differ from
            // the repository listing for imported/linked modules.
            installer.install(prepared.file, packageName, useRoot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            InstallOutcome.Failure(
                error.message ?: context.getString(R.string.error_install_failed),
            )
        }
        when (outcome) {
            InstallOutcome.Completed -> {
                systemInstallSerializer.markCompleted(packageName)
                pendingRepository.clear(packageName)
                libraryRepository.recordInstallResult(
                    info.module.packageName,
                    ok = true,
                    signature = VariantSignature.of(parseTraits(prepared.asset.name)),
                )
            }
            // Delegated installs keep the slot until the package broadcast or the
            // serializer timeout releases it.
            InstallOutcome.DelegatedToSystem -> pendingRepository.markPending(
                PendingInstall(
                    packageName = packageName,
                    repoPackageName = info.module.packageName,
                    title = info.module.title,
                    versionCode = info.latest.versionCode,
                    versionName = info.latest.versionName,
                    assetName = prepared.asset.name,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            is InstallOutcome.Failure -> {
                systemInstallSerializer.markCompleted(packageName)
                libraryRepository.recordInstallResult(info.module.packageName, ok = false)
            }
        }
        return outcome
    }
}
