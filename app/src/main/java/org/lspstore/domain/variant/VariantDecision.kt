package org.lspstore.domain.variant

import org.lspstore.domain.model.ReleaseAsset

/**
 * Why a variant was picked, blocked or flagged. Only a stable code and its
 * formatted arguments are stored; the UI resolves the code to a string resource
 * so the persisted state never contains user-visible text.
 */
data class Reason(
    val kind: Kind,
    val args: List<String> = emptyList(),
) {
    enum class Kind {
        MATCHES_DEVICE,
        REMEMBERED,
        NEEDS_CONFIRM,
        BUNDLED_APP,
        DEBUG_BUILD,
        MIN_SDK_TOO_HIGH,
        ABI_INCOMPATIBLE,
        API_TOO_NEW,
        PACKAGE_MISMATCH,
        NOT_A_MODULE,
        NO_APK,
    }

    fun storageKey(): String = (listOf(kind.name) + args).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001f"

        fun fromStorage(raw: String?): Reason? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEPARATOR)
            val kind = Kind.entries.firstOrNull { it.name == parts.first() } ?: return null
            return Reason(kind, parts.drop(1))
        }
    }
}

data class ScoredAsset(
    val asset: ReleaseAsset,
    val traits: VariantTraits,
    val score: Int,
    val recommended: Boolean,
    /** Non-null when the candidate must not be installed directly (folded in the UI). */
    val blocked: Reason?,
    /** Probe-confirmed debug flag, falling back to the file name. */
    val debug: Boolean = traits.debugName,
    /** P2 probe result, null while only the name layer is known. */
    val meta: ApkMeta? = null,
)

sealed interface VariantDecision {
    data class Pick(
        val asset: ReleaseAsset,
        val reasons: List<Reason>,
        val confidence: Confidence,
    ) : VariantDecision

    data class Choose(
        val candidates: List<ScoredAsset>,
        val hint: Reason? = Reason(Reason.Kind.NEEDS_CONFIRM),
    ) : VariantDecision

    data class NotApplicable(val reason: Reason) : VariantDecision

    enum class Confidence { HIGH, NEEDS_CONFIRM }

    /**
     * Asset a list should start with; null when nothing should be preselected.
     *
     * `Choose` only preselects when the name layer produced a *recommended*
     * candidate. Falling back to the first eligible asset is what made the
     * detail page silently show `OS2_...` for HyperCeiler.
     */
    fun defaultAsset(): ReleaseAsset? = when (this) {
        is Pick -> asset
        is Choose -> candidates.firstOrNull { it.blocked == null && it.recommended }?.asset
        is NotApplicable -> null
    }
}
