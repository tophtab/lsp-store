package io.github.tophtab.lspstore.domain.variant

/**
 * Everything the resolver may know about the current device.
 *
 * P0 only fills [sdkInt] and [abis]; [vendorOs] and [lsposedApi] are populated by
 * the P1 device-profile work and stay null until then. Keeping the fields here
 * means callers and the scoring table do not have to change later.
 */
data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    val vendorOs: VendorOs? = null,
    val lsposedApi: Int? = null,
)

data class VendorOs(val family: Family, val major: Int?) {
    enum class Family { HYPEROS, MIUI, ONEUI, COLOROS, ORIGINOS, OTHER }

    /** Stable token used inside [VariantSignature.os]. */
    val token: String
        get() = family.name.lowercase() + (major?.toString() ?: "")

    fun label(): String {
        val name = when (family) {
            Family.HYPEROS -> "HyperOS"
            Family.MIUI -> "MIUI"
            Family.ONEUI -> "One UI"
            Family.COLOROS -> "ColorOS"
            Family.ORIGINOS -> "OriginOS"
            Family.OTHER -> "Other"
        }
        return if (major == null) name else "$name $major"
    }
}

/**
 * Android marketing version for an API level. Release variants are named after
 * the marketing version (`A16`, `android15+`), never the API level.
 */
fun androidVersionFromSdk(sdkInt: Int): Int? = when {
    sdkInt >= 33 -> sdkInt - 20
    sdkInt == 32 -> 12
    sdkInt == 31 -> 12
    sdkInt == 30 -> 11
    sdkInt == 29 -> 10
    sdkInt == 28 -> 9
    sdkInt == 27 -> 8
    sdkInt == 26 -> 8
    sdkInt >= 21 -> 5 + (sdkInt - 21) / 2
    else -> null
}
