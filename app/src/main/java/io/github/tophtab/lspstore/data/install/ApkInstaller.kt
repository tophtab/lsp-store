package io.github.tophtab.lspstore.data.install

import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import java.io.File
import java.util.concurrent.TimeUnit

// komi-store's AndroidInstaller hands the APK to the device's installer of record
// through a plain VIEW intent; nothing here targets a specific installer app.
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Hidden UserHandle constant; uid = userId * range + appId. */
private const val PER_USER_RANGE = 100_000

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

/**
 * Result of an uninstall request.
 *
 * A non-privileged caller has no in-process result: the system uninstaller owns
 * the confirmation page, and [PackageEventReceiver] reports the removal.
 */
sealed interface UninstallOutcome {
    /** su removed the package in-process. */
    data object Completed : UninstallOutcome

    /** The package was handed to the system uninstaller confirmation page. */
    data object DelegatedToSystem : UninstallOutcome

    data class Failure(val message: String) : UninstallOutcome
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

    /**
     * Any add/replace/remove the system reported, including packages this store
     * did not install. The list rescans on it without claiming an install
     * finished.
     */
    val packageChanges = MutableSharedFlow<String>(extraBufferCapacity = 8)

    suspend fun download(
        asset: ReleaseAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = downloadManager.downloadBlocking(asset, onProgress)

    suspend fun isRootAvailable(timeoutMs: Long = 15_000L): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching false
            }
            output.readText().contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * Hands [file] to the privileged path or the device's installer app.
     *
     * Whether the APK may replace what is installed (signature, downgrade, ABI,
     * ...) is the installer's and PackageManager's call, not ours: rooted users
     * may run a patched installer that accepts a different signing key, and
     * blocking it here would take that choice away. See
     * [io.github.tophtab.lspstore.data.install.ApkGate] for the checks that stay
     * (an APK that cannot run on this device at all).
     *
     * The caller records the hand-off itself (bounding package and version in a
     * `PendingInstall`), because a delegated install returns no result here.
     */
    suspend fun install(
        file: File,
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
            delegateToInstaller(file)
        }.getOrElse {
            InstallOutcome.Failure(it.message ?: context.getString(R.string.installer_launch_failed))
        }
    }

    /**
     * Removes [packageName], preferring the privileged path when [useRoot] is
     * set.
     *
     * Like [install], an unavailable or failed su falls back to the system
     * uninstaller instead of leaving the user with nothing. The fallback always
     * opens the confirmation page: a normal app cannot delete a package itself.
     */
    suspend fun uninstall(packageName: String, useRoot: Boolean): UninstallOutcome {
        if (useRoot && uninstallSilently(packageName)) {
            return UninstallOutcome.Completed
        }
        return runCatching {
            delegateToSystemUninstaller(packageName)
            UninstallOutcome.DelegatedToSystem
        }.getOrElse {
            UninstallOutcome.Failure(
                it.message ?: context.getString(R.string.installer_uninstall_launch_failed),
            )
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

    /**
     * Reports a delegated install that the store confirmed landed (package
     * broadcast or a resume-time rescan). The PACKAGE_ADDED broadcast is not
     * delivered to manifest receivers while the app is in the background
     * (Android 8+), so this is the one completion signal every path shares.
     */
    fun confirmInstalled(packageName: String) {
        installedPackages.tryEmit(packageName)
    }

    /** A package change that is not an install completion of ours. */
    fun notifyPackageChanged(packageName: String) {
        packageChanges.tryEmit(packageName)
    }

    private fun delegateToInstaller(file: File): InstallOutcome {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return InstallOutcome.Failure(context.getString(R.string.installer_no_handler))
        }
        context.startActivity(intent)
        return InstallOutcome.DelegatedToSystem
    }

    private fun delegateToSystemUninstaller(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun installSilently(file: File): Boolean = installerCommandSucceeded(
        command = listOf("su", "-c", "pm install -r ${shellQuote(file.absolutePath)}"),
        logDirectory = context.cacheDir,
    )

    private suspend fun uninstallSilently(packageName: String): Boolean {
        // The installed list is scanned for the calling user, so removal is
        // scoped to that user's copy as well.
        val userId = Process.myUid() / PER_USER_RANGE
        return installerCommandSucceeded(
            command = listOf("su", "-c", "pm uninstall --user $userId ${shellQuote(packageName)}"),
            logDirectory = context.cacheDir,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun clearDownloads() {
        downloadManager.clearAll()
    }

    suspend fun downloadsSizeBytes(): Long = downloadManager.downloadedBytes()
}
