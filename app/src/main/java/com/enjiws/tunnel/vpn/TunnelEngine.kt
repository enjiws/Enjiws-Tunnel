package com.enjiws.tunnel.vpn

import android.os.ParcelFileDescriptor

object TunnelConfig {
    const val MTU = 1400
    const val TUN_IPV4 = "10.255.0.1"
}

/**
 * Siklus satu sesi:
 *  1. connect()  : bangun koneksi ke server SEBELUM TUN dibuat (tidak ada blackhole saat handshake)
 *  2. run(tun)   : teruskan traffic sampai gagal / dibatalkan
 *  3. close()    : bersihkan semuanya (aman dipanggil berkali-kali)
 */
interface TunnelEngine : AutoCloseable {
    /** DNS yang dipakai VPN; tersedia setelah connect(). */
    val dnsServer: String

    suspend fun connect()
    suspend fun run(tun: ParcelFileDescriptor)
    override fun close()
}
