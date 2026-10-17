package org.lspstore.data.remote

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class ProxyAuthenticator(private val state: ProxyState) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val config = state.current
        if (config.username.isBlank()) return null
        // Only retry once, otherwise a bad credential loops forever.
        if (response.request.header("Proxy-Authorization") != null) return null
        return response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
            .build()
    }
}
