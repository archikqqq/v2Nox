package com.v2nox.app

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING,
}

/** Remove this declaration if the project already provides ServerConfig in another package. */
data class ServerConfig(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val rawUrl: String? = null,
    val security: String? = null,
)
