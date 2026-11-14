package com.enjiws.tunnel.profiles

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Ekspor/impor profil ke file JSON. Password, private key, dan passphrase TIDAK ikut diekspor. */
object ProfileBackup {
    fun write(context: Context, uri: Uri, profiles: List<TunnelProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("type", p.type.name)
                put("host", p.host)
                put("port", p.port)
                put("username", p.username)
                put("dns", p.dns)
                put("tls", p.tls)
                put("sni", p.sni)
                put("favorite", p.favorite)
            })
        }
        val root = JSONObject().put("app", "enjiws-tunnel").put("version", 1).put("profiles", arr)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Tidak bisa menulis file")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    fun read(context: Context, uri: Uri): List<TunnelProfile> {
        val input = context.contentResolver.openInputStream(uri) ?: error("Tidak bisa membaca file")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text.length > 1_000_000) error("File terlalu besar")
        val arr = runCatching { JSONObject(text).optJSONArray("profiles") }.getOrNull()
            ?: error("Bukan file profil Enjiws")
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                TunnelProfile(
                    id = "p_${now}_$i",
                    name = o.getString("name"),
                    type = TunnelType.valueOf(o.optString("type", "SSH")),
                    host = o.getString("host"),
                    port = o.optInt("port", 22),
                    username = o.optString("username", ""),
                    dns = o.optString("dns", "1.1.1.1").ifBlank { "1.1.1.1" },
                    tls = o.optBoolean("tls", false),
                    sni = o.optString("sni", ""),
                    favorite = o.optBoolean("favorite", false)
                )
            }.getOrNull()
        }
    }
}
