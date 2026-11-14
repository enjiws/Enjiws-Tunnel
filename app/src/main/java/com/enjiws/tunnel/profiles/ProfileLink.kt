package com.enjiws.tunnel.profiles

import android.net.Uri
import java.net.URI

/**
 * Format link profil: ssh://user@host:port?tls=1&sni=host#Nama  atau  http://user@host:port#Nama
 * Export TIDAK menyertakan password/key. Import menerima user:pass@ bila ada.
 */
object ProfileLink {
    fun format(p: TunnelProfile): String {
        val scheme = if (p.type == TunnelType.SSH) "ssh" else "http"
        val user = if (p.username.isNotBlank()) "${Uri.encode(p.username)}@" else ""
        val query = buildList {
            if (p.tls) add("tls=1")
            if (p.tls && p.sni.isNotBlank()) add("sni=${Uri.encode(p.sni)}")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return "$scheme://$user${p.host}:${p.port}$query#${Uri.encode(p.name)}"
    }

    fun parse(raw: String, idSuffix: String = ""): TunnelProfile? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val type = when (uri.scheme?.lowercase()) {
            "ssh" -> TunnelType.SSH
            "http" -> TunnelType.HTTP
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port in 1..65_535) uri.port else if (type == TunnelType.SSH) 22 else 8080
        val info = uri.userInfo.orEmpty()
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null else k to Uri.decode(it.substringAfter('=', ""))
        }.toMap()
        return TunnelProfile(
            id = "p_${System.currentTimeMillis()}$idSuffix",
            name = uri.fragment?.takeIf { it.isNotBlank() } ?: "$host:$port",
            type = type,
            host = host,
            port = port,
            username = info.substringBefore(':'),
            password = if (info.contains(':')) info.substringAfter(':') else "",
            tls = query["tls"] == "1",
            sni = query["sni"].orEmpty()
        )
    }

    /** Beberapa link sekaligus, satu per baris. */
    fun parseAll(raw: String): List<TunnelProfile> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .mapIndexedNotNull { i, line -> parse(line, "_$i") }
            .toList()
}
