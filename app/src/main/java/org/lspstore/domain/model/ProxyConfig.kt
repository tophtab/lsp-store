package org.lspstore.domain.model

enum class ProxyType { HTTP, SOCKS }

data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val isValid: Boolean
        get() = !enabled || (host.isNotBlank() && port in 1..65535)
}
