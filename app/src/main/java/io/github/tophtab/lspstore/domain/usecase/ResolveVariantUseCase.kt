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
 *  - TRUSTED: map the signature only, zero probes;
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
        if (state.isTrusted) {
            return VariantResolution(
                decision = resolver.resolve(
                    release = release,
                    signature = state.signature,
                    allowProbes = false,
                    expectedPackage = expectedPackage,
                    installed = installed,
                ),
            )
        }

        val nameDecision = resolver.resolve(
            release,
            expectedPackage = expectedPackage,
            installed = installed,
        )
        if (nameDecision is VariantDecision.NotApplicable) {
            return VariantResolution(nameDecision)
        }

        // A DEFAULT pick is a real default, but the archive is the only thing that
        // can still tell the candidates apart (legacy vs libxposed, a renamed
        // package, a real ABI), so that path probes once before installing.
        val ambiguous = nameDecision is VariantDecision.Pick &&
            nameDecision.confidence == VariantDecision.Confidence.DEFAULT
        // Legacy NOT_APPLICABLE records are retryable, just like UNKNOWN. They
        // must not suppress the probes that could disprove the old verdict.
        val shouldProbe = forceProbe || !state.isTrusted || ambiguous
        if (!shouldProbe) return VariantResolution(nameDecision)

        val targets: List<ReleaseAsset> = when {
            // A default that only a preference ladder decided: probe the ranked
            // candidates so the archive can overrule the name layer.
            ambiguous -> resolver.ranked(
                resolver.candidates(
                    release,
                    expectedPackage = expectedPackage,
                    installed = installed,
                ),
            ).filter { it.blocked == null }.map { it.asset }

            nameDecision is VariantDecision.Pick -> listOf(nameDecision.asset)
            else -> emptyList()
        }.take(maxProbes)
        if (targets.isEmpty()) return VariantResolution(nameDecision)

        val probes = coroutineScope {
            targets.map { asset ->
                async { probe.fetch(asset)?.let { asset.name to it } }
            }.awaitAll().filterNotNull().toMap()
        }
        if (probes.isEmpty()) return VariantResolution(nameDecision)

        return VariantResolution(
            decision = resolver.resolve(
                release = release,
                probes = probes,
                expectedPackage = expectedPackage,
                installed = installed,
            ),
            probes = probes,
        )
    }
}
