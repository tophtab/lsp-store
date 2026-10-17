package org.lspstore.data.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lspstore.domain.model.ApkVerification
import org.lspstore.domain.model.ReleaseAsset
import java.io.File
import java.io.FileOutputStream

sealed interface InstallOutcome {
    data object SilentSuccess : InstallOutcome
    data object SystemInstallerLaunched : InstallOutcome
    data class SignatureMismatch(val verification: ApkVerification.Mismatch) : InstallOutcome
    data class Failure(val message: String) : InstallOutcome
}

class ApkInstaller(
    private val context: Context,
    private val client: HttpClient,
) {

    private val verifier = ApkVerifier(context)

    suspend fun download(
        asset: ReleaseAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "apk").apply { mkdirs() }
        val target = File(directory, asset.name.ifBlank { "module.apk" })

        onProgress(0, asset.size)
        val bytes: ByteArray = client.get(asset.downloadUrl).body()
        FileOutputStream(target).use { it.write(bytes) }
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        target
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    suspend fun install(
        file: File,
        packageName: String,
        useRoot: Boolean,
        allowSignatureMismatch: Boolean = false,
    ): InstallOutcome {
        when (val verification = verifier.verify(file, packageName)) {
            is ApkVerification.Invalid ->
                return InstallOutcome.Failure("APK rejected: ${verification.reason}")
            is ApkVerification.Mismatch ->
                if (!allowSignatureMismatch) return InstallOutcome.SignatureMismatch(verification)
            is ApkVerification.Trusted, is ApkVerification.NewInstall -> Unit
        }

        if (useRoot) {
            val ok = installSilently(file)
            if (ok) return InstallOutcome.SilentSuccess
        }
        return runCatching {
            launchSystemInstaller(file)
            InstallOutcome.SystemInstallerLaunched
        }.getOrElse { InstallOutcome.Failure(it.message ?: "Unable to launch installer") }
    }

    private suspend fun installSilently(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val command = "pm install -r ${shellQuote(file.absolutePath)}"
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            process.exitValue() == 0 || output.contains("Success", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun launchSystemInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun clearDownloads() {
        runCatching { File(context.cacheDir, "apk").deleteRecursively() }
    }

    fun downloadsSizeBytes(): Long {
        val directory = File(context.cacheDir, "apk")
        if (!directory.exists()) return 0
        return directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
