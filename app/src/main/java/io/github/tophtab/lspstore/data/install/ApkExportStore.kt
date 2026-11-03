package io.github.tophtab.lspstore.data.install

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes finished downloads in the public `Download/LSP_Store` folder.
 *
 * MediaStore owns the public copy, so the app needs no storage permission: on
 * Android 10+ it may write and later delete the entries it created. The working
 * copy stays in the private cache because resumable transfers and FileProvider
 * both need a real [File].
 *
 * Entries are identified by the identity hash in their name (see
 * [exportedApkName]), so [deleteAll] never touches files another app or the
 * user put in the same folder.
 */
class ApkExportStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Copies [source] into Downloads unless an entry with that name already exists. */
    suspend fun export(source: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (exists(displayName)) return@runCatching true
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("no output stream for $displayName")
                output.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                }
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw IllegalStateException("could not publish $displayName")
                }
                true
            } catch (error: Throwable) {
                // Never leave an invisible pending entry behind.
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }.onFailure {
            Log.w(TAG, "Could not export ${source.name}: ${it.message}")
        }.getOrDefault(false)
    }

    /** Bytes occupied by the APKs this app exported and that still exist. */
    suspend fun exportedBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { ownEntries().sumOf { it.size } }.getOrDefault(0L)
    }

    /** Deletes every APK this app exported; other files in the folder stay. */
    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            runCatching {
                ownEntries().forEach { entry ->
                    resolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            entry.id,
                        ),
                        null,
                        null,
                    )
                }
            }
        }
    }

    private fun exists(displayName: String): Boolean = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$RELATIVE_DIR%", displayName),
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /**
     * Entries under `Download/LSP_Store` whose names carry an identity hash
     * (`-<16 hex>.<ext>`). That suffix is what separates our exports from
     * anything else the user keeps in the folder.
     */
    private fun ownEntries(): List<ExportedApk> = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_DIR%"),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!OWN_NAME.containsMatchIn(name)) continue
                    add(
                        ExportedApk(
                            id = cursor.getLong(idColumn),
                            size = cursor.getLong(sizeColumn),
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private data class ExportedApk(val id: Long, val size: Long)

    private companion object {
        const val TAG = "ApkExportStore"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val RELATIVE_DIR = "Download/LSP_Store"

        /** Matches the `-<identity hash>` suffix [exportedApkName] appends. */
        val OWN_NAME = Regex("-[0-9a-f]{16}\\.[A-Za-z0-9]+$")
    }
}
