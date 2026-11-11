package io.github.tophtab.lspstore.domain.variant

import io.github.tophtab.lspstore.domain.model.ReleaseAsset

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
    /**
     * Non-null when the APK asks for a newer libxposed API than the detected
     * framework. Advisory only: the loaders do not refuse on it, so the
     * candidate stays installable.
     */
    val warning: Reason? = null,
    /** Probe-confirmed debug flag, falling back to the file name. */
    val debug: Boolean = traits.debugName,
    /** P2 probe result, null while only the name layer is known. */
    val meta: ApkMeta? = null,
    /**
     * How much this candidate looks like the copy already installed on the
     * device (package, ABI, libxposed generation, API levels). 0 when the module
     * is not installed, which is what makes a first install follow the device
     * while an update follows what the user already runs.
     */
    val installedFit: Int = 0,
)

sealed interface VariantDecision {
    /**
     * One asset to install. [confidence] says *why* it was picked, not whether it
     * may be used: every install path (detail page, single update, update all,
     * background worker) installs a [Pick] without asking, and the resource
     * picker stays the place to change it afterwards.
     */
    data class Pick(
        val asset: ReleaseAsset,
        val reasons: List<Reason>,
        val confidence: Confidence,
    ) : VariantDecision

    data class NotApplicable(val reason: Reason) : VariantDecision

    enum class Confidence {
        /** A device signal (ABI, API, OS version, minSdk, debug split) decided. */
        HIGH,

        /**
         * The release only differs in author-defined flavours/editions (or not at
         * all): the most installed eligible build is the default. It is still a
         * real pick - update all installs it - but the archive is worth probing
         * once, and the picker shows the alternative builds one tap away.
         */
        DEFAULT,
    }

    /** Asset the page should preselect; null only when nothing can be installed. */
    fun defaultAsset(): ReleaseAsset? = when (this) {
        is Pick -> asset
        is NotApplicable -> null
    }
}
