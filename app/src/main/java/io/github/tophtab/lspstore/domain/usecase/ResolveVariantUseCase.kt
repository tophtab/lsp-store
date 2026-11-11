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
 * Applies the lazy-validation rules (variant-selection.md 7.1) around the pure
 * [VariantResolver]:
 *
 *  - TRUSTED: map the remembered signature, zero probes;
 *  - UNKNOWN / SUSPECT: probe the chosen candidate before installing;
 *  - ambiguous name layer: probe the top candidates, then re-resolve;
 *  - a name that looks like a companion app: probe it once instead of reporting
 *    "not applicable", because the archive can show it is the module itself.
 *
 * Nothing here decides whether the module is usable on this device - it only
 * answers "which file" for the call that asked, and the answer is recomputed
 * from the release and the device every time.
 *
 * A probe failure is silent: the name-layer decision stands, and the caller
 * reports it for this attempt only.
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
        val nameDecision = resolver.resolve(release, expectedPackage = expectedPackage)
        // "The release ships no APK" is a fact about the release; every other
        // rejection the name layer can reach on its own is a guess that the
        // archive can overturn (#71, #73).
        val bundledOnly = nameDecision is VariantDecision.NotApplicable &&
            nameDecision.reason.kind == Reason.Kind.BUNDLED_APP

        if (nameDecision is VariantDecision.NotApplicable && !bundledOnly) {
            return VariantResolution(nameDecision)
        }

        // A remembered variant is the one memory that skips probing. A name-only
        // block is the one thing it must not inherit: the memory already proved
        // the module installs here, so the archive decides instead.
        if (state.isTrusted && !forceProbe && !bundledOnly) {
            return VariantResolution(
                decision = resolver.resolve(
                    release = release,
                    signature = state.signature,
                    allowProbes = false,
                    expectedPackage = expectedPackage,
                ),
            )
        }

        val targets: List<ReleaseAsset> = when {
            bundledOnly -> resolver.candidates(release, expectedPackage = expectedPackage)
                .filter { it.blocked?.kind == Reason.Kind.BUNDLED_APP }
                .map { it.asset }

            nameDecision is VariantDecision.Choose -> nameDecision.candidates
                .filter { it.blocked == null }
                .map { it.asset }

            nameDecision is VariantDecision.Pick -> listOf(nameDecision.asset)

            else -> emptyList()
        }.take(maxProbes)
        if (targets.isEmpty()) {
            return VariantResolution(
                decision = if (state.isTrusted) {
                    resolver.resolve(
                        release = release,
                        signature = state.signature,
                        allowProbes = false,
                        expectedPackage = expectedPackage,
                    )
                } else {
                    nameDecision
                },
            )
        }

        val probes = coroutineScope {
            targets.map { asset ->
                async { probe.fetch(asset)?.let { asset.name to it } }
            }.awaitAll().filterNotNull().toMap()
        }
        if (probes.isEmpty()) return VariantResolution(nameDecision)

        return VariantResolution(
            decision = resolver.resolve(
                release = release,
                signature = if (state.isTrusted) state.signature else null,
                probes = probes,
                // The probes are the point of this pass; the signature only picks
                // among what the archive left eligible.
                allowProbes = true,
                expectedPackage = expectedPackage,
            ),
            probes = probes,
        )
    }
}
