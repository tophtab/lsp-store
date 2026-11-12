package io.github.tophtab.lspstore.domain.variant

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ConfirmedInstallRecord
import kotlinx.serialization.Serializable

/**
 * Installation history and explicit variant preferences for one repository
 * module. Probe selection uses this evidence rather than the status alone.
 */
@Serializable
data class ModuleVariantState(
    val status: Status = Status.UNKNOWN,
    val signature: VariantSignature? = null,
    val failures: Int = 0,
    /** Storage key of a [Reason]; the UI turns it back into a localized string. */
    val reason: String? = null,
    val updatedAt: Long = 0L,
    val explicitPreference: VariantSignature? = null,
    val confirmedInstall: ConfirmedInstallRecord? = null,
    val preferenceMigrated: Boolean = false,
) {
    enum class Status { UNKNOWN, TRUSTED, SUSPECT, NOT_APPLICABLE }

    /** Old asset memories remain preferences; PackageManager seeds remain evidence. */
    fun migrated(): ModuleVariantState = if (preferenceMigrated) this else copy(
        explicitPreference = explicitPreference ?: signature?.takeIf { it.assetFamily != null },
        signature = if (signature?.assetFamily != null) signature.packageName?.let { VariantSignature(packageName = it) } else signature,
        preferenceMigrated = true,
    )

    fun forInstalled(installed: InstalledModule?): ModuleVariantState {
        val current = migrated()
        val validated = if (current.confirmedInstall?.matches(installed) == false) current.copy(confirmedInstall = null, signature = null) else current
        return if (validated.confirmedInstall == null && validated.signature?.assetFamily == null) {
            validated.copy(signature = installed?.let(VariantSignature::of))
        } else validated
    }

    val recommendationSignature: VariantSignature?
        get() = migrated().let { it.explicitPreference ?: it.confirmedInstall?.target?.signature ?: it.signature }

    val activePackage: String?
        get() = confirmedInstall?.packageName ?: signature?.packageName

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

/** Install finished: retain the semantic signature and clear the failure counter. */
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
 * Install failed: retain the preferred type and count the failure for diagnostics.
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
    explicitPreference = signature,
    preferenceMigrated = true,
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
    mapNotNull { (packageName, state) ->
        when {
            !state.isNotApplicable -> packageName to state
            state.migrated().explicitPreference != null -> packageName to state.migrated().copy(
                status = ModuleVariantState.Status.SUSPECT,
                reason = null,
            )
            else -> null
        }
    }.toMap()
