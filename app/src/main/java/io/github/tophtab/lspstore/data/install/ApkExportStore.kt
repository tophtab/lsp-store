package io.github.tophtab.lspstore.data.install

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes finished downloads in `Download/LSP_Store` by default, or in the
 * folder the user picked with the system folder picker.
 *
 * The default goes through MediaStore, so the app needs no storage permission:
 * on Android 10+ it may write and later delete the entries it created. A picked
 * folder is a document tree whose read/write grant the caller persists, and the
 * writes go through DocumentsContract, which also covers an SD card or a cloud
 * provider. The working copy stays in the private cache because resumable
 * transfers and FileProvider both need a real [File].
 *
 * Entries are identified by the identity hash in their name (see
 * [exportedApkName]), so [deleteAll] never touches files another app or the
 * user put in the same folder.
 */
class ApkExportStore(
    private val context: Context,
    /** Document tree URI of the chosen folder; blank keeps the default. */
    private val location: () -> String,
) {

    private val resolver get() = context.contentResolver

    /** Copies [source] into the download folder unless that name already exists. */
    suspend fun export(source: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val tree = treeUri()
        runCatching {
            if (tree != null) {
                exportToTree(tree, source, displayName)
            } else {
                exportToMediaStore(source, displayName)
            }
        }.onFailure {
            Log.w(TAG, "Could not export ${source.name}: ${it.message}")
        }.getOrDefault(false)
    }

    /** Bytes occupied by the APKs this app exported and that still exist. */
    suspend fun exportedBytes(): Long = withContext(Dispatchers.IO) {
        val tree = treeUri()
        runCatching {
            if (tree != null) {
                treeEntries(tree)
                    .filter { OWN_NAME.containsMatchIn(it.name) }
                    .sumOf { it.size }
            } else {
                mediaStoreEntries()
                    .filter { OWN_NAME.containsMatchIn(it.name) }
                    .sumOf { it.size }
            }
        }.getOrDefault(0L)
    }

    /** Deletes every APK this app exported; other files in the folder stay. */
    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            val tree = treeUri()
            runCatching {
                if (tree != null) {
                    treeEntries(tree)
                        .filter { OWN_NAME.containsMatchIn(it.name) }
                        .forEach { entry ->
                            runCatching {
                                DocumentsContract.deleteDocument(
                                    resolver,
                                    DocumentsContract.buildDocumentUriUsingTree(tree, entry.id),
                                )
                            }
                        }
                } else {
                    mediaStoreEntries()
                        .filter { OWN_NAME.containsMatchIn(it.name) }
                        .forEach { entry ->
                            resolver.delete(
                                ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    entry.id.toLong(),
                                ),
                                null,
                                null,
                            )
                        }
                }
            }
        }
    }

    /**
     * Readable path of the picked folder for the settings row, or null while
     * the default is in use. Primary-storage picks know their real path
     * (`Download/LSP_Store`); other providers only expose a display name.
     */
    suspend fun locationLabel(): String? = withContext(Dispatchers.IO) {
        val tree = treeUri() ?: return@withContext null
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        if (id != null && id.startsWith("primary:")) {
            val path = id.substringAfter(':').trim('/')
            if (path.isNotEmpty()) return@withContext path
        }
        treeDisplayName(tree) ?: id?.substringAfter(':')?.takeIf { it.isNotBlank() }
    }

    private fun treeUri(): Uri? = location().takeIf { it.isNotBlank() }?.toUri()

    private fun exportToMediaStore(source: File, displayName: String): Boolean {
        if (existsInMediaStore(displayName)) return true
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
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
            return true
        } catch (error: Throwable) {
            // Never leave an invisible pending entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun exportToTree(tree: Uri, source: File, displayName: String): Boolean {
        if (treeEntries(tree).any { it.name == displayName }) return true
        val document = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            ),
            APK_MIME_TYPE,
            displayName,
        ) ?: return false
        try {
            val output = resolver.openOutputStream(document)
                ?: throw IllegalStateException("no output stream for $displayName")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            return true
        } catch (error: Throwable) {
            // A half-written file would make the next export think the APK is
            // already there, so remove it before reporting the failure.
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw error
        }
    }

    private fun existsInMediaStore(displayName: String): Boolean = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$RELATIVE_DIR%", displayName),
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** Entries under `Download/LSP_Store`; [OWN_NAME] tells ours from the user's. */
    private fun mediaStoreEntries(): List<StoredEntry> = runCatching {
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
                    add(
                        StoredEntry(
                            id = cursor.getLong(idColumn).toString(),
                            name = cursor.getString(nameColumn) ?: continue,
                            size = cursor.getLong(sizeColumn),
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Direct children of the picked folder. */
    private fun treeEntries(tree: Uri): List<StoredEntry> = runCatching {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            ),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    add(
                        StoredEntry(
                            id = id,
                            name = cursor.getString(nameColumn) ?: continue,
                            size = if (cursor.isNull(sizeColumn)) {
                                0L
                            } else {
                                cursor.getLong(sizeColumn).coerceAtLeast(0L)
                            },
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun treeDisplayName(tree: Uri): String? = runCatching {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            ),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /** [id] is a MediaStore row id or a document id, depending on the location. */
    private data class StoredEntry(val id: String, val name: String, val size: Long)

    private companion object {
        const val TAG = "ApkExportStore"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val RELATIVE_DIR = "Download/LSP_Store"

        /** Matches the `-<identity hash>` suffix [exportedApkName] appends. */
        val OWN_NAME = Regex("-[0-9a-f]{16}\\.[A-Za-z0-9]+$")
    }
}
