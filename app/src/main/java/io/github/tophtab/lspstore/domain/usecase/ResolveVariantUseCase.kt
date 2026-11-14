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
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver

data class VariantResolution(
    val decision: VariantDecision,
    val probes: Map<String, ApkMeta> = emptyMap(),
    val candidates: List<ScoredAsset> = emptyList(),
)

/**
 * Supplements [VariantResolver] when several APKs need comparing:
 *
 *  - a single APK is used directly, without reading the probe cache;
 *  - a uniquely remembered asset family is reused directly;
 *  - cached metadata, file names and installation history resolve known choices;
 *  - unresolved defaults and installed-package identity may need remote metadata;
 *  - opening a multi-APK picker can request metadata for reference.
 *
 * A probe failure is silent: the name-layer decision stands.
 */
class ResolveVariantUseCase(
    private val probe: ApkProbe,
    private val maxProbes: Int = 5,
) {
    /** Immediate name-layer result for a page or picker, without probe-cache I/O. */
    fun initial(
        release: Release,
        state: ModuleVariantState,
        device: DeviceProfile,
        expectedPackage: String? = null,
        installed: InstalledVariantHint? = null,
        probes: Map<String, ApkMeta> = emptyMap(),
    ): VariantResolution = resolution(VariantResolver(device), release, state, expectedPackage, installed, probes)

    private fun resolution(
        resolver: VariantResolver,
        release: Release,
        state: ModuleVariantState,
        expectedPackage: String?,
        installed: InstalledVariantHint?,
        probes: Map<String, ApkMeta> = emptyMap(),
    ): VariantResolution {
        val candidates = resolver.candidates(release, probes, expectedPackage = expectedPackage, installed = installed)
        val decision = resolver.resolveCandidates(candidates, state.recommendationSignature)
        val recommended = (decision as? VariantDecision.Pick)?.asset?.name
        return VariantResolution(
            decision = decision,
            probes = probes,
            candidates = resolver.ranked(candidates).map {
                it.copy(recommended = candidates.size > 1 && it.asset.name == recommended)
            }.sortedByDescending { it.recommended },
        )
    }

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
        val initial = resolution(resolver, release, state, expectedPackage, installed)
        // There is no choice to refine. This also applies to an explicit picker
        // request and avoids any probe-cache I/O on the single-APK path.
        if (initial.candidates.size <= 1) return initial

        // A recorded asset family identifies the user's choice or a confirmed
        // installation across release versions, even after a failed attempt.
        if (!forceProbe && state.recommendationSignature?.assetFamily != null &&
            initial.decision.isRememberedChoice()
        ) {
            return initial
        }

        val cached = release.apkAssets.mapNotNull { asset -> probe.cached(asset)?.let { asset.name to it } }.toMap()
        val named = if (cached.isEmpty()) initial else resolution(resolver, release, state, expectedPackage, installed, cached)
        val nameDecision = named.decision
        if (nameDecision is VariantDecision.NotApplicable) {
            return named
        }

        val ambiguous = nameDecision is VariantDecision.Pick &&
            nameDecision.confidence == VariantDecision.Confidence.DEFAULT
        // A device-only recommendation may still be a different application.
        // Updates follow the installed copy when no unique remembered choice or
        // cached package identity already establishes that link.
        val needsInstalledIdentity = installed != null &&
            !nameDecision.isRememberedChoice() &&
            (nameDecision as? VariantDecision.Pick)?.asset?.let {
                cached[it.name]?.packageName != installed.packageName
            } == true
        val shouldProbe = forceProbe || ambiguous || needsInstalledIdentity
        if (!shouldProbe) return named

        val targets: List<ReleaseAsset> = resolver.ranked(named.candidates).take(maxProbes).map { it.asset }
        if (targets.isEmpty()) return named

        val probes = cached + coroutineScope {
            targets.filterNot { it.name in cached }.map { asset ->
                async { probe.fetch(asset)?.let { asset.name to it } }
            }.awaitAll().filterNotNull().toMap()
        }
        if (probes == cached) return named
        return resolution(resolver, release, state, expectedPackage, installed, probes)
    }
}

private fun VariantDecision.isRememberedChoice(): Boolean =
    this is VariantDecision.Pick && confidence == VariantDecision.Confidence.HIGH &&
        reasons.any { it.kind == Reason.Kind.REMEMBERED }
