package com.enjiws.tunnel.profiles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-defined connection profile. Secrets are encrypted before persistence. */
data class ProtocolProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val proxyHost: String,
    val proxyPort: Int,
    val sni: String,
    val payload: String
)

object ProtocolProfileStore {
    private const val PREFS = "enjiws_protocol_profiles"
    private const val KEY_DATA = "encrypted_profiles"
    private const val KEY_ALIAS = "enjiws_tunnel_profile_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Synchronized
    fun all(context: Context): List<ProtocolProfile> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null) ?: return emptyList()
        return runCatching {
            val packed = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(packed.size > 12) { "Encrypted profile data is invalid" }
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                ProtocolProfile(
                    id = o.getString("id"), name = o.getString("name"),
                    protocol = o.getString("protocol"), host = o.getString("host"),
                    port = o.getInt("port"), username = o.optString("username"),
                    password = o.optString("password"), proxyHost = o.optString("proxyHost"),
                    proxyPort = o.optInt("proxyPort"), sni = o.optString("sni"),
                    payload = o.optString("payload")
                )
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(context: Context, profile: ProtocolProfile) {
        val updated = all(context).filterNot { it.id == profile.id } + profile
        val json = JSONArray()
        updated.forEach { p -> json.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("protocol", p.protocol)
            put("host", p.host); put("port", p.port); put("username", p.username)
            put("password", p.password); put("proxyHost", p.proxyHost)
            put("proxyPort", p.proxyPort); put("sni", p.sni); put("payload", p.payload)
        }) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, encoded).commit()) { "Could not save encrypted profile" }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val updated = all(context).filterNot { it.id == id }
        if (updated.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DATA).apply()
        } else {
            // Re-encrypt through the same save path using a temporary replacement set.
            val json = JSONArray()
            updated.forEach { p -> json.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("protocol", p.protocol)
                put("host", p.host); put("port", p.port); put("username", p.username)
                put("password", p.password); put("proxyHost", p.proxyHost)
                put("proxyPort", p.proxyPort); put("sni", p.sni); put("payload", p.payload)
            }) }
            val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encoded = android.util.Base64.encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray()), android.util.Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DATA, encoded).apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
