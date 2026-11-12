package io.github.tophtab.lspstore.domain.variant

import kotlinx.serialization.Serializable
import io.github.tophtab.lspstore.domain.model.InstalledModule

/**
 * Memory of a chosen variant: semantic traits and an asset family that survives
 * release version changes. The confirmed package is used for update bookkeeping.
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
    /** Asset name without its release version; distinguishes arbitrary author flavours. */
    val assetFamily: String? = null,
    /** Package confirmed installed from this repository entry. */
    val packageName: String? = null,
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

    fun matches(assetName: String, traits: VariantTraits): Boolean =
        matches(traits) && (assetFamily == null || assetFamily == familyOf(assetName))

    companion object {
        const val UNIVERSAL = "universal"
        const val API_LIBXPOSED = "libxposed"
        const val API_LEGACY = "legacy"

        fun ofAsset(assetName: String, packageName: String? = null): VariantSignature =
            of(parseTraits(assetName)).copy(assetFamily = familyOf(assetName), packageName = packageName)

        fun familyOf(assetName: String): String? = assetName
            .substringAfterLast('/').substringAfterLast('\\')
            .lowercase().removeSuffix(".apk")
            .replace(RELEASE_NUMBER) { it.groupValues[1] }
            .replace(SEPARATORS, "-")
            .trim('-')
            .takeIf { it.isNotBlank() }

        private val RELEASE_NUMBER = Regex(
            "(?<![a-z0-9])(?<!api[-_])(?<!x86[-_])(?<!android[-_])(?<!minsdk[-_])" +
                "v?\\d+(?:[._-]\\d+)*(?:[-_.]?(alpha|beta|rc)\\d*)?(?![a-z0-9])",
        )
        private val SEPARATORS = Regex("[\\s_.-]+")

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
    /** `module.prop targetApiVersion`: what the loaders actually select on. */
    val targetApiVersion: Int? = null,
    /** `assets/xposed_init` present: the framework can load this as a legacy module. */
    val hasLegacyInit: Boolean = false,
    /** libxposed scope packages, recorded for future target-app matching (P3). */
    val scope: List<String> = emptyList(),
)
