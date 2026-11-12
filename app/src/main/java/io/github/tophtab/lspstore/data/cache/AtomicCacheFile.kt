package io.github.tophtab.lspstore.data.cache

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Same-directory staging keeps the old cache intact until the atomic rename. */
internal fun writeCacheAtomically(
    file: File,
    text: String,
    replace: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    val target = file.absoluteFile.toPath()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, "${file.name}.", ".tmp")
    try {
        FileOutputStream(temporary.toFile()).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        replace(temporary, target)
    } finally {
        runCatching { Files.deleteIfExists(temporary) }
    }
}
