package io.github.tophtab.lspstore.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads and writes [LibraryBackup] files through a document URI picked by the
 * user, so no storage permission is involved and the file may live anywhere the
 * document picker can reach (Downloads, an SD card, a cloud provider).
 */
class LibraryBackupStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    suspend fun read(uri: Uri): LibraryBackup = withContext(Dispatchers.IO) {
        val text = resolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("Cannot open $uri")
        LibraryBackup.decode(text)
    }

    suspend fun write(uri: Uri, backup: LibraryBackup) = withContext(Dispatchers.IO) {
        val bytes = LibraryBackup.encode(backup).toByteArray(Charsets.UTF_8)
        // "wt" truncates: overwriting a longer file must not leave a tail.
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
        stream.use { it.write(bytes) }
    }
}
