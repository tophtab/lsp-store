package org.lspstore.domain.variant

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

/** Install failed: probe again next time, and give up after [ModuleVariantState.MAX_FAILURES]. */
fun ModuleVariantState.onInstallFailed(
    now: Long = System.currentTimeMillis(),
): ModuleVariantState {
    val failures = failures + 1
    return if (failures >= ModuleVariantState.MAX_FAILURES) {
        copy(
            status = ModuleVariantState.Status.NOT_APPLICABLE,
            failures = failures,
            reason = null,
            updatedAt = now,
        )
    } else {
        copy(
            status = ModuleVariantState.Status.SUSPECT,
            failures = failures,
            updatedAt = now,
        )
    }
}

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
