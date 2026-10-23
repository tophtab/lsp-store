package io.github.tophtab.lspstore.data.probe

/** Values the variant resolver needs from a compiled `AndroidManifest.xml`. */
internal data class ManifestFacts(
    val packageName: String? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val debuggable: Boolean = false,
    val metaData: Map<String, String> = emptyMap(),
    val hasLibXposedProvider: Boolean = false,
)

/**
 * Tiny binary-XML reader for AndroidManifest.xml. Only the handful of
 * attributes the resolver needs are decoded; every failure returns null and the
 * caller falls back to the file-name layer.
 */
internal object AxmlParser {

    private const val XML_TYPE = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_ELEMENT = 0x0102
    private const val RESOURCE_MAP = 0x0180
    private const val XPOSED_PROVIDER = "io.github.libxposed.service.XposedProvider"

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    fun parse(bytes: ByteArray): ManifestFacts? = runCatching { parseInternal(bytes) }.getOrNull()

    private fun parseInternal(bytes: ByteArray): ManifestFacts? {
        if (bytes.size < 8 || bytes.le16(0) != XML_TYPE) return null
        val headerSize = bytes.le16(2)
        var strings: List<String>? = null
        var packageName: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var debuggable = false
        val metaData = linkedMapOf<String, String>()
        var hasProvider = false

        var position = headerSize
        while (position + 8 <= bytes.size) {
            val chunkType = bytes.le16(position)
            val chunkSize = bytes.le32(position + 4).toInt()
            if (chunkSize < 8 || position + chunkSize > bytes.size) break
            when (chunkType) {
                STRING_POOL -> strings = readStringPool(bytes, position)
                START_ELEMENT -> {
                    val pool = strings ?: return null
                    val element = pool.getOrNull(bytes.le32(position + 20).toInt()).orEmpty()
                    val attributeStart = bytes.le16(position + 24)
                    val attributeSize = bytes.le16(position + 26).takeIf { it >= 20 } ?: 20
                    val attributeCount = bytes.le16(position + 28)
                    val attributes = linkedMapOf<String, String>()
                    var attribute = position + 16 + attributeStart
                    var index = 0
                    while (index < attributeCount && attribute + 20 <= position + chunkSize) {
                        val nameIndex = bytes.le32(attribute + 4).toInt()
                        val rawIndex = bytes.le32(attribute + 8).toInt()
                        val dataType = bytes[attribute + 15].toInt() and 0xFF
                        val data = bytes.le32(attribute + 16).toInt()
                        val name = pool.getOrNull(nameIndex)
                        if (name != null) {
                            val raw = pool.getOrNull(rawIndex)
                            val text = raw ?: decodeTyped(pool, dataType, data)
                            if (text != null) attributes[name] = text
                        }
                        attribute += attributeSize
                        index++
                    }
                    when (element) {
                        "manifest" -> {
                            packageName = attributes["package"]
                            versionCode = attributes["versionCode"]?.toLongOrNull()
                            versionName = attributes["versionName"]
                        }

                        "uses-sdk" -> {
                            minSdk = attributes["minSdkVersion"]?.toIntOrNull()
                            targetSdk = attributes["targetSdkVersion"]?.toIntOrNull()
                        }

                        "application" -> {
                            debuggable = attributes["debuggable"] == "true"
                        }

                        "meta-data" -> {
                            val name = attributes["name"]
                            if (name != null) metaData[name] = attributes["value"].orEmpty()
                        }

                        "provider" -> {
                            if (attributes["name"] == XPOSED_PROVIDER) hasProvider = true
                        }
                    }
                }

                RESOURCE_MAP -> Unit
                else -> Unit
            }
            position += chunkSize
        }

        if (strings?.contains(XPOSED_PROVIDER) == true) hasProvider = true
        if (packageName == null && minSdk == null && metaData.isEmpty() && !hasProvider) return null
        return ManifestFacts(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            debuggable = debuggable,
            metaData = metaData,
            hasLibXposedProvider = hasProvider,
        )
    }

    private fun decodeTyped(pool: List<String>, dataType: Int, data: Int): String? = when (dataType) {
        TYPE_STRING -> pool.getOrNull(data)
        TYPE_INT_DEC -> data.toString()
        TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
        TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
        else -> null
    }

    private fun readStringPool(bytes: ByteArray, start: Int): List<String> {
        val headerSize = bytes.le16(start + 2)
        val count = bytes.le32(start + 8).toInt()
        val flags = bytes.le32(start + 16).toInt()
        val stringsStart = bytes.le32(start + 20).toInt()
        val offsetsBase = start + headerSize
        val dataBase = start + stringsStart
        val utf8 = flags and 0x100 != 0
        val strings = ArrayList<String>(count.coerceAtMost(4096))
        for (index in 0 until count) {
            val offset = bytes.le32(offsetsBase + index * 4).toInt()
            val value = readString(bytes, dataBase + offset, utf8)
            if (value == null) return strings
            strings += value
        }
        return strings
    }

    private fun readString(bytes: ByteArray, position: Int, utf8: Boolean): String? {
        var cursor = position
        if (utf8) {
            var length = bytes[cursor++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                length = ((length and 0x7F) shl 8) or (bytes[cursor++].toInt() and 0xFF)
            }
            var byteLength = bytes[cursor++].toInt() and 0xFF
            if (byteLength and 0x80 != 0) {
                byteLength = ((byteLength and 0x7F) shl 8) or (bytes[cursor++].toInt() and 0xFF)
            }
            if (cursor + byteLength > bytes.size) return null
            return String(bytes, cursor, byteLength, Charsets.UTF_8)
        }
        var length = bytes.le16(cursor)
        cursor += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or bytes.le16(cursor)
            cursor += 2
        }
        if (cursor + length * 2 > bytes.size) return null
        return String(bytes, cursor, length * 2, Charsets.UTF_16LE)
    }
}
