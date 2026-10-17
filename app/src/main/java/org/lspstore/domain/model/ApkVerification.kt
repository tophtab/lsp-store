package org.lspstore.domain.model

data class SignerInfo(
    val sha256: String,
    val subject: String?,
    val issuer: String?,
)

sealed interface ApkVerification {
    val signers: List<SignerInfo>

    /** APK signer matches the installed module's signing history. */
    data class Trusted(override val signers: List<SignerInfo>) : ApkVerification

    /** Module is not installed yet, so there is nothing to compare against. */
    data class NewInstall(override val signers: List<SignerInfo>) : ApkVerification

    /** Signer differs from the installed module. Android would refuse this update. */
    data class Mismatch(
        override val signers: List<SignerInfo>,
        val installedSigners: List<SignerInfo>,
    ) : ApkVerification

    /** APK is unsigned or could not be parsed. */
    data class Invalid(val reason: String) : ApkVerification {
        override val signers: List<SignerInfo> = emptyList()
    }
}
