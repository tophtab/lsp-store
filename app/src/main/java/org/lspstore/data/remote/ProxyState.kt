package org.lspstore.data.remote

import org.lspstore.domain.model.ProxyConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Mutable holder read by the proxy selector so settings changes apply without rebuilding the client. */
class ProxyState {
    @Volatile
    var current: ProxyConfig = ProxyConfig()
}

class DynamicProxySelector(private val state: ProxyState) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val config = state.current
        if (!config.enabled || !config.isValid || config.host.isBlank()) {
            return listOf(Proxy.NO_PROXY)
        }
        val type = if (config.type == org.lspstore.domain.model.ProxyType.SOCKS) {
            Proxy.Type.SOCKS
        } else {
            Proxy.Type.HTTP
        }
        return listOf(Proxy(type, InetSocketAddress.createUnresolved(config.host, config.port)))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
}
