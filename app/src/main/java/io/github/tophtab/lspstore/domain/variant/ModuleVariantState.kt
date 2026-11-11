package io.github.tophtab.lspstore.domain.variant

import kotlinx.serialization.Serializable

/**
 * Lazy validation state for one repository module: which asset finally worked on
 * this device and whether it should be re-probed.
 */
@Serializable
data class ModuleVariantState(
    val status: Status = Status.UNKNOWN,
    val signature: VariantSignature? = null,
    val failures: Int = 0,
    /** Storage key of a [Reason]; the UI turns it back into a localized string. */
    val reason: String? = null,
    val updatedAt: Long = 0L,
) {
    enum class Status { UNKNOWN, TRUSTED, SUSPECT, NOT_APPLICABLE }

    val isTrusted: Boolean
        get() = status == Status.TRUSTED

    val isNotApplicable: Boolean
        get() = status == Status.NOT_APPLICABLE

    companion object {
        /**
         * Upper bound of the failure counter. It never changes the verdict, so a
         * module that keeps failing stays visible instead of disappearing.
         */
        const val MAX_FAILURES = 3
    }
}

/** Install finished: remember the semantic signature and stop probing. */
fun ModuleVariantState.onInstallSucceeded(
    signature: VariantSignature?,
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = copy(
    status = ModuleVariantState.Status.TRUSTED,
    signature = signature ?: this.signature,
    failures = 0,
    reason = null,
    updatedAt = now,
)

/**
 * Install failed: probe again next time and re-rank the candidates.
 *
 * This is deliberately not a verdict about the module. The installer, root, a
 * stale mirror or a variant that did not fit can all fail once, and a stored
 * "not applicable" outlives the cause: three such failures used to hide the
 * module from updates, notifications and batch installs exactly like the API
 * -number block did (#65, #69). The counter stays bounded for diagnostics only.
 */
fun ModuleVariantState.onInstallFailed(
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = copy(
    status = ModuleVariantState.Status.SUSPECT,
    failures = (failures + 1).coerceAtMost(ModuleVariantState.MAX_FAILURES),
    reason = null,
    updatedAt = now,
)

/** The user explicitly chose a variant, so trust it immediately. */
fun ModuleVariantState.onUserPicked(
    signature: VariantSignature,
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = copy(
    status = ModuleVariantState.Status.TRUSTED,
    signature = signature,
    failures = 0,
    reason = null,
    updatedAt = now,
)

/** The resolver found no installable candidate for this device. */
fun ModuleVariantState.onEvaluatedNoCandidate(
    reason: Reason,
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = copy(
    status = ModuleVariantState.Status.NOT_APPLICABLE,
    failures = 0,
    reason = reason.storageKey(),
    updatedAt = now,
)

/**
 * Drops the NOT_APPLICABLE verdicts that the pre-1.2.0 API-number block wrote.
 *
 * A stored [ModuleVariantState.reason] of [Reason.Kind.API_TOO_NEW] can only come
 * from a build that refused installs over `minApiVersion`. Since #47 that number
 * is advisory, so the verdict must not keep the module out of updates or disable
 * its install button any more (#65, #69). Current builds never store it.
 */
fun Map<String, ModuleVariantState>.withoutAdvisoryApiVerdicts(): Map<String, ModuleVariantState> =
    filterValues { state ->
        !state.isNotApplicable ||
            Reason.fromStorage(state.reason)?.kind != Reason.Kind.API_TOO_NEW
    }
