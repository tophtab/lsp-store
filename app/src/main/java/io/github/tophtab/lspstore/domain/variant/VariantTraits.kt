package io.github.tophtab.lspstore.domain.variant

/**
 * Structured tags read from an APK file name.
 *
 * Parsing is deliberately white-list based: bare numbers never mean an OS
 * version (`FiveGSwitcher-2.0.4_14-panel` ships the app version, not Android),
 * and `x86` must not match `x86_64`.
 */
data class VariantTraits(
    /** arm64-v8a / armeabi-v7a / x86 / x86_64 */
    val abis: Set<String> = emptySet(),
    val universal: Boolean = false,
    val debugName: Boolean = false,
    /** 100 / 101 / 102 when the name carries a libxposed API marker. */
    val libxposedApi: Int? = null,
    val legacyWord: Boolean = false,
    /** `modern` names are libxposed builds without an explicit API number. */
    val modernWord: Boolean = false,
    /** Android marketing version: A16 / Android14 / android15+ -> 16 / 14 / 15. */
    val android: Int? = null,
    val vendor: Pair<VendorOs.Family, Int?>? = null,
    /** `minsdk35` only. */
    val minSdkToken: Int? = null,
    /** full / lite / foss / pro / normal / root / native */
    val edition: Set<String> = emptySet(),
    /** toast / panel / tile / all / adblock / simple / sign */
    val flavors: Set<String> = emptySet(),
    /** Package name found in the file name, e.g. `com.google.android.apps.translate`. */
    val targetApp: String? = null,
) {
    val vendorFamily: VendorOs.Family?
        get() = vendor?.first

    val vendorMajor: Int?
        get() = vendor?.second

    /**
     * Stable token used inside [VariantSignature.os].
     * Vendor OS wins over the Android version: `A16_OneUI8.x` is One UI 8.
     */
    val osToken: String?
        get() = vendor?.let { (family, major) -> family.name.lowercase() + (major?.toString() ?: "") }
            ?: android?.let { "android$it" }
}

private val SEPARATOR = Regex("[\\s_.,+()\\[\\]]+")
private val DASHES = Regex("-+")

private val ABI_ARM64 = Regex("(^|[^a-z0-9])(arm64|aarch64|armv8)")
private val ABI_ARM32 = Regex("(^|[^a-z0-9])(armeabi|armv7|v7a|arm32)")
private val ABI_ARM_WORD = Regex("(^|[^a-z0-9])arm([^a-z0-9]|$)")
private val ABI_X86_64 = Regex("(^|[^a-z0-9])(x86[_-]?64|x64)([^a-z0-9]|$)")
private val ABI_X86 = Regex("(^|[^a-z0-9])x86([^a-z0-9]|$)")
private val UNIVERSAL_TOKEN = Regex("(^|[^a-z0-9])(universal|all[_-]?abi|allabi|armall|noarch|uni)([^a-z0-9]|$)")
private val DEBUG_TOKEN = Regex("(^|[^a-z0-9])(debug|dev)([^a-z0-9]|$)")
private val LEGACY_TOKEN = Regex("(^|[^a-z0-9])legacy")
private val MODERN_TOKEN = Regex("(^|[^a-z0-9])modern([^a-z0-9]|$)")
private val LIBXPOSED_API = Regex("(^|[^a-z0-9])api[_-]?(\\d{2,3})(?![0-9])")
private val MINSDK_TOKEN = Regex("minsdk[_-]?(\\d{2,3})")
private val ANDROID_LONG = Regex("(^|[^a-z0-9])android[_-]?(\\d{2})(?![0-9])")
private val ANDROID_SHORT = Regex("(^|[^a-z0-9])a(1[2-9])(?![0-9])")
private val VENDOR_OS = Regex("(^|[^a-z0-9])os([23])(?![0-9])")
private val VENDOR_HYPEROS = Regex("hyperos(?:[_-]?(\\d+))?")
private val VENDOR_MIUI = Regex("miui(?:[_-]?(\\d+))?")
private val VENDOR_ONEUI = Regex("oneui[_-]?(\\d+)")
private val VENDOR_COLOROS = Regex("coloros(?:[_-]?(\\d+))?")
private val VENDOR_ORIGINOS = Regex("originos(?:[_-]?(\\d+))?")
private val TARGET_APP = Regex(
    "(?<![a-z0-9])(" +
        "(?:com|org|io|net|dev|me|cn|app|xyz|top|cc|tv|moe|github|eu|us|uk|de|fr|jp|kr|ru|in)" +
        "\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+)" +
        "(?![a-z0-9])",
)

