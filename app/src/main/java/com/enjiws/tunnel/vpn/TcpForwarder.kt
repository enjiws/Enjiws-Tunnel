package com.enjiws.tunnel.vpn

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * TCP stack minimal untuk TUN -> upstream.
 *
 * IPv4/TCP saja. UDP hanya dipakai untuk DNS (port 53, dijawab [DnsRelay]);
 * UDP lain dan ICMP dibuang (forwarding UDP penuh = Stage 6).
 *
 * Desain hemat baterai: baca TUN dan baca upstream sama-sama blocking (tanpa polling).
 * Satu-satunya timer adalah sweep 500 ms untuk retransmit + koneksi idle.
 *
 * Catatan kejujuran: ini stack TCP pengguna-ruang (userspace), bukan kernel.
 * Ia menangani SYN/ACK/data/FIN/RST + retransmit dasar. Paket yang hilang di
 * jalur lokal praktis nol, jadi retransmit di sini murni pengaman.
 */
class TcpForwarder(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val upstream: Upstream,
    private val dns: DnsRelay
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = ConcurrentHashMap<FourTuple, TcpConn>()
    private val writeLock = Any()
    private var ipId = Random.nextInt(0, 65_535)

    @Volatile
    private var running = true

    fun start() {
        scope.launch { readLoop() }
        scope.launch { sweepLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        conns.values.forEach { it.close() }
        conns.clear()
    }

    // ------------------------------------------------------------ TUN read loop

    private suspend fun readLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val n = try {
                withContext(Dispatchers.IO) { tunIn.read(buf) }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "TUN read error", e)
                break
            } catch (e: CancellationException) {
                break
            }
            if (n <= 0) continue
            try {
                handlePacket(buf, n)
            } catch (e: Exception) {
                Log.w(TAG, "packet error", e)
            }
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        if (n < 20) return
        val version = (buf[0].toInt() and 0xFF) shr 4
        if (version != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || n < ihl) return
        val totalLen = u16(buf, 2)
        if (totalLen < ihl || totalLen > n) return
        val fragField = u16(buf, 6)
        val mf = fragField and 0x2000 != 0
        val fragOff = fragField and 0x1FFF
        if (mf || fragOff != 0) return // buang paket terfragmentasi
        val proto = buf[9].toInt() and 0xFF
        val srcIp = u32(buf, 12)
        val dstIp = u32(buf, 16)
        when (proto) {
            6 -> handleTcp(buf, ihl, totalLen, srcIp, dstIp)
            17 -> handleUdp(buf, ihl, totalLen, srcIp, dstIp)
            else -> { /* ICMP dll. dibuang */ }
        }
    }

    // ------------------------------------------------------------ TCP

    private fun handleTcp(buf: ByteArray, ihl: Int, totalLen: Int, srcIp: Long, dstIp: Long) {
        val tcpOff = ihl
        if (totalLen < tcpOff + 20) return
        val srcPort = u16(buf, tcpOff)
        val dstPort = u16(buf, tcpOff + 2)
        val seq = u32(buf, tcpOff + 4)
        val ack = u32(buf, tcpOff + 8)
        val dataOff = ((buf[tcpOff + 12].toInt() and 0xFF) shr 4) * 4
        if (dataOff < 20 || totalLen < tcpOff + dataOff) return
        val flags = buf[tcpOff + 13].toInt() and 0xFF
        val window = u16(buf, tcpOff + 14)
        val payloadOff = tcpOff + dataOff
        val payloadLen = totalLen - payloadOff
        val key = FourTuple(srcIp, srcPort, dstIp, dstPort)

        val syn = flags and 0x02 != 0
        val ackFlag = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0

        if (syn && !ackFlag) {
            val existing = conns[key]
            if (existing != null) {
                if (existing.state == TcpState.CONNECTING) sendSynAck(existing)
                return
            }
            val conn = TcpConn(key, srcIp, srcPort, dstIp, dstPort, seq, Random.nextLong(0, 0x1_0000_0000L))
            conns[key] = conn
            sendSynAck(conn)
            scope.launch { connectUpstream(conn) }
            return
        }

        val conn = conns[key] ?: run {
            if (!rst) sendRst(srcIp, srcPort, dstIp, dstPort, seq, ack, ackFlag, payloadLen, syn, fin)
            return
        }
        if (rst) {
            closeConn(conn)
            return
        }
        conn.touch()
        conn.clientWindow = max(1, window)

        if (ackFlag) conn.onAck(ack)

        if (payloadLen > 0) {
            if (seq == conn.clientNext) {
                val data = buf.copyOfRange(payloadOff, payloadOff + payloadLen)
                conn.clientNext += payloadLen
                when (conn.state) {
                    TcpState.CONNECTING -> conn.pending.add(data)
                    TcpState.ESTABLISHED -> conn.writeUpstream(data)
                    else -> { /* menutup: buang */ }
                }
                sendAck(conn)
            } else {
                sendAck(conn) // out-of-order: minta ulang dari clientNext
            }
        }

        if (fin) {
            if (seq + payloadLen == conn.clientNext) {
                conn.clientNext += 1
                sendAck(conn)
                conn.onClientFin()
            }
        }
    }

    private suspend fun connectUpstream(conn: TcpConn) {
        try {
            val stream = withContext(Dispatchers.IO) {
                upstream.openStream(ipToString(conn.dstIp), conn.dstPort)
            }
            if (conn.closed) {
                stream.close()
                return
            }
            conn.upstream = stream
            conn.state = TcpState.ESTABLISHED
            while (true) {
                val d = conn.pending.poll() ?: break
                conn.writeUpstream(d)
            }
            conn.readerJob = scope.launch { readUpstream(conn) }
        } catch (e: Exception) {
            Log.w(TAG, "upstream connect failed ${conn.dstIp}:${conn.dstPort}", e)
            sendRstConn(conn)
            closeConn(conn)
        }
    }

    private suspend fun readUpstream(conn: TcpConn) {
        val stream = conn.upstream ?: return
        val buf = ByteArray(MAX_SEGMENT)
        try {
            while (running && !conn.closed) {
                val n = withContext(Dispatchers.IO) { stream.input.read(buf) }
                if (n < 0) break // EOF
                if (n == 0) continue
                var waited = 0
                while (conn.unackedBytes() >= max(1, conn.clientWindow) && !conn.closed && waited < 10_000) {
                    delay(50)
                    waited += 50
                }
                if (conn.closed) break
                sendData(conn, buf.copyOf(n))
            }
        } catch (e: IOException) {
            Log.w(TAG, "upstream read failed", e)
        } catch (e: CancellationException) {
            // penutupan normal
        }
        if (!conn.closed) {
            // upstream selesai: kirim FIN setelah semua data terkirim
            sendFin(conn)
        }
    }

    // ------------------------------------------------------------ UDP (DNS saja)

    private fun handleUdp(buf: ByteArray, ihl: Int, totalLen: Int, srcIp: Long, dstIp: Long) {
        val udpOff = ihl
        if (totalLen < udpOff + 8) return
        val srcPort = u16(buf, udpOff)
        val dstPort = u16(buf, udpOff + 2)
        if (dstPort != 53) return
        val payloadOff = udpOff + 8
        val payloadLen = totalLen - payloadOff
        if (payloadLen <= 0) return
        val query = buf.copyOfRange(payloadOff, payloadOff + payloadLen)
        scope.launch {
            val resp = runCatching { dns.resolve(query, ipToString(dstIp)) }.getOrNull() ?: return@launch
            sendUdpReply(srcIp, srcPort, dstIp, dstPort, resp)
        }
    }

    private fun sendUdpReply(clientIp: Long, clientPort: Int, dnsIp: Long, dnsPort: Int, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)
        // IP header (src = server DNS, dst = klien)
        pkt[0] = 0x45
        put16(pkt, 2, totalLen)
        put16(pkt, 4, nextIpId())
        put16(pkt, 6, 0x4000) // DF
        pkt[8] = 64 // TTL
        pkt[9] = 17 // UDP
        put32(pkt, 12, dnsIp)
        put32(pkt, 16, clientIp)
        put16(pkt, 10, ipChecksum(pkt, 0, 20)) // checksum header IP (offset 10)
        // UDP header (checksum 0 = tidak dipakai, sah untuk IPv4)
        put16(pkt, 20, dnsPort)
        put16(pkt, 22, clientPort)
        put16(pkt, 24, udpLen)
        put16(pkt, 26, 0)
        payload.copyInto(pkt, 28)
        sendRaw(pkt)
    }

    // ------------------------------------------------------------ kirim paket TCP

    private fun sendSynAck(conn: TcpConn) {
        sendPacket(conn, seq = conn.ourIsn, ack = conn.clientIsn + 1, flags = 0x12, data = null, withMss = true)
    }

    private fun sendAck(conn: TcpConn) {
        sendPacket(conn, seq = conn.ourNext, ack = conn.clientNext, flags = 0x10, data = null, withMss = false)
    }

    private fun sendData(conn: TcpConn, data: ByteArray) {
        if (conn.closed || conn.finSent) return
        val seq = conn.ourNext
        sendPacket(conn, seq = seq, ack = conn.clientNext, flags = 0x18, data = data, withMss = false)
        conn.ourNext += data.size
        conn.unacked.addLast(Seg(seq, data, fin = false, sentAt = SystemClock.elapsedRealtime(), retries = 0))
    }

    private fun sendFin(conn: TcpConn) {
        if (conn.closed || conn.finSent) return
        val seq = conn.ourNext
        sendPacket(conn, seq = seq, ack = conn.clientNext, flags = 0x11, data = null, withMss = false)
        conn.ourNext += 1
        conn.finSent = true
        conn.unacked.addLast(Seg(seq, ByteArray(0), fin = true, sentAt = SystemClock.elapsedRealtime(), retries = 0))
    }

    private fun sendRstConn(conn: TcpConn) {
        sendPacket(conn, seq = conn.ourNext, ack = conn.clientNext, flags = 0x14, data = null, withMss = false)
    }

    /** RST untuk koneksi yang tidak dikenal. */
    private fun sendRst(
        srcIp: Long, srcPort: Int, dstIp: Long, dstPort: Int,
        seq: Long, ack: Long, ackFlag: Boolean, payloadLen: Int, syn: Boolean, fin: Boolean
    ) {
        val pktSeq: Long
        val pktAck: Long
        val flags: Int
        if (ackFlag) {
            pktSeq = ack; pktAck = 0; flags = 0x04
        } else {
            var len = payloadLen.toLong()
            if (syn) len += 1
            if (fin) len += 1
            pktSeq = 0; pktAck = seq + len; flags = 0x14
        }
        val pkt = buildTcpPacket(
            srcIp = dstIp, srcPort = dstPort, dstIp = srcIp, dstPort = srcPort,
            seq = pktSeq, ack = pktAck, flags = flags, data = null, withMss = false
        )
        sendRaw(pkt)
    }

    private fun sendPacket(conn: TcpConn, seq: Long, ack: Long, flags: Int, data: ByteArray?, withMss: Boolean) {
        val pkt = buildTcpPacket(
            srcIp = conn.dstIp, srcPort = conn.dstPort, dstIp = conn.srcIp, dstPort = conn.srcPort,
            seq = seq, ack = ack, flags = flags, data = data, withMss = withMss
        )
        sendRaw(pkt)
    }

    private fun buildTcpPacket(
        srcIp: Long, srcPort: Int, dstIp: Long, dstPort: Int,
        seq: Long, ack: Long, flags: Int, data: ByteArray?, withMss: Boolean
    ): ByteArray {
        val tcpOpts = if (withMss) 4 else 0
        val tcpLen = 20 + tcpOpts + (data?.size ?: 0)
        val totalLen = 20 + tcpLen
        val pkt = ByteArray(totalLen)
        // IP header
        pkt[0] = 0x45
        put16(pkt, 2, totalLen)
        put16(pkt, 4, nextIpId())
        put16(pkt, 6, 0x4000) // DF
        pkt[8] = 64 // TTL
        pkt[9] = 6 // TCP
        put32(pkt, 12, srcIp)
        put32(pkt, 16, dstIp)
        put16(pkt, 10, ipChecksum(pkt, 0, 20)) // checksum header IP (offset 10)
        // TCP header
        val t = 20
        put16(pkt, t, srcPort)
        put16(pkt, t + 2, dstPort)
        put32(pkt, t + 4, seq)
        put32(pkt, t + 8, ack)
        pkt[t + 12] = (((20 + tcpOpts) / 4) shl 4).toByte()
        pkt[t + 13] = flags.toByte()
        put16(pkt, t + 14, 65_535) // window
        put16(pkt, t + 16, 0) // checksum (diisi di bawah)
        put16(pkt, t + 18, 0) // urgent
        if (withMss) {
            pkt[t + 20] = 2 // MSS option
            pkt[t + 21] = 4
            put16(pkt, t + 22, MSS)
        }
        data?.copyInto(pkt, t + 20 + tcpOpts)
        put16(pkt, t + 16, tcpChecksum(pkt, t, tcpLen, srcIp, dstIp))
        return pkt
    }

    private fun sendRaw(pkt: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOut.write(pkt)
                tunOut.flush()
            } catch (e: IOException) {
                Log.w(TAG, "TUN write error", e)
            }
        }
    }

    // ------------------------------------------------------------ sweep: retransmit + idle

    private suspend fun sweepLoop() {
        while (running) {
            delay(500)
            val now = SystemClock.elapsedRealtime()
            val it = conns.entries.iterator()
            while (it.hasNext()) {
                val (key, conn) = it.next()
                if (conn.closed) {
                    it.remove()
                    continue
                }
                // retransmit segmen yang belum di-ACK
                val un = conn.unacked
                var dead = false
                val segIt = un.iterator()
                while (segIt.hasNext()) {
                    val seg = segIt.next()
                    if (now - seg.sentAt >= RETRANSMIT_MS) {
                        if (seg.retries >= MAX_RETRIES) {
                            dead = true
                            break
                        }
                        seg.retries += 1
                        seg.sentAt = now
                        val flags = if (seg.fin) 0x11 else 0x18
                        val data = if (seg.fin) null else seg.data
                        sendPacket(conn, seq = seg.seq, ack = conn.clientNext, flags = flags, data = data, withMss = false)
                    }
                }
                if (dead || now - conn.lastActivity > IDLE_TIMEOUT_MS) {
                    sendRstConn(conn)
                    conn.close()
                    it.remove()
                    continue
                }
                // FIN sudah di-ACK dan klien sudah FIN -> tutup penuh
                if (conn.finSent && conn.finAcked && conn.clientFinReceived) {
                    conn.close()
                    it.remove()
                }
            }
        }
    }

    private fun closeConn(conn: TcpConn) {
        conn.close()
        conns.remove(conn.key)
    }

    // ------------------------------------------------------------ util

    private fun nextIpId(): Int {
        ipId = (ipId + 1) and 0xFFFF
        return ipId
    }

    companion object {
        private const val TAG = "TcpForwarder"
        private const val MSS = 1460
        private const val MAX_SEGMENT = 1360 // MTU 1400 - IP 20 - TCP 20
        private const val RETRANSMIT_MS = 1000L
        private const val MAX_RETRIES = 5
        private const val IDLE_TIMEOUT_MS = 120_000L

        fun ipToString(ip: Long): String =
            "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
}

