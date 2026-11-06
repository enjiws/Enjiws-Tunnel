package com.enjiws.tunnel.data

import org.json.JSONObject

/**
 * Urutan koneksi: TCP (langsung / via proxy) -> TLS (opsional, SNI) -> payload HTTP (opsional) -> SSH.
 *
 * Placeholder payload: [crlf] [cr] [lf] [host] [port] [host_port] [protocol]
 */
data class Profile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val tlsEnabled: Boolean = false,
    val sni: String = "",
    val tlsInsecure: Boolean = false,
    val payload: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val dns: String = "1.1.1.1"
) {
    val isValid: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && port in 1..65535

    fun toJson(): String = JSONObject().apply {
        put("host", host); put("port", port)
        put("username", username); put("password", password)
        put("tlsEnabled", tlsEnabled); put("sni", sni); put("tlsInsecure", tlsInsecure)
        put("payload", payload); put("proxyHost", proxyHost); put("proxyPort", proxyPort)
        put("dns", dns)
    }.toString()

    companion object {
        fun fromJson(json: String): Profile {
            val o = JSONObject(json)
            return Profile(
                host = o.optString("host"),
                port = o.optInt("port", 22),
                username = o.optString("username"),
                password = o.optString("password"),
                tlsEnabled = o.optBoolean("tlsEnabled"),
                sni = o.optString("sni"),
                tlsInsecure = o.optBoolean("tlsInsecure"),
                payload = o.optString("payload"),
                proxyHost = o.optString("proxyHost"),
                proxyPort = o.optInt("proxyPort", 8080),
                dns = o.optString("dns", "1.1.1.1").ifBlank { "1.1.1.1" }
            )
        }
    }
}
