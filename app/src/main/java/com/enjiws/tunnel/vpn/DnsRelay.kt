package com.enjiws.tunnel.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.InputStream

/**
 * DNS lewat tunnel: query UDP yang masuk ke TUN (port 53) dijawab dengan
 * menanyakan server DNS lewat TCP (DNS over TCP adalah standar) melalui upstream.
 * Tanpa ini, DNS bocor keluar tunnel atau gagal total saat traffic ditangkap.
 */
class DnsRelay(private val upstream: Upstream) {

    /** Kirim query DNS mentah lewat TCP ke [dnsServer]:53, kembalikan respons mentah. Null bila gagal. */
    suspend fun resolve(query: ByteArray, dnsServer: String): ByteArray? {
        return withTimeoutOrNull(8_000) {
            withContext(Dispatchers.IO) {
                val stream = upstream.openStream(dnsServer, 53)
                try {
                    val out = stream.output
                    out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
                    out.write(query)
                    out.flush()
                    val inp = stream.input
                    val lenBuf = ByteArray(2)
                    readFully(inp, lenBuf)
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    if (len <= 0 || len > 65_535) return@withContext null
                    val resp = ByteArray(len)
                    readFully(inp, resp)
                    resp
                } finally {
                    stream.close()
                }
            }
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("DNS stream ended early")
            off += n
        }
    }
}
