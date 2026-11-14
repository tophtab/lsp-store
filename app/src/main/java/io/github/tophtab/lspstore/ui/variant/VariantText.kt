package io.github.tophtab.lspstore.ui.variant

import android.content.Context
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantTraits
import io.github.tophtab.lspstore.domain.variant.VendorOs
import io.github.tophtab.lspstore.domain.variant.androidVersionFromSdk
import io.github.tophtab.lspstore.domain.variant.libXposedApiRequirement

internal data class VariantCandidateText(val tags: String?, val recommendation: String?)

/** Both pickers format tags and recommendation captions from the same evidence. */
internal fun Context.variantCandidateText(candidate: ScoredAsset, resolver: VariantResolver): VariantCandidateText {
    val labels = if (candidate.recommended) resolver.matchLabels(candidate.traits, candidate.meta) else emptyList()
    return VariantCandidateText(
        tags = variantTags(candidate.traits, candidate.meta).takeIf { it.isNotEmpty() }?.joinToString(" · "),
        recommendation = labels.takeIf { it.isNotEmpty() }?.let {
            variantReasonText(Reason(Reason.Kind.MATCHES_DEVICE, listOf(it.joinToString(", "))))
        },
    )
}

/** Turns a resolver [Reason] back into a localized, one-line explanation. */
fun Context.variantReasonText(reason: Reason): String = when (reason.kind) {
    Reason.Kind.MATCHES_DEVICE -> getString(
        R.string.variant_reason_matches_device,
        reason.args.firstOrNull().orEmpty(),
    )

    Reason.Kind.REMEMBERED -> getString(R.string.variant_remembered)
    Reason.Kind.BUNDLED_APP -> getString(R.string.variant_bundled_app)
    // Retained for old persisted reasons; debug builds need no reminder.
    Reason.Kind.DEBUG_BUILD -> ""

    Reason.Kind.MIN_SDK_TOO_HIGH -> {
        val requiredSdk = reason.args.getOrNull(0)?.toIntOrNull() ?: 0
        val deviceSdk = reason.args.getOrNull(1)?.toIntOrNull() ?: 0
        // Both sides are API levels; the string talks about Android versions, so
        // both are converted (35 -> "Android 15") to keep the units consistent.
        getString(
            R.string.variant_incompatible_min_sdk,
            androidVersionFromSdk(requiredSdk) ?: requiredSdk,
            androidVersionFromSdk(deviceSdk) ?: deviceSdk,
        )
    }

    Reason.Kind.ABI_INCOMPATIBLE -> getString(
        R.string.variant_incompatible_abi,
        reason.args.firstOrNull().orEmpty(),
    )

    Reason.Kind.API_TOO_NEW -> getString(
        R.string.variant_incompatible_api,
        reason.args.firstOrNull()?.toIntOrNull() ?: 0,
    )

    Reason.Kind.PACKAGE_MISMATCH -> getString(
        R.string.verifier_package_mismatch,
        reason.args.getOrNull(0).orEmpty(),
        reason.args.getOrNull(1).orEmpty(),
    )

    Reason.Kind.NOT_A_MODULE -> getString(R.string.variant_not_module)
    Reason.Kind.NO_APK -> getString(R.string.detail_no_apk)
}

/**
 * Compact labels for a picker row: real probe values win over file-name tokens.
 * Pure so both Compose and logging can use it.
 */
fun variantTags(traits: VariantTraits, meta: ApkMeta? = null): List<String> {
    val tags = mutableListOf<String>()
    val abis = meta?.abis?.takeIf { it.isNotEmpty() } ?: traits.abis
    when {
        abis.isNotEmpty() -> tags += abis.sorted().joinToString(", ")
        meta?.universal == true || traits.universal -> tags += "universal"
    }
    // Row order is fixed: ABI, API generation, minSdk, then OS/edition/flavour.
    val api = if (meta != null) {
        libXposedApiRequirement(meta.targetApiVersion, meta.hasLegacyInit, meta.minApiVersion)
    } else {
        traits.libxposedApi
    }
    when {
        api != null -> tags += "API $api"
        traits.modernWord -> tags += "modern"
        traits.legacyWord -> tags += "legacy"
    }
    (meta?.minSdk ?: traits.minSdkToken)?.let { tags += "minSdk $it" }
    traits.android?.let { tags += "Android $it" }
    traits.vendor?.let { (family, major) -> tags += VendorOs(family, major).label() }
    tags += traits.edition.sorted()
    tags += traits.flavors.sorted()
    return tags.distinct()
}
