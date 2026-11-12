package io.github.tophtab.lspstore.domain.usecase

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.ApkGate
import io.github.tophtab.lspstore.data.install.ApkInstaller
import io.github.tophtab.lspstore.data.install.DownloadManager
import io.github.tophtab.lspstore.data.install.GateResult
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
import io.github.tophtab.lspstore.domain.variant.parseTraits
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
            // Lazy validation: TRUSTED skips probing; UNKNOWN/SUSPECT/ambiguous probe.
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
    private val allowUi: Boolean = true,
) : ModuleInstaller {

    override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
        val info = prepared.info
        val packageName = info.installed.packageName
        var installTarget = packageName
        var linkAfterInstall: Pair<String, String>? = null
        // Local gate: zero network, before the APK reaches the installer.
        when (val gate = ApkGate.check(context, prepared.file, installTarget, deviceProfile())) {
            is GateResult.Reject -> {
                // One failed attempt, nothing persisted: a gate hit does not make
                // the module unfit, and it must stay in the update list.
                libraryRepository.recordInstallResult(info.module.packageName, ok = false)
                return InstallOutcome.Failure(context.variantReasonText(gate.reason))
            }

            is GateResult.Warn -> {
                val archivePackage = gate.reason.args.firstOrNull()
                if (gate.reason.kind == Reason.Kind.PACKAGE_MISMATCH &&
                    !archivePackage.isNullOrBlank() &&
                    archivePackage != installTarget
                ) {
                    installTarget = archivePackage
                    linkAfterInstall = archivePackage to info.module.packageName
                }
            }
            GateResult.Ok -> Unit
        }
        var acquired = false
        val outcome = try {
            val useRoot = settingsRepository.rootInstall.first()
            // komi's orchestrator order: everything is ready before the single
            // install slot is taken, then the APK is handed over.
            systemInstallSerializer.awaitFreeAndMarkPending(installTarget)
            acquired = true
            // Verify and update the installed package, which may differ from
            // the repository listing for imported/linked modules.
            installer.install(prepared.file, useRoot, allowUi)
        } catch (cancellation: CancellationException) {
            if (acquired) systemInstallSerializer.markCompleted(installTarget)
            throw cancellation
        } catch (error: Throwable) {
            InstallOutcome.Failure(
                error.message ?: context.getString(R.string.error_install_failed),
            )
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
                    signature = VariantSignature.of(parseTraits(prepared.asset.name)),
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
                        versionCode = info.latest.versionCode,
                        versionName = info.latest.versionName,
                        assetName = prepared.asset.name,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                linkAfterInstall?.let { (installedPackage, repoPackage) ->
                    libraryRepository.linkModule(installedPackage, repoPackage)
                }
            }
            is InstallOutcome.Failure -> {
                systemInstallSerializer.markCompleted(installTarget)
                libraryRepository.recordInstallResult(info.module.packageName, ok = false)
            }
        }
        return outcome
    }
}
