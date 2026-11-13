package io.github.tophtab.lspstore.ui.screens.detail

import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.usecase.ReleaseProbeRequests
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Metadata belongs to one release request, including when the same tag is reloaded. */
internal class DetailProbeController(
    private val scope: CoroutineScope,
    private val readState: () -> ModuleDetailUiState,
    private val updateState: ((ModuleDetailUiState) -> ModuleDetailUiState) -> Unit,
    private val repoPackageName: String,
    private val resolveVariant: ResolveVariantUseCase,
    private val device: DeviceProfile,
    private val variantState: () -> ModuleVariantState,
) {
    private var probeJob: Job? = null
    private val requests = ReleaseProbeRequests()

    fun cancel() {
        requests.invalidate()
        probeJob?.cancel()
        probeJob = null
    }

    fun probeRelease(release: Release, forceProbe: Boolean = false) {
        cancel()
        if (release.apkAssets.size <= 1) {
            updateState {
                it.copy(isProbing = false, probes = emptyMap(), probedTag = null, probedRelease = null, variantResolution = null)
            }
            return
        }
        val request = requests.begin(release)
        val state = variantState()
        val installed = readState().installed
        probeJob = scope.launch {
            updateState {
                it.copy(isProbing = true, probes = emptyMap(), probedTag = release.tagName, probedRelease = release, variantResolution = null)
            }
            val resolution = resolveVariant.resolve(
                release = release,
                state = state,
                device = device,
                expectedPackage = expectedPackage(installed?.packageName, repoPackageName),
                forceProbe = forceProbe,
                installed = installed?.let(InstalledVariantHint::of),
            )
            currentCoroutineContext().ensureActive()
            if (!requests.isCurrent(request)) return@launch
            updateState {
                if (!requests.isCurrent(request)) return@updateState it
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
}
