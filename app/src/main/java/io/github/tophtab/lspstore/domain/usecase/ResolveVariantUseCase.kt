package io.github.tophtab.lspstore.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
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
 *  - NOT_APPLICABLE: only re-check the name layer (unless the user explicitly
 *    opened the variant picker), except for a companion-app looking name, which
 *    is probed once because the archive can show it is the module itself.
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
        // A name-layer "nothing fits" is final only when the release has no APK.
        // The other reason a file name can produce on its own - looking like a
        // companion app - is a guess, so it is probed once below.
        val bundledOnly = nameDecision is VariantDecision.NotApplicable &&
            nameDecision.reason.kind == Reason.Kind.BUNDLED_APP
        if (nameDecision is VariantDecision.NotApplicable && !bundledOnly) {
            return VariantResolution(nameDecision)
        }

        // NOT_APPLICABLE is re-checked on the name layer only; an explicit user
        // action (opening the picker) may still probe.
        val shouldProbe = forceProbe || bundledOnly || (
            state.status != ModuleVariantState.Status.NOT_APPLICABLE && (
                state.status == ModuleVariantState.Status.UNKNOWN ||
                    state.status == ModuleVariantState.Status.SUSPECT ||
                    nameDecision is VariantDecision.Choose
                )
            )
        if (!shouldProbe) return VariantResolution(nameDecision)

        val targets: List<ReleaseAsset> = when {
            // A name that looks like a companion app is the one name-layer verdict
            // a probe can overturn, so the blocked candidates are read here.
            bundledOnly -> resolver.candidates(release, expectedPackage = expectedPackage)
                .filter { it.blocked?.kind == Reason.Kind.BUNDLED_APP }
                .map { it.asset }

            nameDecision is VariantDecision.Choose -> nameDecision.candidates
                .filter { it.blocked == null }
                .map { it.asset }

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
            ),
            probes = probes,
        )
    }
}
