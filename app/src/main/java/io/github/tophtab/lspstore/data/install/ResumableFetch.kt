package io.github.tophtab.lspstore.data.install

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
 * Returns the size the server says the asset has (0 when it does not say), which
 * is more trustworthy than the size recorded in the repository index.
 */
internal fun fetchResumable(
    client: OkHttpClient,
    url: String,
    target: File,
    resumeFrom: Long,
    expectedSize: Long,
    onProgress: (Long, Long) -> Unit,
): Long {
    var offset = resumeOffset(resumeFrom, expectedSize)
    if (offset == 0L) runCatching { target.delete() }

    while (true) {
        val total = fetchOnce(client, url, target, offset, onProgress)
        if (total != null) return total
        // The server refused or misplaced the range: one clean retry from zero.
        if (offset == 0L) throw IOException("HTTP 416")
        offset = 0L
        runCatching { target.delete() }
    }
}

/**
 * Returns the asset size the server reported (0 when unknown), or null when the
 * server refused or misplaced the requested range.
 */
private fun fetchOnce(
    client: OkHttpClient,
    url: String,
    target: File,
    resumeFrom: Long,
    onProgress: (Long, Long) -> Unit,
): Long? {
    val request = Request.Builder()
        .url(url)
        .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
        .build()

    client.newCall(request).execute().use { response ->
        if (response.code == 416 && resumeFrom > 0) return null
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

        val body = response.body ?: throw IOException("Empty response")
        val appending = response.code == 206 && resumeFrom > 0
        // A 206 must start exactly where we asked, otherwise appending would
        // splice unrelated bytes into the file.
        val contentRange = response.header("Content-Range")
        val partStart = contentRangeStart(contentRange)
        if (appending && partStart != null && partStart != resumeFrom) return null

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
