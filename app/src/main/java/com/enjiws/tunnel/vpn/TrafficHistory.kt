package com.enjiws.tunnel.vpn

import android.content.Context
import java.util.Calendar
import java.util.Locale

/** Riwayat pemakaian harian (download,upload) di SharedPreferences. Di-flush tiap 60 dtk + saat stop. */
object TrafficHistory {
    private val lock = Any()
    private var lastDown = 0L
    private var lastUp = 0L

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("tunnel_usage", Context.MODE_PRIVATE)

    private fun dayKey(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return String.format(
            Locale.US, "d_%04d%02d%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun read(c: Context, key: String): Pair<Long, Long> {
        val parts = prefs(c).getString(key, null)?.split(',') ?: return 0L to 0L
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun flush(c: Context) {
        synchronized(lock) { flushLocked(c) }
    }

    /** Simpan sisa traffic lalu nolkan penghitung sesi. */
    fun flushAndReset(c: Context) {
        synchronized(lock) {
            flushLocked(c)
            TrafficStats.reset()
            lastDown = 0L
            lastUp = 0L
        }
    }

    private fun flushLocked(c: Context) {
        val d = TrafficStats.downBytes()
        val u = TrafficStats.upBytes()
        val dd = (d - lastDown).coerceAtLeast(0L)
        val uu = (u - lastUp).coerceAtLeast(0L)
        lastDown = d
        lastUp = u
        if (dd == 0L && uu == 0L) return
        val key = dayKey(0)
        val cur = read(c, key)
        prefs(c).edit().putString(key, "${cur.first + dd},${cur.second + uu}").apply()
    }

    /** Total byte (down+up) untuk [days] hari terakhir, termasuk yang belum di-flush. */
    fun total(c: Context, days: Int): Long = synchronized(lock) {
        var sum = 0L
        for (i in 0 until days) {
            val v = read(c, dayKey(i))
            sum += v.first + v.second
        }
        sum + (TrafficStats.downBytes() - lastDown).coerceAtLeast(0L) +
            (TrafficStats.upBytes() - lastUp).coerceAtLeast(0L)
    }
}
