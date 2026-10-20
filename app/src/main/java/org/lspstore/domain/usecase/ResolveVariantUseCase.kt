package org.lspstore.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.lspstore.domain.model.Release
import org.lspstore.domain.variant.ApkMeta
import org.lspstore.domain.variant.ApkProbe
import org.lspstore.domain.variant.DeviceProfile
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.VariantDecision
import org.lspstore.domain.variant.VariantResolver

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
 *  - NOT_APPLICABLE: only re-check the name layer (unless the user explicitly
 *    opened the variant picker).
 *
 * A probe failure is silent: the name-layer decision stands.
 */
class ResolveVariantUseCase(
    private val probe: ApkProbe,
    private val maxProbes: Int = 5,
) {
    suspend fun resolve(
        release: Release,
        state: ModuleVariantState,
        device: DeviceProfile,
        expectedPackage: String? = null,
        forceProbe: Boolean = false,
    ): VariantResolution {
        val resolver = VariantResolver(device)
        if (state.isTrusted) {
            return VariantResolution(
                decision = resolver.resolve(
                    release = release,
                    signature = state.signature,
                    allowProbes = false,
                    expectedPackage = expectedPackage,
                ),
            )
        }

        val nameDecision = resolver.resolve(release, expectedPackage = expectedPackage)
        if (nameDecision is VariantDecision.NotApplicable) {
            return VariantResolution(nameDecision)
        }

        // NOT_APPLICABLE is re-checked on the name layer only; an explicit user
        // action (opening the picker) may still probe.
        val shouldProbe = forceProbe || (
            state.status != ModuleVariantState.Status.NOT_APPLICABLE && (
                state.status == ModuleVariantState.Status.UNKNOWN ||
                    state.status == ModuleVariantState.Status.SUSPECT ||
                    nameDecision is VariantDecision.Choose
                )
            )
        if (!shouldProbe) return VariantResolution(nameDecision)

        val targets = when (nameDecision) {
            is VariantDecision.Choose -> nameDecision.candidates
                .filter { it.blocked == null }
                .take(maxProbes)
                .map { it.asset }

            is VariantDecision.Pick -> listOf(nameDecision.asset)
            is VariantDecision.NotApplicable -> emptyList()
        }
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
            ),
            probes = probes,
        )
    }
}
