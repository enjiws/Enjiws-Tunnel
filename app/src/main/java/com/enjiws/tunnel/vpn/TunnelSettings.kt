package com.enjiws.tunnel.vpn

import android.content.Context

enum class SplitMode { ALL, ONLY, EXCEPT }

object TunnelSettings {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("tunnel_settings", Context.MODE_PRIVATE)

    fun splitMode(c: Context): SplitMode =
        runCatching { SplitMode.valueOf(prefs(c).getString("split_mode", "ALL") ?: "ALL") }
            .getOrDefault(SplitMode.ALL)

    fun setSplitMode(c: Context, mode: SplitMode) {
        prefs(c).edit().putString("split_mode", mode.name).apply()
    }

    fun splitApps(c: Context): Set<String> =
        prefs(c).getStringSet("split_apps", emptySet())?.toSet() ?: emptySet()

    fun setSplitApps(c: Context, apps: Set<String>) {
        prefs(c).edit().putStringSet("split_apps", HashSet(apps)).apply()
    }

    fun autoReconnect(c: Context): Boolean = prefs(c).getBoolean("auto_reconnect", true)
    fun setAutoReconnect(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto_reconnect", on).apply()
    }

    /** Putuskan tunnel otomatis setelah N menit (0 = mati). */
    fun autoStopMinutes(c: Context): Int = prefs(c).getInt("auto_stop_min", 0)
    fun setAutoStopMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt("auto_stop_min", minutes).apply()
    }

    /** Pindah ke profil berikutnya setelah beberapa kali gagal berturut-turut. */
    fun failover(c: Context): Boolean = prefs(c).getBoolean("failover", false)
    fun setFailover(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("failover", on).apply()
    }
}
