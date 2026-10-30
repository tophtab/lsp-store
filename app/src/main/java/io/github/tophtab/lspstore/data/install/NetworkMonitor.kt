package io.github.tophtab.lspstore.data.install

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the downloader whether the device currently has a usable network, and
 * lets a failed transfer wait for a reconnection instead of hammering a dead
 * socket. With no monitor, a retry simply backs off.
 */
interface NetworkMonitor {

    /** Whether a network that is actually validated can reach the internet. */
    fun isAvailable(): Boolean

    /**
     * Suspends until a validated network is available again, or [timeoutMs]
     * passes. Returns false on timeout.
     */
    suspend fun awaitAvailable(timeoutMs: Long): Boolean

    /** Never waits: the default for tests and non-Android callers. */
    object AlwaysOnline : NetworkMonitor {
        override fun isAvailable(): Boolean = true

        override suspend fun awaitAvailable(timeoutMs: Long): Boolean = true
    }
}

/**
 * [NetworkMonitor] backed by the default network callback.
 *
 * "Available" means the default network carries both `INTERNET` and `VALIDATED`:
 * a Wi-Fi connection that is still associated but has no upstream (the usual
 * state right after a router reboots or a phone loses range) would make a retry
 * fail again immediately. Validation is what turns that into "wait a moment".
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isAvailable(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network).isUsable()
    }

    override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
        val manager = connectivity ?: return true
        // Register before the second check: a reconnection between the first
        // check and the registration must not be missed.
        val reconnected = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = qualify(network)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = qualify(network)

            private fun qualify(network: Network) {
                if (manager.getNetworkCapabilities(network).isUsable()) {
                    reconnected.complete(Unit)
                }
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            if (isAvailable()) reconnected.complete(Unit)
            withTimeoutOrNull(timeoutMs) { reconnected.await() } != null
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Some OEM builds reject callbacks while the app is in a restricted
            // state; fall back to "let the transfer decide".
            isAvailable()
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun NetworkCapabilities?.isUsable(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
