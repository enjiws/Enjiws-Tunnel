package com.enjiws.tunnel.vpn

import java.io.InputStream
import java.io.OutputStream

/**
 * Satu aliran byte lewat tunnel menuju host:port tujuan.
 * Dibuka per koneksi TCP oleh [Upstream.openStream]; ditutup saat koneksi selesai.
 */
class TunnelStream(
    val input: InputStream,
    val output: OutputStream,
    private val onClose: () -> Unit,
    private val onShutdownOutput: () -> Unit = {}
) {
    fun close() = runCatching { onClose() }

    /** Beri tahu ujung jauh bahwa kita tidak akan mengirim lagi (half-close). */
    fun shutdownOutput() = runCatching { onShutdownOutput() }
}

/**
 * Penghubung ke server tunnel. Implementasi: SSH (direct-tcpip) dan HTTP CONNECT.
 * [openStream] boleh blocking (dipanggil dari Dispatchers.IO); lempar exception bila gagal.
 */
interface Upstream {
    @Throws(Exception::class)
    fun openStream(host: String, port: Int): TunnelStream

    fun close()
}
