package com.enjiws.tunnel.vpn

import android.os.ParcelFileDescriptor
import android.util.Base64
import com.enjiws.tunnel.profiles.TunnelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Engine HTTP CONNECT: tiap koneksi TCP dari TUN dibuka sebagai
 * `CONNECT host:port HTTP/1.1` ke proxy HTTP. Mendukung Basic auth.
 * (Versi TLS/SNI dari proxy = Stage 3.)
 */
class HttpConnectEngine(private val profile: TunnelProfile) : TunnelEngine {
    override val capturesTraffic = true
    override val dnsServer: String get() = profile.dns
    private val raw = HttpUpstream(profile)
    private val upstream: Upstream = CountingUpstream(raw)

    override fun close() {
        upstream.close()
    }

    /**
     * Pastikan proxy menjawab CONNECT (termasuk auth bila ada) sebelum TUN dibuat.
     * Dipanggil service sebelum buildTun; gagal di sini = tidak pernah CONNECTED.
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        raw.warmUp()
    }

    override suspend fun run(tun: ParcelFileDescriptor) {
        val tunIn = FileInputStream(tun.fileDescriptor)
        val tunOut = FileOutputStream(tun.fileDescriptor)
        val forwarder = TcpForwarder(tunIn, tunOut, upstream, DnsRelay(upstream))
        try {
            // connect() sudah membuktikan proxy menjawab sebelum TUN dibuat.
            forwarder.start()
            awaitCancellation()
        } finally {
            forwarder.stop()
            runCatching { tunIn.close() }
            runCatching { tunOut.close() }
            upstream.close()
        }
    }
}

class HttpUpstream(private val profile: TunnelProfile) : Upstream {

    /** Tes satu CONNECT ke 1.1.1.1:80 — memastikan proxy hidup tanpa traffic sungguhan. */
    fun warmUp() {
        openStream("1.1.1.1", 80).close()
    }

    override fun openStream(host: String, port: Int): TunnelStream {
        val raw = Socket()
        raw.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = CONNECT_TIMEOUT_MS
        // HTTPS proxy: bungkus TLS dulu (sertifikat diverifikasi), baru kirim CONNECT.
        val socket: Socket = try {
            if (profile.tls) TlsUtil.wrap(raw, profile.tlsHost, profile.port, CONNECT_TIMEOUT_MS) else raw
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        try {
            val out = socket.getOutputStream()
            val req = StringBuilder()
            req.append("CONNECT $host:$port HTTP/1.1\r\n")
            req.append("Host: $host:$port\r\n")
            if (profile.username.isNotBlank()) {
                val cred = Base64.encodeToString(
                    "${profile.username}:${profile.password}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                req.append("Proxy-Authorization: Basic $cred\r\n")
            }
            req.append("\r\n")
            out.write(req.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            val inp = socket.getInputStream()
            val header = readHeader(inp)
            if (!header.startsWith("HTTP/1.1 200") && !header.startsWith("HTTP/1.0 200")) {
                throw IOException("Proxy menolak CONNECT: ${header.lineSequence().firstOrNull().orEmpty()}")
            }
            socket.soTimeout = 0 // data mengalir tanpa timeout baca
            return TunnelStream(
                inp,
                out,
                onClose = { runCatching { socket.close() } },
                onShutdownOutput = { runCatching { socket.shutdownOutput() } }
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    override fun close() {
        // tidak ada state persisten; socket ditutup per stream
    }

    /** Baca header respons sampai \r\n\r\n (maks 8 KB). */
    private fun readHeader(inp: InputStream): String {
        val buf = ByteArrayOutputStream()
        val last4 = IntArray(4)
        var count = 0
        while (count < 8192) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b)
            last4[0] = last4[1]
            last4[1] = last4[2]
            last4[2] = last4[3]
            last4[3] = b
            count++
            if (count >= 4 && last4[0] == '\r'.code && last4[1] == '\n'.code &&
                last4[2] == '\r'.code && last4[3] == '\n'.code
            ) break
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
