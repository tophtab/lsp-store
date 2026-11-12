package io.github.tophtab.lspstore.data.install

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads [url] into [target], continuing from [resumeFrom] when the server
 * honours `Range`.
 *
 * A partial file is only resumed when it can be trusted. If the server answers
 * `416 Range Not Satisfiable` (the leftover bytes already cover the whole asset,
 * or the asset was re-uploaded smaller), answers a `206` for a different slice,
 * or ignores the range entirely, the file is thrown away and fetched from the
 * start instead of failing the download with a bare HTTP code.
 *
 * A transfer that dies mid-stream - the usual shape of a Wi-Fi drop or a switch
 * from Wi-Fi to mobile data - is retried by [retry] and continues from the bytes
 * already on disk, so a reconnection costs only the tail that was in flight.
 *
 * Returns the size the server says the asset has (0 when it does not say), which
 * is more trustworthy than the size recorded in the repository index.
 */
internal suspend fun fetchResumable(
    client: OkHttpClient,
    url: String,
    target: File,
    resumeFrom: Long,
    expectedSize: Long,
    onProgress: (Long, Long) -> Unit,
    retry: DownloadRetry = DownloadRetry(),
): Long {
    currentCoroutineContext().ensureActive()
    var offset = resumeOffset(resumeFrom, expectedSize)
    var resumeSize = expectedSize
    if (offset == 0L) runCatching { target.delete() }

    var attempt = 1
    while (true) {
        try {
            var from = offset
            while (true) {
                val total = fetchOnce(client, url, target, from) { downloaded, total ->
                    // Once a response arrives, its size is authoritative. A stale
                    // index must not discard bytes on every mid-stream retry.
                    resumeSize = total.coerceAtLeast(0L)
                    onProgress(downloaded, total)
                }
                if (total != null) {
                    // A body that ends quietly before the advertised size (a
                    // proxy or a server that closes exactly on a packet
                    // boundary) is as resumable as a thrown socket error.
                    if (total > 0L && target.length() < total) {
                        throw IOException("Incomplete download: ${target.length()} of $total bytes")
                    }
                    return total
                }
                // The server refused or misplaced the range: one clean retry
                // from zero. Anything else loops into the HTTP error branch.
                if (from == 0L) throw HttpStatusException(416)
                from = 0L
                runCatching { target.delete() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            // Keep whatever landed: the next attempt asks for the missing tail.
            offset = resumeOffset(target.length(), resumeSize)
            if (offset == 0L) runCatching { target.delete() }
            if (!retry.canRetry(attempt, error)) throw error
            attempt++
            if (!retry.awaitNextAttempt(attempt)) throw error
        }
    }
}

/**
 * How a failed transfer is retried. Reconnection is preferred over a fixed
 * delay: when the device has no validated network the retry waits for one to
 * come back, up to a bounded time, instead of failing behind the user's back.
 */
internal class DownloadRetry(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val offlineWaitMs: Long = DEFAULT_OFFLINE_WAIT_MS,
    private val network: NetworkMonitor = NetworkMonitor.AlwaysOnline,
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
) {

    /** Whether [attempt] (1-based) may run again after [error]. */
    fun canRetry(attempt: Int, error: Throwable): Boolean =
        attempt < maxAttempts && isTransientDownloadError(error)

    /**
     * Waits before attempt [nextAttempt] (2 for the first retry). Returns false
     * when the device stayed offline for the whole [offlineWaitMs] grace, which
     * means the failure should surface instead of holding the download hostage.
     */
    suspend fun awaitNextAttempt(nextAttempt: Int): Boolean {
        if (!network.isAvailable()) return network.awaitAvailable(offlineWaitMs)
        sleep(backoffMs(nextAttempt))
        return true
    }

    /** 1s, 2s, 4s, ... capped at [maxDelayMs]. */
    fun backoffMs(nextAttempt: Int): Long {
        val index = (nextAttempt - 2).coerceAtLeast(0)
        // Shift rather than pow so the cap applies before the value can overflow.
        return if (index >= 32) maxDelayMs else {
            (baseDelayMs shl index).coerceAtMost(maxDelayMs)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 4
        const val DEFAULT_BASE_DELAY_MS: Long = 1_000L
        const val DEFAULT_MAX_DELAY_MS: Long = 8_000L
        const val DEFAULT_OFFLINE_WAIT_MS: Long = 20_000L
    }
}

/**
 * A request the server answered with an HTTP error. Only 408, 429 and 5xx are
 * worth retrying; a 404 or a refusal of the range will not change on a retry.
 */
internal class HttpStatusException(val code: Int) : IOException("HTTP $code")

internal fun isTransientDownloadError(error: Throwable): Boolean = when (error) {
    is HttpStatusException -> error.code == 408 || error.code == 429 || error.code >= 500
    // Socket resets, timeouts, DNS while a network comes up, dropped TLS.
    is IOException -> true
    else -> false
}

/**
 * Returns the asset size the server reported (0 when unknown), or null when the
 * server refused or misplaced the requested range.
 */
private suspend fun fetchOnce(
    client: OkHttpClient,
    url: String,
    target: File,
    resumeFrom: Long,
    onProgress: (Long, Long) -> Unit,
): Long? = coroutineScope {
    val request = Request.Builder()
        .url(url)
        .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
        .build()

    val call = client.newCall(request)
    // Cancelling a coroutine does not interrupt OkHttp's blocking socket reads.
    // Keep a child listening for cancellation until the response is fully closed.
    val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            writeResponse(call.execute(), target, resumeFrom, onProgress, currentCoroutineContext())
        }
    } finally {
        cancelCall.cancel()
    }
}

private fun writeResponse(
    response: Response,
    target: File,
    resumeFrom: Long,
    onProgress: (Long, Long) -> Unit,
    transferContext: CoroutineContext,
): Long? {
    response.use {
        transferContext.ensureActive()
        if (response.code == 416 && resumeFrom > 0) return null
        if (!response.isSuccessful) throw HttpStatusException(response.code)

        val body = response.body ?: throw IOException("Empty response")
        val appending = response.code == 206 && resumeFrom > 0
        // A 206 must start exactly where we asked, otherwise appending would
        // splice unrelated bytes into the file.
        val contentRange = response.header("Content-Range")
        val partStart = contentRangeStart(contentRange)
        if (appending && partStart != resumeFrom) return null

        val contentLength = body.contentLength()
        val total = when {
            !appending -> contentLength
            else -> contentRangeTotal(contentRange)
                ?: if (contentLength >= 0L) resumeFrom + contentLength else -1L
        }

        RandomAccessFile(target, "rw").use { file ->
            if (!appending) file.setLength(0)
            file.seek(if (appending) resumeFrom else 0L)
            var downloaded = if (appending) resumeFrom else 0L
            onProgress(downloaded, total)
            transferContext.ensureActive()

            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    transferContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    transferContext.ensureActive()
                    file.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                    transferContext.ensureActive()
                }
            }
        }
        return if (total > 0L) total else 0L
    }
}

/**
 * Offset a resume may safely continue from, or 0 when the file must be fetched
 * again from the start. `expectedSize` is 0 when the asset size is unknown.
 */
internal fun resumeOffset(partialLength: Long, expectedSize: Long): Long = when {
    partialLength <= 0L -> 0L
    expectedSize > 0L && partialLength >= expectedSize -> 0L
    else -> partialLength
}

/** Start byte of a `Content-Range: bytes 100-999/1000` header, or null. */
internal fun contentRangeStart(header: String?): Long? {
    val raw = header?.trim()?.removePrefix("bytes")?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.substringBefore('/').trim().substringBefore('-').trim().toLongOrNull()
}

/** Total of a `Content-Range: bytes 100-999/1000` header, or null. */
internal fun contentRangeTotal(header: String?): Long? {
    val raw = header?.trim()?.removePrefix("bytes")?.trim().orEmpty()
    return raw.substringAfter('/', "").trim().toLongOrNull()
}
