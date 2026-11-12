package io.github.tophtab.lspstore.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver

data class VariantResolution(
    val decision: VariantDecision,
    val probes: Map<String, ApkMeta> = emptyMap(),
)

/**
 * Applies the lazy-validation rules (variant-selection.md §7.1) around the pure
 * [VariantResolver]:
 *
 *  - remembered choice: follow its stable asset identity; probe only if ambiguous or requested;
 *  - UNKNOWN / SUSPECT: probe the chosen candidate before installing;
 *  - ambiguous name layer: probe the top candidates, then re-resolve;
 *  - legacy NOT_APPLICABLE: retry like UNKNOWN, including probes.
 *
 * A probe failure is silent: the name-layer decision stands.
 */
class ResolveVariantUseCase(
    private val probe: ApkProbe,
    private val maxProbes: Int = 5,
) {
    /**
     * @param installed the copy on the device, when there is one. Updates prefer
     *   the asset that matches it; a first install passes null and follows the
     *   device profile instead.
     */
    suspend fun resolve(
        release: Release,
        state: ModuleVariantState,
        device: DeviceProfile,
        expectedPackage: String? = null,
        forceProbe: Boolean = false,
        installed: InstalledVariantHint? = null,
    ): VariantResolution {
        val resolver = VariantResolver(device)
        val cached = release.apkAssets.mapNotNull { asset -> probe.cached(asset)?.let { asset.name to it } }.toMap()
        val nameDecision = resolver.resolve(
            release,
            signature = state.recommendationSignature,
            probes = cached,
            allowProbes = true,
            expectedPackage = expectedPackage,
            installed = installed,
        )
        if (nameDecision is VariantDecision.NotApplicable) {
            return VariantResolution(nameDecision, cached)
        }

        // A DEFAULT pick is a real default, but the archive is the only thing that
        // can still tell the candidates apart (legacy vs libxposed, a renamed
        // package, a real ABI), so that path probes once before installing.
        val ambiguous = nameDecision is VariantDecision.Pick &&
            nameDecision.confidence == VariantDecision.Confidence.DEFAULT
        // Legacy NOT_APPLICABLE records are retryable, just like UNKNOWN. They
        // must not suppress the probes that could disprove the old verdict.
        val shouldProbe = forceProbe || !state.isTrusted || ambiguous
        if (!shouldProbe) return VariantResolution(nameDecision, cached)

        val targets: List<ReleaseAsset> = when {
            forceProbe -> resolver.ranked(
                resolver.candidates(release, expectedPackage = expectedPackage, installed = installed),
            ).map { it.asset }
            // A default that only a preference ladder decided: probe the ranked
            // candidates so the archive can overrule the name layer.
            ambiguous -> resolver.ranked(
                resolver.candidates(
                    release,
                    expectedPackage = expectedPackage,
                    installed = installed,
                ),
            ).map { it.asset }

            nameDecision is VariantDecision.Pick -> listOf(nameDecision.asset)
            else -> emptyList()
        }.take(maxProbes)
        if (targets.isEmpty()) return VariantResolution(nameDecision, cached)

        val probes = cached + coroutineScope {
            targets.filterNot { it.name in cached }.map { asset ->
                async { probe.fetch(asset)?.let { asset.name to it } }
            }.awaitAll().filterNotNull().toMap()
        }
        if (probes.isEmpty()) return VariantResolution(nameDecision, cached)

        return VariantResolution(
            decision = resolver.resolve(
                release = release,
                signature = state.recommendationSignature,
                probes = probes,
                allowProbes = true,
                expectedPackage = expectedPackage,
                installed = installed,
            ),
            probes = probes,
        )
    }
}
