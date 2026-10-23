package io.github.tophtab.lspstore.domain.variant

import kotlinx.serialization.Serializable
import io.github.tophtab.lspstore.domain.model.InstalledModule

/**
 * Semantic memory of the variant a user picked (or installed). Deliberately not
 * the asset file name: names carry the app version and change every release.
 */
@Serializable
data class VariantSignature(
    val abi: String? = null,
    /** "legacy" | "libxposed" */
    val api: String? = null,
    /** "hyperos3" | "oneui8" | "android16" | "miui14" ... */
    val os: String? = null,
    val edition: List<String> = emptyList(),
    val flavor: List<String> = emptyList(),
) {
    /**
     * True when every dimension recorded in the signature also holds for
     * [traits]. Dimensions that were never recorded do not constrain the match,
     * so a partially remembered signature can still follow a renamed release.
     */
    fun matches(traits: VariantTraits): Boolean {
        abi?.let { wanted ->
            if (wanted == UNIVERSAL) {
                if (!traits.universal) return false
            } else if (wanted !in traits.abis) {
                return false
            }
        }
        api?.let { wanted ->
            when (wanted) {
                API_LIBXPOSED -> if (traits.libxposedApi == null && !traits.modernWord) return false
                API_LEGACY -> if (!traits.legacyWord) return false
                else -> return false
            }
        }
        os?.let { wanted -> if (traits.osToken != wanted) return false }
        if (edition.isNotEmpty() && edition.toSet() != traits.edition) return false
        if (flavor.isNotEmpty() && flavor.toSet() != traits.flavors) return false
        return true
    }

    companion object {
        const val UNIVERSAL = "universal"
        const val API_LIBXPOSED = "libxposed"
        const val API_LEGACY = "legacy"

        fun of(traits: VariantTraits, meta: ApkMeta? = null): VariantSignature = VariantSignature(
            abi = traits.abis.minByOrNull(::abiRank) ?: UNIVERSAL.takeIf { traits.universal },
            api = when {
                meta?.minApiVersion != null -> API_LIBXPOSED
                traits.libxposedApi != null || traits.modernWord -> API_LIBXPOSED
                traits.legacyWord -> API_LEGACY
                else -> null
            },
            os = traits.osToken,
            edition = traits.edition.sorted(),
            flavor = traits.flavors.sorted(),
        )

        /** Signature of an already installed module, from PackageManager only (no network). */
        fun of(module: InstalledModule): VariantSignature = VariantSignature(
            abi = module.primaryCpuAbi?.takeIf { it.isNotBlank() },
            api = if (module.hasLibXposedProvider) API_LIBXPOSED else null,
        )

        private fun abiRank(abi: String): Int = when (abi) {
            "arm64-v8a" -> 0
            "armeabi-v7a" -> 1
            "x86_64" -> 2
            "x86" -> 3
            else -> 4
        }
    }
}

/**
 * Metadata probed from a remote APK (P2). P0 never fills it; the resolver and
 * [VariantSignature] already accept it so the Range-probing work is additive.
 */
@Serializable
data class ApkMeta(
    val packageName: String? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val debuggable: Boolean = false,
    /** Concrete `lib/<abi>/` directories. */
    val abis: Set<String> = emptySet(),
    val universal: Boolean = false,
    val hasXposedDeclaration: Boolean = false,
    val minApiVersion: Int? = null,
    /** libxposed scope packages, recorded for future target-app matching (P3). */
    val scope: List<String> = emptyList(),
)
