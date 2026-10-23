package io.github.tophtab.lspstore.ui

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.github.tophtab.lspstore.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "LspStoreError"

/**
 * Maps a network/IO failure to a localized message. Returns null for anything
 * that is not a recognised transport error so callers can fall back to their
 * own screen-specific string instead of leaking a raw exception message.
 */
fun Throwable.localizedNetworkMessage(context: Context): String? = when (this) {
    is UnknownHostException -> context.getString(R.string.error_network)
    is SocketTimeoutException -> context.getString(R.string.error_timeout)
    is HttpRequestTimeoutException -> context.getString(R.string.error_timeout)
    is ResponseException -> context.getString(R.string.error_http, response.status.value)
    is IOException -> context.getString(R.string.error_network)
    else -> null
}

/**
 * Localized message for a failed operation: a transport error when recognised,
 * otherwise the caller's own contextual string.
 */
fun Throwable.toUserMessage(
    context: Context,
    @StringRes fallback: Int,
): String {
    // Keep the raw cause in logcat for bug reports while the UI stays localized.
    Log.w(TAG, "Operation failed", this)
    return localizedNetworkMessage(context) ?: context.getString(fallback)
}