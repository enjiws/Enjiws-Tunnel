package com.enjiws.tunnel.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress

/**
 * Konfigurasi relay UDP (berjalan di server SSH). Relay dijalankan di VPS
 * (server/udp-relay.py) dan hanya bisa dijangkau lewat SSH (default 127.0.0.1:7300).
 */
data class UdpConfig(val relayHost: String, val relayPort: Int)

/**
 * UDP-over-TCP: satu stream TCP per asosiasi UDP SOCKS5.
 *
 * SSH hanya meneruskan TCP, jadi paket UDP dibungkus jadi frame dan dikirim lewat
 * channel direct-tcpip ke relay di server, yang meneruskannya sebagai UDP asli
 * dan mengembalikan balasannya lewat stream yang sama.
 *
 * Framing (kedua arah): [panjang 4 byte big-endian][payload].
 * Payload: [family 1 byte][alamat][port 2 byte big-endian][data UDP].
 *   family 4 = IPv4 (4 byte alamat), 6 = IPv6 (16 byte), 3 = domain (1 byte panjang + nama).
 * Balasan dari relay selalu membawa alamat IP (family 4/6).
 * Harus sama persis dengan server/udp-relay.py.
 *
 * Hemat baterai: pembacaan stream blocking (tanpa polling); tidak ada timer.
 * UDP memang tidak dijamin (loss-tolerant): paket yang gagal dikirim dibuang,
 * aplikasi akan mengirim ulang sendiri.
 */
class UdpOverTcp(
    private val dialer: Dialer,
    private val relayHost: String,
    private val relayPort: Int,
    private val scope: CoroutineScope,
    private val onReply: (src: InetAddress, srcPort: Int, data: ByteArray) -> Unit
) {
    private val lock = Any()
    private var conn: RemoteConn? = null
    private var readerJob: Job? = null

    @Volatile
    private var closed = false

    /** Kirim satu paket UDP. Stream TCP dibuka saat pertama dipakai. False bila gagal (paket dibuang). */
    fun send(dst: InetAddress, dstPort: Int, data: ByteArray): Boolean {
        val family: Byte = if (dst.address.size == 4) 4 else 6
        return sendRaw(family, dst.address, dstPort, data)
    }

    /** Kirim satu paket UDP ke hostname (relay yang me-resolve). */
    fun sendHost(hostname: String, dstPort: Int, data: ByteArray): Boolean {
        val nameBytes = hostname.toByteArray(Charsets.US_ASCII)
        if (nameBytes.isEmpty() || nameBytes.size > 255) return false
        val field = byteArrayOf(nameBytes.size.toByte()) + nameBytes
        return sendRaw(3, field, dstPort, data)
    }

    private fun sendRaw(family: Byte, addrField: ByteArray, dstPort: Int, data: ByteArray): Boolean {
        if (closed) return false
        val c: RemoteConn = synchronized(lock) {
            conn ?: run {
                val nc = try {
                    dialer.dial(relayHost, relayPort)
                } catch (e: Exception) {
                    return false
                }
                conn = nc
                readerJob = scope.launch { readLoop(nc) }
                nc
            }
        }
        return try {
            val frame = buildFrame(family, addrField, dstPort, data)
            synchronized(lock) {
                c.output.write(frame)
                c.output.flush()
            }
            true
        } catch (e: IOException) {
            dropConnection()
            false
        }
    }

    private suspend fun readLoop(c: RemoteConn) {
        val din = DataInputStream(c.input)
        try {
            while (!closed) {
                val len = din.readInt()
                if (len <= 0 || len > MAX_FRAME) break
                val payload = ByteArray(len)
                din.readFully(payload)
                parseFrame(payload)?.let { (src, srcPort, data) -> onReply(src, srcPort, data) }
            }
        } catch (_: IOException) {
            // stream ditutup
        } catch (_: CancellationException) {
            // penutupan normal
        } finally {
            dropConnection()
        }
    }

    /** Putuskan stream tapi izinkan dial ulang saat paket berikutnya datang. */
    private fun dropConnection() {
        readerJob?.cancel()
        synchronized(lock) {
            runCatching { conn?.close() }
            conn = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        dropConnection()
    }

    companion object {
        const val MAX_FRAME = 65_554 // payload UDP maks 65535 + header 19

        fun buildFrame(family: Byte, addrField: ByteArray, dstPort: Int, data: ByteArray): ByteArray {
            val payloadLen = 1 + addrField.size + 2 + data.size
            val out = ByteArray(4 + payloadLen)
            out[0] = (payloadLen ushr 24).toByte()
            out[1] = (payloadLen ushr 16).toByte()
            out[2] = (payloadLen ushr 8).toByte()
            out[3] = payloadLen.toByte()
            out[4] = family
            addrField.copyInto(out, 5)
            out[5 + addrField.size] = (dstPort ushr 8).toByte()
            out[6 + addrField.size] = dstPort.toByte()
            data.copyInto(out, 7 + addrField.size)
            return out
        }

        /** Urai frame balasan (selalu alamat IP). Null bila rusak. */
        fun parseFrame(payload: ByteArray): Triple<InetAddress, Int, ByteArray>? {
            if (payload.size < 1 + 4 + 2) return null
            val family = payload[0].toInt()
            val addrLen = when (family) {
                4 -> 4
                6 -> 16
                else -> return null
            }
            if (payload.size < 1 + addrLen + 2) return null
            val addr = payload.copyOfRange(1, 1 + addrLen)
            val port = ((payload[1 + addrLen].toInt() and 0xFF) shl 8) or
                (payload[2 + addrLen].toInt() and 0xFF)
            val data = payload.copyOfRange(3 + addrLen, payload.size)
            return Triple(InetAddress.getByAddress(addr), port, data)
        }
    }
}
