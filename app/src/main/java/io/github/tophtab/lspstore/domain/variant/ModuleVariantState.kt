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
    signature = signature.copy(packageName = signature.packageName ?: this.signature?.packageName),
    failures = 0,
    reason = null,
    updatedAt = now,
)

/** No candidate on this attempt: keep it retryable without hiding updates. */
@Suppress("UNUSED_PARAMETER") // Reason is displayed by callers, never persisted as a gate.
fun ModuleVariantState.onEvaluatedNoCandidate(
    reason: Reason,
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = onInstallFailed(now)

/**
 * Drops legacy NOT_APPLICABLE verdicts of every kind. A device change, an APK
 * added to the same tag or a failed probe can invalidate any of them.
 */
fun Map<String, ModuleVariantState>.withoutNotApplicableVerdicts(): Map<String, ModuleVariantState> =
    filterValues { !it.isNotApplicable }
