package org.lspstore.data.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.lspstore.R
import org.lspstore.domain.model.ApkVerification
import org.lspstore.domain.model.SignerInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Verifies that a downloaded APK is signed, and that its signer matches the
 * already installed module. Android itself rejects signature changes on update,
 * so this catches the problem before the user is sent to the installer.
 */
class ApkVerifier(private val context: Context) {

    fun verify(apkFile: File, packageName: String): ApkVerification {
        val archiveInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, signatureFlags())
        }.getOrNull() ?: return ApkVerification.Invalid(context.getString(R.string.verifier_invalid_apk))

        val apkSigners = archiveInfo.readSigners()
        if (apkSigners.isEmpty()) return ApkVerification.Invalid(context.getString(R.string.verifier_unsigned_apk))

        val installedInfo = runCatching {
            context.packageManager.getPackageInfo(packageName, signatureFlags())
        }.getOrNull() ?: return ApkVerification.NewInstall(apkSigners)

        val installedHistory = installedInfo.readSignerHistory()
        val apkDigests = apkSigners.map { it.sha256 }.toSet()
        val historyDigests = installedHistory.map { it.sha256 }.toSet()

        return if (historyDigests.isNotEmpty() && apkDigests.all { it in historyDigests }) {
            ApkVerification.Trusted(apkSigners)
        } else {
            ApkVerification.Mismatch(apkSigners, installedInfo.readSigners())
        }
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.readSigners(): List<SignerInfo> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners ?: return emptyList()
        } else {
            signatures ?: return emptyList()
        }
        return signatures.map { it.toSignerInfo() }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.readSignerHistory(): List<SignerInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptyList()
            val history = info.signingCertificateHistory
            val signatures = if (history != null && history.isNotEmpty()) history else info.apkContentsSigners
            return signatures.map { it.toSignerInfo() }
        }
        return readSigners()
    }

    private fun Signature.toSignerInfo(): SignerInfo {
        val bytes = toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val (subject, issuer) = runCatching {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            certificate.subjectX500Principal.name to certificate.issuerX500Principal.name
        }.getOrElse { null to null }
        return SignerInfo(sha256 = sha256, subject = subject, issuer = issuer)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
