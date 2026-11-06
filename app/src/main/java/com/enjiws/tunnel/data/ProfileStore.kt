package com.enjiws.tunnel.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Profil (termasuk password) disimpan terenkripsi lewat Android Keystore. */
class ProfileStore private constructor(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "enjiws_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Profile? {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return runCatching { Profile.fromJson(raw) }.getOrNull()?.takeIf { it.isValid }
    }

    fun save(profile: Profile) {
        prefs.edit().putString(KEY_PROFILE, profile.toJson()).apply()
    }

    // Trust-on-first-use untuk host key SSH.
    fun hostKey(host: String, port: Int): String? = prefs.getString("hk:$host:$port", null)

    fun saveHostKey(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString("hk:$host:$port", fingerprint).apply()
    }

    fun forgetHostKey(host: String, port: Int) {
        prefs.edit().remove("hk:$host:$port").apply()
    }

    companion object {
        private const val KEY_PROFILE = "profile"

        @Volatile private var instance: ProfileStore? = null

        fun get(context: Context): ProfileStore =
            instance ?: synchronized(this) {
                instance ?: ProfileStore(context.applicationContext).also { instance = it }
            }
    }
}
