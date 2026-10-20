package org.lspstore.ui.variant

import android.content.Context
import org.lspstore.R
import org.lspstore.domain.variant.ApkMeta
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.Reason
import org.lspstore.domain.variant.VariantTraits
import org.lspstore.domain.variant.VendorOs
import org.lspstore.domain.variant.androidVersionFromSdk

/** Turns a resolver [Reason] back into a localized, one-line explanation. */
fun Context.variantReasonText(reason: Reason): String = when (reason.kind) {
    Reason.Kind.MATCHES_DEVICE -> getString(
        R.string.variant_reason_matches_device,
        reason.args.firstOrNull().orEmpty(),
    )

    Reason.Kind.REMEMBERED -> getString(R.string.variant_remembered)
    Reason.Kind.NEEDS_CONFIRM -> getString(R.string.variant_needs_confirm)
    Reason.Kind.BUNDLED_APP -> getString(R.string.variant_bundled_app)
    Reason.Kind.DEBUG_BUILD -> getString(R.string.variant_debug_badge)

    Reason.Kind.MIN_SDK_TOO_HIGH -> {
        val required = reason.args.getOrNull(0)?.toIntOrNull() ?: 0
        val deviceSdk = reason.args.getOrNull(1)?.toIntOrNull() ?: 0
        val deviceVersion = androidVersionFromSdk(deviceSdk) ?: deviceSdk
        getString(R.string.variant_incompatible_min_sdk, required, deviceVersion)
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

/** Explanation stored on a NOT_APPLICABLE state, if there is one. */
fun Context.variantStateReasonText(state: ModuleVariantState): String? =
    Reason.fromStorage(state.reason)?.let(::variantReasonText)

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
    (meta?.minSdk ?: traits.minSdkToken)?.let { tags += "minSdk $it" }
    val api = meta?.minApiVersion ?: traits.libxposedApi
    when {
        api != null -> tags += "API $api"
        traits.modernWord -> tags += "modern"
        traits.legacyWord -> tags += "legacy"
    }
    traits.android?.let { tags += "Android $it" }
    traits.vendor?.let { (family, major) -> tags += VendorOs(family, major).label() }
    tags += traits.edition.sorted()
    tags += traits.flavors.sorted()
    return tags.distinct()
}
