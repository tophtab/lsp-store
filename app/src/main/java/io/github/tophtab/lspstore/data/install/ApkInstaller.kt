package io.github.tophtab.lspstore.data.install

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import java.io.File

// komi-store's AndroidInstaller hands the APK to the device's installer of record
// through a plain VIEW intent; nothing here targets a specific installer app.
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/**
 * Result of an install request.
 *
 * Mirrors komi-store's `InstallOutcome`: the default (non-root) path hands the
 * APK to whatever installer the device has registered for APKs and therefore
 * never learns the final result itself - [DelegatedToSystem]. Only a privileged
 * install reports [Completed] directly.
 */
sealed interface InstallOutcome {
    /** A privileged (root) install finished in-process. */
    data object Completed : InstallOutcome

    /** The APK was handed to the device's installer app (InstallerX, system installer, ...). */
    data object DelegatedToSystem : InstallOutcome

    data class Failure(val message: String) : InstallOutcome
}

class ApkInstaller(
    context: Context,
    private val downloadManager: DownloadManager,
) {
    private val context: Context = context.applicationContext

    /**
     * Packages the system reported as installed/removed, forwarded by
     * [PackageEventReceiver]. Delegated installs have no other completion signal.
     */
    val installedPackages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Packages handed to an external installer and not yet reported back. */
    private val delegatedPackages = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun download(
        asset: ReleaseAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = downloadManager.downloadBlocking(asset, onProgress)

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

    /**
     * @param packageName the package that the APK is expected to bring. Only used
     *   to remember the hand-off; the APK itself is not inspected here. Whether
     *   the APK may replace what is installed (signature, downgrade, ABI, ...) is
     *   the installer's and PackageManager's call, not ours: rooted users may run
     *   a patched installer that accepts a different signing key, and blocking it
     *   here would take that choice away. See [io.github.tophtab.lspstore.data.install.ApkGate]
     *   for the checks that stay (an APK that cannot run on this device at all).
     */
    suspend fun install(
        file: File,
        packageName: String,
        useRoot: Boolean,
        allowUi: Boolean = true,
    ): InstallOutcome {
        if (!file.exists()) {
            return InstallOutcome.Failure(
                context.getString(R.string.installer_apk_missing, file.name),
            )
        }

        // Privileged path (komi's InstallerType.ROOT): su installs the APK without
        // any system confirmation UI.
        if (useRoot && installSilently(file)) {
            return InstallOutcome.Completed
        }
        if (!allowUi) {
            return InstallOutcome.Failure(context.getString(R.string.installer_silent_failed))
        }

        // Default path (komi's InstallerType.DEFAULT): hand the APK to the user's
        // installer of choice. InstallerX/Dhizuku/AppManager all sit on this intent,
        // so no PackageInstaller session is created from this process.
        return runCatching {
            ensurePermissionsOrThrow()
            delegateToInstaller(file, packageName)
        }.getOrElse {
            InstallOutcome.Failure(it.message ?: context.getString(R.string.installer_launch_failed))
        }
    }

    /**
     * komi's `AndroidInstaller.ensurePermissionsOrThrow`: send the user to the
     * "Install unknown apps" toggle and abort instead of letting the installer
     * silently cancel the request.
     */
    fun ensurePermissionsOrThrow() {
        if (context.packageManager.canRequestPackageInstalls()) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        throw IllegalStateException(context.getString(R.string.installer_unknown_sources))
    }

    /** Called by [PackageEventReceiver] once the system reports the install landed. */
    fun onPackageInstalled(packageName: String) {
        // Only report packages we delegated ourselves; the receiver also sees
        // installs made outside the store.
        if (delegatedPackages.remove(packageName)) {
            installedPackages.tryEmit(packageName)
        }
    }

    /**
     * Reports a delegated install that we discovered by rescanning PackageManager
     * on resume. The PACKAGE_ADDED broadcast is not delivered to manifest
     * receivers while the app is in the background (Android 8+), so this is the
     * reliable completion signal for the system-installer path.
     */
    fun confirmInstalled(packageName: String) {
        delegatedPackages.remove(packageName)
        installedPackages.tryEmit(packageName)
    }

    private fun delegateToInstaller(file: File, packageName: String): InstallOutcome {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return InstallOutcome.Failure(context.getString(R.string.installer_no_handler))
        }
        context.startActivity(intent)
        delegatedPackages.add(packageName)
        return InstallOutcome.DelegatedToSystem
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

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun clearDownloads() {
        downloadManager.clearAll()
    }

    fun downloadsSizeBytes(): Long = downloadManager.downloadedBytes()
}