private data class FourTuple(val srcIp: Long, val srcPort: Int, val dstIp: Long, val dstPort: Int)

private enum class TcpState { CONNECTING, ESTABLISHED, CLOSING }

private class Seg(
    val seq: Long,
    val data: ByteArray,
    val fin: Boolean,
    var sentAt: Long,
    var retries: Int
)

private class TcpConn(
    val key: FourTuple,
    val srcIp: Long,
    val srcPort: Int,
    val dstIp: Long,
    val dstPort: Int,
    val clientIsn: Long,
    val ourIsn: Long
) {
    var state = TcpState.CONNECTING
    var clientNext = clientIsn + 1
    var ourNext = ourIsn + 1
    var clientWindow = 65_535
    var upstream: TunnelStream? = null
    val pending = ArrayDeque<ByteArray>()
    val unacked = ArrayDeque<Seg>()
    var finSent = false
    var finAcked = false
    var clientFinReceived = false
    var readerJob: kotlinx.coroutines.Job? = null
    var lastActivity = SystemClock.elapsedRealtime()
    var closed = false

    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    fun unackedBytes(): Int = unacked.sumOf { it.data.size }

    /** Hapus segmen yang sudah di-ACK oleh klien. */
    fun onAck(ackNum: Long) {
        val it = unacked.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            val end = seg.seq + seg.data.size + if (seg.fin) 1 else 0
            if (end <= ackNum) {
                if (seg.fin) finAcked = true
                it.remove()
            } else {
                break // segmen berurutan; berhenti di yang pertama belum ter-ACK
            }
        }
    }

    fun writeUpstream(data: ByteArray) {
        if (closed) return
        val stream = upstream ?: return
        try {
            stream.output.write(data)
            stream.output.flush()
        } catch (e: IOException) {
            Log.w("TcpForwarder", "upstream write failed", e)
            close()
        }
    }

    /** Klien mengirim FIN: ACK sudah dikirim pemanggil; tutup sisi kirim upstream. */
    fun onClientFin() {
        clientFinReceived = true
        upstream?.shutdownOutput()
    }

    fun close() {
        if (closed) return
        closed = true
        readerJob?.cancel()
        runCatching { upstream?.close() }
    }
}

