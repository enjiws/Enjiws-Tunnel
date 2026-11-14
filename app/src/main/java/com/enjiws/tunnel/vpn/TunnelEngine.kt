package com.enjiws.tunnel.vpn

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.awaitCancellation

object TunnelConfig {
    const val MTU = 1400
    const val TUN_IPV4 = "10.255.0.1"
}

/**
 * Siklus satu sesi:
 *  1. connect()  : bangun koneksi ke server SEBELUM TUN dibuat (tidak ada blackhole saat handshake)
 *  2. run(tun)   : teruskan traffic sampai gagal / dibatalkan
 *  3. close()    : bersihkan semuanya (aman dipanggil berkali-kali)
 *
 * Rekonsiliasi (fix build update2-jsch): interface ini dipakai dua generasi engine.
 * Engine baru (SshEngine/HttpConnectEngine) menyambung di dalam run(), jadi
 * connect() punya implementasi default kosong. capturesTraffic punya default
 * false (engine lama V0.3 mewarisinya; engine baru meng-override jadi true).
 */
interface TunnelEngine : AutoCloseable {
    /** True bila engine menangkap semua traffic (butuh route 0.0.0.0/0 + split tunneling). */
    val capturesTraffic: Boolean
        get() = false

    /** DNS yang dipakai VPN; tersedia setelah connect(). */
    val dnsServer: String

    /**
     * Autentikasi + warmUp (buka satu channel percobaan). Service WAJIB memanggil
     * ini sebelum TUN dibuat; gagal = status tidak pernah CONNECTED. Default kosong
     * untuk engine yang tidak perlu autentikasi (IdleEngine).
     */
    suspend fun connect() {}

    suspend fun run(tun: ParcelFileDescriptor)
    override fun close()
}

/** Engine diam: tanpa profil (mode test, internet tidak terganggu). */
class IdleEngine : TunnelEngine {
    override val dnsServer: String = "1.1.1.1"

    override suspend fun run(tun: ParcelFileDescriptor) {
        awaitCancellation()
    }

    override fun close() {}
}
