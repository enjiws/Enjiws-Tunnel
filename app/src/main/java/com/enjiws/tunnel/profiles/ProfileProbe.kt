package com.enjiws.tunnel.profiles

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object ProfileProbe {
    /** Latensi TCP connect ke server (ms), ambil yang terbaik dari 2 percobaan. Null = tidak terjangkau. */
    suspend fun ping(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
        var best = Long.MAX_VALUE
        repeat(2) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 4_000) }
            }.isSuccess
            if (ok) best = minOf(best, SystemClock.elapsedRealtime() - t0)
        }
        if (best == Long.MAX_VALUE) null else best
    }
}
