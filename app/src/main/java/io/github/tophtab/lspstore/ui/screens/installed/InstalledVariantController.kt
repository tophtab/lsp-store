package io.github.tophtab.lspstore.ui.screens.installed

import io.github.tophtab.lspstore.data.install.ModuleInstallRequest
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.model.tracksPreReleases
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class InstalledVariantText(
    val empty: () -> String,
    val loadFailed: () -> String,
    val saved: (automatic: Boolean) -> String,
)

/** Loads picker choices and persists preferences without owning another UI state flow. */
internal class InstalledVariantController(
    private val scope: CoroutineScope,
    private val readState: () -> InstalledUiState,
    private val updateState: ((InstalledUiState) -> InstalledUiState) -> Unit,
    private val getDetail: suspend (String) -> ModuleDetail,
    private val titleFor: (String) -> String,
    private val installedFor: (String) -> InstalledModule?,
    private val resolveVariant: ResolveVariantUseCase,
    private val device: DeviceProfile,
    private val clearPreference: suspend (String) -> Unit,
    private val savePreference: suspend (String, VariantSignature) -> Unit,
    private val startInstall: (ModuleInstallRequest) -> Unit,
    private val text: InstalledVariantText,
) {
    private var pickerJob: Job? = null
    private var pickerGeneration = 0L

    fun openVariantPicker(repoPackageName: String, caller: UpdateInfo? = null) {
        if (readState().variantPicker != null) return
        val generation = ++pickerGeneration
        updateState {
            it.copy(variantPicker = InstalledVariantPicker(repoPackageName, titleFor(repoPackageName), null, caller = caller))
        }
        pickerJob = scope.launch {
            try {
                val detail = getDetail(repoPackageName)
                currentCoroutineContext().ensureActive()
                val installed = caller?.installed ?: installedFor(repoPackageName)
                val includePreReleases = readState().preReleases.tracksPreReleases(installed?.packageName, repoPackageName)
                val release = detail.latestReleaseFor(includePreReleases)
                if (release == null) {
                    updatePicker(generation, repoPackageName) { it.copy(isLoading = false, error = text.empty()) }
                    return@launch
                }
                val states = readState().variantStates
                val state = (states[repoPackageName] ?: states[installed?.packageName] ?: ModuleVariantState()).forInstalled(installed)
                val installedHint = installed?.let(InstalledVariantHint::of)
                val expectedPackage = expectedPackage(installed?.packageName, repoPackageName)
                val initial = resolveVariant.initial(release, state, device, expectedPackage, installedHint)
                updatePicker(generation, repoPackageName) {
                    it.copy(candidates = initial.candidates, release = release)
                }
                val resolution = resolveVariant.resolve(
                    release, state, device, expectedPackage,
                    forceProbe = true, installed = installedHint,
                )
                currentCoroutineContext().ensureActive()
                val recommended = (resolution.decision as? VariantDecision.Pick)?.asset?.name
                val candidates = resolution.candidates
                val selected = state.migrated().explicitPreference?.let { signature ->
                    candidates.firstOrNull { signature.matches(it.asset.name, it.traits) }?.asset?.name
                } ?: recommended
                updatePicker(generation, repoPackageName) {
                    it.copy(
                        candidates = candidates,
                        release = release,
                        selectedAssetName = selected,
                        isLoading = false,
                        error = if (candidates.isEmpty()) text.empty() else null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updatePicker(generation, repoPackageName) { it.copy(isLoading = false, error = text.loadFailed()) }
            }
        }
    }

    fun selectVariant(assetName: String?) {
        val picker = readState().variantPicker ?: return
        if (picker.isLoading || picker.error != null) return
        val generation = pickerGeneration
        val asset = picker.candidates.firstOrNull { it.asset.name == assetName }?.asset
        scope.launch {
            if (asset == null) clearPreference(picker.repoPackageName)
            else savePreference(picker.repoPackageName, VariantSignature.ofAsset(asset.name))
            currentCoroutineContext().ensureActive()
            updateState {
                if (generation != pickerGeneration) return@updateState it
                it.copy(variantPicker = null, message = text.saved(asset == null))
            }
            val caller = picker.caller
            if (asset != null && caller != null) {
                startInstall(
                    ModuleInstallRequest(
                        repoPackageName = picker.repoPackageName,
                        title = caller.module.title,
                        asset = asset,
                        target = picker.release?.let { ResolvedInstallTarget.of(it, asset, explicit = true) },
                        installedPackage = caller.installed.packageName,
                        versionCode = caller.latest.versionCode,
                        versionName = caller.latest.versionName,
                    ),
                )
            }
        }
    }

    fun dismissVariantPicker() {
        pickerGeneration++
        pickerJob?.cancel()
        pickerJob = null
        updateState { it.copy(variantPicker = null) }
    }

    private fun updatePicker(
        generation: Long,
        packageName: String,
        transform: (InstalledVariantPicker) -> InstalledVariantPicker,
    ) {
        updateState { current ->
            val picker = current.variantPicker ?: return@updateState current
            if (generation != pickerGeneration || picker.repoPackageName != packageName) return@updateState current
            current.copy(variantPicker = transform(picker))
        }
    }
}
