package com.enjiws.tunnel.profiles

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

enum class TunnelType { SSH, HTTP }

data class TunnelProfile(
    val id: String,
    val name: String,
    val type: TunnelType,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val dns: String = "1.1.1.1",
    /** Private key SSH (PEM/OpenSSH). Rahasia: disimpan di brankas terenkripsi, bukan di JSON. */
    val privateKey: String = "",
    val keyPassphrase: String = "",
    /** Bungkus koneksi ke server dalam TLS (SSH-over-TLS / HTTPS proxy). Sertifikat selalu diverifikasi. */
    val tls: Boolean = false,
    val sni: String = "",
    val favorite: Boolean = false
) {
    /** Hostname untuk SNI + verifikasi sertifikat; default host server. */
    val tlsHost: String get() = sni.ifBlank { host }
}

/**
 * Profil tunnel yang bisa dipakai koneksi.
 * Metadata disimpan sebagai JSON di SharedPreferences biasa. Rahasia (password, private key,
 * passphrase) disimpan di EncryptedSharedPreferences (Android Keystore); kalau brankas tidak
 * tersedia, fallback ke prefs biasa supaya profil tidak hilang.
 */
object TunnelProfileStore {
    private const val PREFS = "tunnel_profiles"
    private const val KEY_LIST = "profiles"
    private const val KEY_ACTIVE = "active_id"
    private val SECRET_PREFIXES = listOf("pw_", "key_", "kp_")

    // Dibuat sekali lalu di-cache: membuat EncryptedSharedPreferences itu mahal (Keystore).
    @Volatile
    private var cachedVault: SharedPreferences? = null

    private fun vault(context: Context): SharedPreferences? {
        cachedVault?.let { return it }
        val app = context.applicationContext
        return runCatching {
            EncryptedSharedPreferences.create(
                app,
                "tunnel_secrets",
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()?.also { cachedVault = it }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getSecret(context: Context, prefix: String, id: String): String {
        val key = prefix + id
        return vault(context)?.getString(key, null) ?: prefs(context).getString(key, "").orEmpty()
    }

    private fun putSecret(context: Context, prefix: String, id: String, value: String) {
        val key = prefix + id
        val v = vault(context)
        if (v != null) {
            v.edit().putString(key, value).apply()
            prefs(context).edit().remove(key).apply()
        } else {
            prefs(context).edit().putString(key, value).apply()
        }
    }

    fun all(context: Context): List<TunnelProfile> {
        val raw = prefs(context).getString(KEY_LIST, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val id = o.getString("id")
                TunnelProfile(
                    id = id,
                    name = o.getString("name"),
                    type = TunnelType.valueOf(o.optString("type", "SSH")),
                    host = o.getString("host"),
                    port = o.optInt("port", 22),
                    username = o.optString("username", ""),
                    password = getSecret(context, "pw_", id),
                    dns = o.optString("dns", "1.1.1.1").ifBlank { "1.1.1.1" },
                    privateKey = getSecret(context, "key_", id),
                    keyPassphrase = getSecret(context, "kp_", id),
                    tls = o.optBoolean("tls", false),
                    sni = o.optString("sni", ""),
                    favorite = o.optBoolean("favorite", false)
                )
            }.getOrNull()
        }
    }

    fun save(context: Context, profile: TunnelProfile) {
        putSecret(context, "pw_", profile.id, profile.password)
        putSecret(context, "key_", profile.id, profile.privateKey)
        putSecret(context, "kp_", profile.id, profile.keyPassphrase)
        // Edit mempertahankan urutan; profil baru ditambahkan di akhir.
        val existing = all(context)
        val list = if (existing.any { it.id == profile.id })
            existing.map { if (it.id == profile.id) profile else it }
        else existing + profile
        writeList(context, list)
    }

    fun delete(context: Context, id: String) {
        SECRET_PREFIXES.forEach { prefix ->
            vault(context)?.edit()?.remove(prefix + id)?.apply()
            prefs(context).edit().remove(prefix + id).apply()
        }
        writeList(context, all(context).filterNot { it.id == id })
        if (activeId(context) == id) setActive(context, null)
    }

    fun active(context: Context): TunnelProfile? {
        val id = activeId(context) ?: return null
        return all(context).firstOrNull { it.id == id }
    }

    fun setActive(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun activeId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)

    private fun writeList(context: Context, list: List<TunnelProfile>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
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
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }
}
