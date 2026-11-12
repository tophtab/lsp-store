package io.github.tophtab.lspstore.data.install

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * True once the transfer reached an outcome. Waiting code must treat these as the
 * only statuses it can finish on; every other status means "not there yet".
 */
internal val DownloadStatus.isTerminal: Boolean
    get() = this == DownloadStatus.COMPLETED ||
        this == DownloadStatus.FAILED ||
        this == DownloadStatus.CANCELLED

data class DownloadTask(
    val key: String,
    val assetName: String,
    val downloaded: Long = 0,
    val total: Long = 0,
    val status: DownloadStatus = DownloadStatus.RUNNING,
    val file: File? = null,
    val error: String? = null,
) {
    val progress: Float
        get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Resumable APK downloader. Uses HTTP Range so a paused download can continue
 * when the server supports it (assets.lsposed.org and GitHub both do usually);
 * otherwise it restarts from zero.
 *
 * Partial files are never discarded just because a transfer failed: a dropped
 * connection, a mirror switch or even process death leaves the bytes on disk,
 * and the next attempt resumes from them. [fetchResumable] handles transient
 * failures on its own, waiting briefly for the network to return.
 */
class DownloadManager(
    private val context: Context,
    private val exporter: ApkExportStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val retry = DownloadRetry(network = AndroidNetworkMonitor(context))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Copies into the download folder, tracked so [clearAll] can wait for them. */
    private val exports = ConcurrentHashMap<String, Job>()
    private val taskStore = DownloadTaskStore()
    val tasks = taskStore.tasks

    fun keyOf(asset: ReleaseAsset): String = assetCacheKey(asset)

    fun taskFor(asset: ReleaseAsset): DownloadTask? = tasks.value[keyOf(asset)]

    /** An unsuccessful installer attempt must be able to fetch a fresh copy. */
    suspend fun invalidate(key: String) = withContext(Dispatchers.IO) {
        if (!key.matches(Regex("[0-9a-f]{16}"))) return@withContext
        val exporting = exports[key]
        exporting?.cancel()
        exporting?.join()
        val removed = synchronized(this@DownloadManager) {
            val status = tasks.value[key]?.status
            if (status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED) {
                false
            } else {
                invalidateCachedDownload(File(context.cacheDir, "apk"), key)
                taskStore.remove(key)
                true
            }
        }
        if (removed) exporter.delete(key)
    }

    /**
     * Atomic with respect to other callers: the RUNNING check and the job
     * creation are one step, so two install chains cannot both open a writer for
     * the same target file.
     */
    @Synchronized
    fun start(asset: ReleaseAsset): String {
        val key = keyOf(asset)
        val existing = tasks.value[key]
        if (existing?.status == DownloadStatus.RUNNING) return key
        if (existing?.status == DownloadStatus.COMPLETED && existing.file?.exists() == true) {
            // The export may not have run yet (process death right after the
            // transfer finished); it is idempotent, so retry it here.
            exportCompleted(key, asset, existing.file)
            return key
        }
        // A previously failed or interrupted transfer keeps its partial file;
        // launchDownload resumes from whatever is on disk.
        launchDownload(key, asset)
        return key
    }

    @Synchronized
    fun pause(key: String) {
        if (taskStore.pause(key) == null) return
        jobs[key]?.cancel()
    }

    @Synchronized
    fun resume(asset: ReleaseAsset) {
        val key = keyOf(asset)
        if (tasks.value[key]?.status == DownloadStatus.PAUSED) {
            launchDownload(key, asset)
        }
    }

    @Synchronized
    fun cancel(key: String) {
        val task = tasks.value[key] ?: return
        // A finished transfer is shared by whoever asked for it; cancelling one
        // consumer must not delete an APK another install chain is about to use.
        if (task.status == DownloadStatus.COMPLETED) return
        jobs[key]?.cancel()
        taskStore.cancel(key)
        // The working copy is ours; a cancelled transfer should not leave it
        // behind. A later attempt simply starts from zero.
        runCatching { task.file?.delete() }
    }

    fun clearFinished() = taskStore.clearFinished()

    suspend fun downloadBlocking(
        asset: ReleaseAsset,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val key = start(asset)
        return await(key, onProgress)
    }

    private suspend fun await(key: String, onProgress: (Long, Long) -> Unit): File {
        val progressJob = scope.launch {
            tasks.map { it[key] }.distinctUntilChanged().collect { task ->
                task?.let { onProgress(it.downloaded, it.total) }
            }
        }
        try {
            // Waiting for a terminal status is not enough: a cleared task
            // (Settings > clear downloaded APKs calls clearAll) disappears from
            // the map for good, and waiting for an outcome that can never arrive
            // would park the install chain forever. A missing task therefore ends
            // the wait as a cancellation.
            val task = tasks.map { it[key] }
                .first { it == null || it.status.isTerminal }
            if (task == null) throw IOException("Download cancelled")
            onProgress(task.downloaded, task.total)
            return when (task.status) {
                DownloadStatus.COMPLETED -> task.file ?: throw IOException("missing file")
                DownloadStatus.CANCELLED -> throw IOException("Download cancelled")
                else -> throw IOException(task.error ?: "Download failed")
            }
        } finally {
            progressJob.cancel()
        }
    }

    private fun launchDownload(key: String, asset: ReleaseAsset) {
        // Keep the cancelled writer reachable until it has closed the file. A
        // quick pause/resume must not open a second writer on the same partial.
        val previousJob = jobs[key]
        val target = targetFile(key, asset)
        val existingBytes = if (target.exists()) target.length() else 0L
        // A file that already covers the asset is a finished transfer whose
        // completion could not be recorded (process death right after the last
        // write). There is nothing left to ask the network for.
        val complete = asset.size > 0L && existingBytes == asset.size

        val attempt = taskStore.begin(
            DownloadTask(
                key = key,
                assetName = asset.name,
                downloaded = existingBytes,
                total = if (complete) existingBytes else asset.size,
                status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.RUNNING,
                file = target,
                error = null,
            ),
        )
        if (complete) {
            exportCompleted(key, asset, target)
            return
        }

        DownloadService.start(context)

        // Register before starting so a fast completion cannot leave a finished
        // job in the map, and pause/cancel can always find the new transfer.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previousJob?.join()
                val urls = listOfNotNull(asset.downloadUrl.takeIf { it.isNotBlank() }, asset.fallbackUrl)
                    .distinct()
                var lastError: Throwable? = null
                for (url in urls) {
                    try {
                        // Continue from whatever an earlier attempt or mirror
                        // wrote, so a retry and a mid-stream fallback both cost
                        // only the bytes that were in flight.
                        val offset = if (target.exists()) target.length() else 0L
                        val serverSize = fetchResumable(
                            client = client,
                            url = url,
                            target = target,
                            resumeFrom = offset,
                            expectedSize = asset.size,
                            onProgress = { downloaded, total ->
                                taskStore.progress(attempt, downloaded, if (total > 0) total else asset.size)
                            },
                            retry = retry,
                        )
                        // The server's own total wins over the index metadata, which
                        // can be stale after an asset is re-uploaded under the same URL.
                        val expectedSize = if (serverSize > 0) serverSize else asset.size
                        if (expectedSize > 0 && target.length() != expectedSize) {
                            throw IOException(
                                "Downloaded size ${target.length()} does not match $expectedSize",
                            )
                        }
                        if (!taskStore.complete(attempt, target)) return@launch
                        // Best effort and off the critical path: the install uses
                        // the private file, so a failed export never blocks it.
                        exportCompleted(key, asset, target)
                        return@launch
                    } catch (t: Throwable) {
                        lastError = t
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    }
                }
                throw lastError ?: IOException("No download URL available")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    // Paused or cancelled: keep the partial file for resume.
                    return@launch
                }
                taskStore.fail(attempt, t.message ?: "Download failed")
            } finally {
                val self = coroutineContext[Job]
                if (self != null) jobs.remove(key, self) else jobs.remove(key)
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun targetFile(key: String, asset: ReleaseAsset): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val safeName = asset.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "module.apk" }
        return File(dir, "$key-$safeName")
    }

    /**
     * Publishes a finished transfer in the download folder. Runs on the download
     * scope so the caller (install chain) never waits for the copy, and is
     * idempotent for the same asset identity.
     */
    private fun exportCompleted(key: String, asset: ReleaseAsset, file: File?) {
        if (file == null || !file.exists()) return
        exports.computeIfAbsent(key) {
            scope.launch {
                val self = coroutineContext[Job]
                try {
                    exporter.export(file, exportedApkName(key, asset))
                } finally {
                    if (self != null) exports.remove(key, self) else exports.remove(key)
                }
            }
        }
    }

    /**
     * Space used by both copies: the private working files and the exported
     * APKs in the download folder. The Settings entry clears both.
     */
    suspend fun downloadedBytes(): Long = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk")
        val working = if (dir.exists()) {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        working + exporter.exportedBytes()
    }

    /** Cancels every running/paused download, keeping the tasks list for history. */
    @Synchronized
    fun cancelAllActive() {
        tasks.value.keys.toList().forEach { key ->
            val task = tasks.value[key] ?: return@forEach
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.PAUSED) {
                jobs[key]?.cancel()
                taskStore.cancel(key)
            }
        }
    }

    /**
     * Cancels transfers, removes the private working files and deletes the
     * APKs this app exported to the download folder. Waits for in-flight exports
     * so none of them can publish a file after the cleanup query ran.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val runningDownloads = jobs.values.toList()
        runningDownloads.forEach { it.cancel() }
        // Socket cancellation wakes blocked reads; joining also waits for their
        // file handles to close before files and exported copies are removed.
        runningDownloads.forEach { it.join() }
        jobs.clear()
        val runningExports = exports.values.toList()
        runningExports.forEach { it.cancel() }
        runningExports.forEach { it.join() }
        exports.clear()
        taskStore.clear()
        runCatching { File(context.cacheDir, "apk").deleteRecursively() }
        exporter.deleteAll()
    }
}

/**
 * Cache key for an asset. The file name alone is not unique: many modules ship
 * assets called app-release.apk, and one module can reuse a name across releases.
 * Hash the download identity so two different APKs can never share a task or file.
 */
internal fun assetCacheKey(asset: ReleaseAsset): String {
    val identity = buildString {
        append(asset.downloadUrl)
        append('|')
        append(asset.fallbackUrl.orEmpty())
        append('|')
        append(asset.name)
        append('|')
        append(asset.size)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Also works after process death, when there is no in-memory task. */
internal fun invalidateCachedDownload(directory: File, key: String) {
    if (!key.matches(Regex("[0-9a-f]{16}"))) return
    directory.listFiles()?.filter { it.isFile && it.name.startsWith("$key-") }
        ?.forEach { it.delete() }
}
