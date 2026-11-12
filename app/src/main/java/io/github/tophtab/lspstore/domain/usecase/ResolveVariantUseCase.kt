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
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver

data class VariantResolution(
    val decision: VariantDecision,
    val probes: Map<String, ApkMeta> = emptyMap(),
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
        val initialDecision = resolver.resolveForUpdate(
            state, release, expectedPackage = expectedPackage, installed = installed,
        )
        // There is no choice to refine. This also applies to an explicit picker
        // request and avoids any probe-cache I/O on the single-APK path.
        if (release.apkAssets.size <= 1) return VariantResolution(initialDecision)

        // A recorded asset family identifies the user's choice or a confirmed
        // installation across release versions, even after a failed attempt.
        if (!forceProbe && state.recommendationSignature?.assetFamily != null &&
            initialDecision.isRememberedChoice()
        ) {
            return VariantResolution(initialDecision)
        }

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
        if (!shouldProbe) return VariantResolution(nameDecision, cached)

        val targets: List<ReleaseAsset> = resolver.ranked(
            resolver.candidates(
                release,
                probes = cached,
                expectedPackage = expectedPackage,
                installed = installed,
            ),
        ).take(maxProbes).map { it.asset }
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

private fun VariantDecision.isRememberedChoice(): Boolean =
    this is VariantDecision.Pick && confidence == VariantDecision.Confidence.HIGH &&
        reasons.any { it.kind == Reason.Kind.REMEMBERED }