// ------------------------------------------------------------ helpers bit

private fun u16(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xFF) shl 24) or
        ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or
        (b[off + 3].toLong() and 0xFF)

private fun put16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v ushr 8).toByte()
    b[off + 1] = v.toByte()
}

private fun put32(b: ByteArray, off: Int, v: Long) {
    b[off] = (v ushr 24).toByte()
    b[off + 1] = (v ushr 16).toByte()
    b[off + 2] = (v ushr 8).toByte()
    b[off + 3] = v.toByte()
}

/** Checksum Internet (one's complement) untuk header IP. */
private fun ipChecksum(data: ByteArray, off: Int, len: Int): Int {
    var sum = 0L
    var i = off
    while (i + 1 < off + len) {
        sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2
    }
    if (i < off + len) sum += (data[i].toInt() and 0xFF) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    return sum.inv().toInt() and 0xFFFF
}

/** Checksum TCP dengan pseudo-header. */
private fun tcpChecksum(pkt: ByteArray, tcpOff: Int, tcpLen: Int, srcIp: Long, dstIp: Long): Int {
    var sum = 0L
    sum += (srcIp ushr 16) and 0xFFFF
    sum += srcIp and 0xFFFF
    sum += (dstIp ushr 16) and 0xFFFF
    sum += dstIp and 0xFFFF
    sum += 6 // protokol TCP
    sum += tcpLen
    var i = tcpOff
    val end = tcpOff + tcpLen
    while (i + 1 < end) {
        sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        i += 2
    }
    if (i < end) sum += (pkt[i].toInt() and 0xFF) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    return sum.inv().toInt() and 0xFFFF
}
