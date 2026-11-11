package io.github.tophtab.lspstore.domain.variant

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What the store remembers about one repository module.
 *
 * Two kinds of memory only, never a judgement about the device:
 *
 *  - [Status.TRUSTED]: a variant installed successfully or the user picked it,
 *    so the next update maps [signature] and never probes;
 *  - [Status.SUSPECT]: the last attempt failed, so the next one re-probes and
 *    re-ranks. The counter is diagnostics, not a verdict.
 *
 * "This module cannot run here" is never stored. It is derived from the release
 * and the device every time it is needed (see `ResolveVariantUseCase`), so one
 * wrong answer stays one wrong answer instead of a module that disappears from
 * the update list until its author ships again (#65, #69, #73).
 *
 * A stored verdict from an older build decodes as [Status.UNKNOWN] (see
 * [StatusSerializer]), which costs one probe and nothing else.
 */
@Serializable
data class ModuleVariantState(
    @Serializable(with = StatusSerializer::class)
    val status: Status = Status.UNKNOWN,
    val signature: VariantSignature? = null,
    /** Consecutive failed attempts; diagnostics only, it never hides anything. */
    val failures: Int = 0,
    /** When this memory was written. */
    val updatedAt: Long = 0L,
) {
    enum class Status { UNKNOWN, TRUSTED, SUSPECT }

    val isTrusted: Boolean
        get() = status == Status.TRUSTED

    companion object {
        /**
         * Upper bound of the failure counter. It never changes the verdict, so a
         * module that keeps failing stays visible instead of disappearing.
         */
        const val MAX_FAILURES = 3
    }
}

/**
 * Tolerates a status this build does not know.
 *
 * Builds before #73 could store `NOT_APPLICABLE`, which is not part of the model
 * any more. It decodes as [ModuleVariantState.Status.UNKNOWN] wherever it is
 * read - the library's DataStore and an imported backup alike - so an old entry
 * costs one probe and never a module that stays hidden. Doing it here instead of
 * in one Json config keeps every decoder honest.
 */
object StatusSerializer : KSerializer<ModuleVariantState.Status> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ModuleVariantState.Status", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ModuleVariantState.Status) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ModuleVariantState.Status {
        val raw = decoder.decodeString()
        return ModuleVariantState.Status.entries.firstOrNull { it.name == raw }
            ?: ModuleVariantState.Status.UNKNOWN
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
    updatedAt = now,
)

/**
 * Install failed: probe again next time and re-rank the candidates.
 *
 * This is deliberately not a verdict about the module. The installer, root, a
 * stale mirror or a variant that did not fit can all fail once, and a stored
 * "not applicable" outlives the cause: three such failures used to hide the
 * module from updates, notifications and batch installs exactly like the API
 * -number block did (#65, #69).
 */
fun ModuleVariantState.onInstallFailed(
    now: Long = System.currentTimeMillis(),
): ModuleVariantState = copy(
    status = ModuleVariantState.Status.SUSPECT,
    failures = (failures + 1).coerceAtMost(ModuleVariantState.MAX_FAILURES),
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
    updatedAt = now,
)
