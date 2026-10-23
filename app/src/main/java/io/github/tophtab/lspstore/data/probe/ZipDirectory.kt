package io.github.tophtab.lspstore.data.probe

/** Minimal central-directory reader; never allocates the whole archive. */
internal data class ZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

internal data class ZipEndRecord(
    val entryCount: Int,
    val directoryOffset: Long,
    val directorySize: Long,
)

internal object ZipDirectory {

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val EOCD_MIN_SIZE = 22

    /** Scans the tail of the file backwards for the end-of-central-directory record. */
    fun findEndRecord(tail: ByteArray): ZipEndRecord? {
        var position = tail.size - EOCD_MIN_SIZE
        while (position >= 0) {
            if (tail.le32(position) == EOCD_SIGNATURE) {
                val entries = tail.le16(position + 10)
                val size = tail.le32(position + 12)
                val offset = tail.le32(position + 16)
                // zip64 markers are unsupported on purpose.
                if (entries == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) return null
                return ZipEndRecord(entries, offset, size)
            }
            position--
        }
        return null
    }

    fun parseDirectory(bytes: ByteArray, entryCount: Int): List<ZipEntry> {
        val entries = ArrayList<ZipEntry>(entryCount.coerceAtMost(4096))
        var position = 0
        repeat(entryCount) {
            if (position + 46 > bytes.size || bytes.le32(position) != CENTRAL_SIGNATURE) {
                return entries
            }
            val method = bytes.le16(position + 10)
            val compressedSize = bytes.le32(position + 20)
            val uncompressedSize = bytes.le32(position + 24)
            val nameLength = bytes.le16(position + 28)
            val extraLength = bytes.le16(position + 30)
            val commentLength = bytes.le16(position + 32)
            val localOffset = bytes.le32(position + 42)
            if (position + 46 + nameLength > bytes.size) return entries
            val name = String(bytes, position + 46, nameLength, Charsets.UTF_8)
            entries += ZipEntry(name, method, compressedSize, uncompressedSize, localOffset)
            position += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** Offset of the entry data relative to the local header, from its 30-byte header. */
    fun dataOffset(localHeader: ByteArray): Long? {
        if (localHeader.size < 30 || localHeader.le32(0) != LOCAL_SIGNATURE) return null
        val nameLength = localHeader.le16(26)
        val extraLength = localHeader.le16(28)
        return 30L + nameLength + extraLength
    }

    fun abisOf(entries: List<ZipEntry>): Set<String> = entries.asSequence()
        .map { it.name }
        .filter { it.startsWith("lib/") }
        .mapNotNull { name -> name.split('/').takeIf { it.size >= 3 }?.get(1) }
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun ByteArray.le16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.le32(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
