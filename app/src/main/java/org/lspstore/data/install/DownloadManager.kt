package org.lspstore.data.install

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lspstore.data.remote.DynamicProxySelector
import org.lspstore.data.remote.ProxyAuthenticator
import org.lspstore.data.remote.ProxyState
import org.lspstore.domain.model.ReleaseAsset
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

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
 */
class DownloadManager(
    private val context: Context,
    proxyState: ProxyState,
) {
    private val client = OkHttpClient.Builder()
        .proxySelector(DynamicProxySelector(proxyState))
        .proxyAuthenticator(ProxyAuthenticator(proxyState))
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks = _tasks.asStateFlow()

    fun keyOf(asset: ReleaseAsset): String = asset.name.ifBlank { asset.downloadUrl.takeLast(48) }

    fun taskFor(asset: ReleaseAsset): DownloadTask? = _tasks.value[keyOf(asset)]

    fun start(asset: ReleaseAsset): String {
        val key = keyOf(asset)
        val existing = _tasks.value[key]
        if (existing?.status == DownloadStatus.RUNNING || existing?.status == DownloadStatus.COMPLETED) {
            return key
        }
        launchDownload(key, asset, resume = existing?.status == DownloadStatus.PAUSED)
        return key
    }

    fun pause(key: String) {
        updateTask(key) { it.copy(status = DownloadStatus.PAUSED) }
        jobs.remove(key)?.cancel()
    }

    fun resume(asset: ReleaseAsset) {
        val key = keyOf(asset)
        if (_tasks.value[key]?.status == DownloadStatus.PAUSED) {
            launchDownload(key, asset, resume = true)
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        updateTask(key) { it.copy(status = DownloadStatus.CANCELLED) }
        runCatching { partialFile(_tasks.value[key]?.assetName.orEmpty()).delete() }
    }

    fun clearFinished() {
        _tasks.update { current ->
            current.filterValues {
                it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.CANCELLED
            }.let { finished -> current - finished.keys }
        }
    }

    suspend fun downloadBlocking(
        asset: ReleaseAsset,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val key = start(asset)
        return await(key, onProgress)
    }

    private suspend fun await(key: String, onProgress: (Long, Long) -> Unit): File {
        val progressJob = scope.launch {
            _tasks.collect { map ->
                map[key]?.let { onProgress(it.downloaded, it.total) }
            }
        }
        try {
            val task = _tasks
                .mapNotNull { it[key] }
                .first {
                    it.status == DownloadStatus.COMPLETED ||
                        it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELLED
                }
            return when (task.status) {
                DownloadStatus.COMPLETED -> task.file ?: throw IOException("missing file")
                DownloadStatus.CANCELLED -> throw IOException("Download cancelled")
                else -> throw IOException(task.error ?: "Download failed")
            }
        } finally {
            progressJob.cancel()
        }
    }

    private fun launchDownload(key: String, asset: ReleaseAsset, resume: Boolean) {
        val target = partialFile(asset.name)
        val existingBytes = if (resume && target.exists()) target.length() else 0L
        if (!resume) runCatching { target.delete() }

        updateTask(key, create = true) {
            it.copy(
                key = key,
                assetName = asset.name,
                downloaded = existingBytes,
                total = asset.size,
                status = DownloadStatus.RUNNING,
                error = null,
            )
        }

        jobs[key] = scope.launch {
            try {
                val urls = listOfNotNull(asset.downloadUrl.takeIf { it.isNotBlank() }, asset.fallbackUrl)
                    .distinct()
                var lastError: Throwable? = null
                for (url in urls) {
                    try {
                        fetch(url, target, existingBytes) { downloaded, total ->
                            updateTask(key) {
                                it.copy(
                                    downloaded = downloaded,
                                    total = if (total > 0) total else asset.size,
                                )
                            }
                        }
                        updateTask(key) {
                            it.copy(
                                status = DownloadStatus.COMPLETED,
                                file = target,
                                downloaded = target.length(),
                                total = target.length(),
                            )
                        }
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
                updateTask(key) {
                    it.copy(status = DownloadStatus.FAILED, error = t.message ?: "Download failed")
                }
            } finally {
                jobs.remove(key)
            }
        }
    }

    private fun fetch(url: String, target: File, resumeFrom: Long, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val body = response.body ?: throw IOException("Empty response")
            val appending = response.code == 206 && resumeFrom > 0
            val contentLength = body.contentLength()
            val total = if (appending) resumeFrom + contentLength else contentLength

            RandomAccessFile(target, "rw").use { file ->
                if (!appending) file.setLength(0)
                file.seek(if (appending) resumeFrom else 0)
                var downloaded = if (appending) resumeFrom else 0L
                onProgress(downloaded, total)

                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
    }

    private fun partialFile(assetName: String): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        return File(dir, assetName.ifBlank { "module.apk" })
    }

    private fun updateTask(key: String, create: Boolean = false, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { current ->
            val task = current[key] ?: if (create) DownloadTask(key = key, assetName = "") else return@update current
            current + (key to transform(task))
        }
    }

    fun downloadedBytes(): Long {
        val dir = File(context.cacheDir, "apk")
        if (!dir.exists()) return 0
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _tasks.value = emptyMap()
        runCatching { File(context.cacheDir, "apk").deleteRecursively() }
    }
}