private val EDITION_WORDS = setOf("full", "lite", "foss", "pro", "normal", "root", "native")
private val FLAVOR_WORDS = setOf(
    "toast", "panel", "tile", "all", "adblock", "simple", "sign",
    "offline", "online", "log", "nolog", "icon",
)

fun parseTraits(assetName: String): VariantTraits {
    val raw = assetName.lowercase()
    // Keep '_' while looking for x86_64, then normalise everything to dashes.
    val abiSource = raw.replace(Regex("[\\s.,+()\\[\\]]"), "-").replace(DASHES, "-")
    val name = raw.replace(SEPARATOR, "-").replace(DASHES, "-")
    val tokens = name.split('-').filter { it.isNotEmpty() }

    val abis = linkedSetOf<String>()
    if (ABI_ARM64.containsMatchIn(name)) abis += "arm64-v8a"
    if (ABI_ARM32.containsMatchIn(name) || ABI_ARM_WORD.containsMatchIn(name)) {
        abis += "armeabi-v7a"
    }
    if (ABI_X86_64.containsMatchIn(abiSource)) abis += "x86_64"
    else if (ABI_X86.containsMatchIn(abiSource)) abis += "x86"

    val targetApp = TARGET_APP.find(raw)?.groupValues?.get(1)?.removeSuffix(".apk")
    // `dev.foo.bar.apk` is a package name, not a dev build.
    val debugName = DEBUG_TOKEN.containsMatchIn(name) &&
        !(targetApp != null && targetApp.startsWith("dev."))

    val nonNative = name.contains("nonnative") || name.contains("non-native")
    val edition = buildSet {
        tokens.forEach { token ->
            when (token) {
                "native" -> if (!nonNative) add("native")
                in EDITION_WORDS -> add(token)
            }
        }
        if (nonNative) add("non-native")
    }

    return VariantTraits(
        abis = abis,
        universal = UNIVERSAL_TOKEN.containsMatchIn(name) || tokens.contains("uni"),
        debugName = debugName,
        libxposedApi = LIBXPOSED_API.find(name)?.groupValues?.get(2)?.toIntOrNull(),
        legacyWord = LEGACY_TOKEN.containsMatchIn(name),
        modernWord = MODERN_TOKEN.containsMatchIn(name),
        android = ANDROID_LONG.find(name)?.groupValues?.get(2)?.toIntOrNull()
            ?: ANDROID_SHORT.find(name)?.groupValues?.get(2)?.toIntOrNull(),
        vendor = parseVendor(name),
        minSdkToken = MINSDK_TOKEN.find(name)?.groupValues?.get(1)?.toIntOrNull(),
        edition = edition,
        flavors = tokens.filterTo(linkedSetOf()) { it in FLAVOR_WORDS },
        targetApp = targetApp,
    )
}

private fun parseVendor(name: String): Pair<VendorOs.Family, Int?>? {
    VENDOR_OS.find(name)?.let {
        return VendorOs.Family.HYPEROS to it.groupValues[2].toIntOrNull()
    }
    VENDOR_HYPEROS.find(name)?.let {
        return VendorOs.Family.HYPEROS to it.groupValues[1].toIntOrNull()
    }
    VENDOR_MIUI.find(name)?.let {
        return VendorOs.Family.MIUI to it.groupValues[1].toIntOrNull()
    }
    VENDOR_ONEUI.find(name)?.let {
        return VendorOs.Family.ONEUI to it.groupValues[1].toIntOrNull()
    }
    VENDOR_COLOROS.find(name)?.let {
        return VendorOs.Family.COLOROS to it.groupValues[1].toIntOrNull()
    }
    VENDOR_ORIGINOS.find(name)?.let {
        return VendorOs.Family.ORIGINOS to it.groupValues[1].toIntOrNull()
    }
    return null
}
