package io.github.tophtab.lspstore.data.device

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.VendorOs

/**
 * P1 device profile: SDK + ABI immediately, vendor OS and the installed LSPosed
 * API capability asynchronously (single `getprop` call, 1.5 s budget).
 * Everything is cheap, local and failure-tolerant: an unknown value stays null
 * and the resolver simply skips that scoring axis.
 */
class DeviceProfileProvider(private val context: Context) {

    private val mutex = Mutex()
    private val _profile = MutableStateFlow(baseProfile())
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    /** Idempotent; call once at startup and whenever LSPosed may have changed. */
    suspend fun refresh(): DeviceProfile = mutex.withLock {
        val props = readProperties()
        val vendor = parseVendorOs(props, Build.VERSION.INCREMENTAL)
        val api = readLsposedApi(context)
        val profile = DeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            vendorOs = vendor,
            lsposedApi = api,
        )
        _profile.value = profile
        profile
    }

    private fun baseProfile(): DeviceProfile = DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
    )

    private suspend fun readProperties(): Map<String, String> = withTimeoutOrNull(1_500) {
        withContext(Dispatchers.IO) {
            val process = runCatching { ProcessBuilder("getprop").start() }.getOrNull()
                ?: return@withContext emptyMap()
            try {
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                parseGetprop(text)
            } catch (_: Throwable) {
                emptyMap()
            } finally {
                process.destroy()
            }
        }
    } ?: emptyMap()

    private fun readLsposedApi(context: Context): Int? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(LSPOSED_PACKAGE, 0)
        lsposedApiFor(info.versionName)
    }.getOrNull()

    private companion object {
        const val LSPOSED_PACKAGE = "org.lsposed.manager"
    }
}

/** `[ro.mi.os.version.name]: [OS3.0]` → map. */
fun parseGetprop(raw: String): Map<String, String> = buildMap {
    Regex("^\\[(.+?)]:\\s\\[(.*)]$", RegexOption.MULTILINE).findAll(raw).forEach { match ->
        put(match.groupValues[1], match.groupValues[2])
    }
}

fun parseVendorOs(props: Map<String, String>, incremental: String?): VendorOs? {
    props["ro.mi.os.version.name"]?.let { value ->
        firstInt(value)?.let { return VendorOs(VendorOs.Family.HYPEROS, it) }
    }
    props["ro.miui.ui.version.name"]?.let { value ->
        firstInt(value)?.let { return VendorOs(VendorOs.Family.MIUI, it) }
    }
    props["ro.build.version.oneui"]?.let { value ->
        parseOneUi(value)?.let { return VendorOs(VendorOs.Family.ONEUI, it) }
    }
    props["ro.build.version.oplusrom"]?.let { value ->
        firstInt(value)?.let { return VendorOs(VendorOs.Family.COLOROS, it) }
    }
    props["ro.vivo.os.version"]?.let { value ->
        firstInt(value)?.let { return VendorOs(VendorOs.Family.ORIGINOS, it) }
    }
    incremental?.let { value ->
        Regex("(^|[^A-Za-z0-9])OS(\\d+)").find(value)?.groupValues?.get(2)?.toIntOrNull()?.let {
            return VendorOs(VendorOs.Family.HYPEROS, it)
        }
    }
    return null
}

/** LSPosed's versionName → highest libxposed API we assume it supports. */
fun lsposedApiFor(versionName: String?): Int? {
    if (versionName.isNullOrBlank()) return null
    val match = Regex("(\\d+)\\.(\\d+)").find(versionName) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    return when {
        major < 1 -> 93
        major == 1 && minor < 9 -> 93
        major == 1 && minor == 9 -> 100
        else -> 102
    }
}

private fun firstInt(value: String): Int? =
    Regex("\\d+").find(value)?.value?.toIntOrNull()

/** One UI reports build numbers such as 80000 (8.0) / 60100 (6.1). */
private fun parseOneUi(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.all { it.isDigit() } && trimmed.length >= 5) {
        return trimmed.toIntOrNull()?.div(10_000)
    }
    return firstInt(trimmed)
}
