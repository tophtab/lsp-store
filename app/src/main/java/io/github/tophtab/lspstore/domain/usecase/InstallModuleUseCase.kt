package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.data.install.InstallExecution
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.DownloadManager
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
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
        val target: ResolvedInstallTarget? = null,
        val taskId: String? = null,
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
                state = state.forInstalled(info.installed),
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
                    PreparedInstall.Ready(info, file, asset, state, ResolvedInstallTarget.of(release, asset))
                }

                is VariantDecision.NotApplicable -> {
                    libraryRepository.recordInstallResult(info.module.packageName, ok = false)
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
    private val execution: InstallExecution,
    private val allowUi: Boolean = true,
) : ModuleInstaller {
    override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
        val info = prepared.info
        return execution.install(
            info.module.packageName, info.module.title, prepared.asset, prepared.file,
            info.installed.packageName, prepared.target, requireNotNull(prepared.taskId),
            info.latest.versionCode, info.latest.versionName, allowUi,
        )
    }
}
