package com.enjiws.tunnel.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Satu koneksi TCP keluar lewat SSH (direct-tcpip). */
interface RemoteConn : Closeable {
    val input: InputStream
    val output: OutputStream
}

fun interface Dialer {
    fun dial(host: String, port: Int): RemoteConn
}

/** Header paket UDP SOCKS5 yang sudah diurai. */
private data class SocksUdp(
    val host: String,
    val address: InetAddress?, // null bila tujuan berupa domain (ATYP 3)
    val port: Int,
    val header: ByteArray, // byte mentah RSV..PORT (dipakai ulang untuk balasan DNS)
    val data: ByteArray
)

/**
 * SOCKS5 server lokal (127.0.0.1) yang diumpankan ke tun2socks.
 *  - CONNECT: diteruskan lewat SSH
 *  - UDP ASSOCIATE: DNS (port 53) diubah jadi DNS-over-TCP lewat SSH;
 *    UDP lain diteruskan lewat relay UDP-over-TCP (butuh relay di server,
 *    lihat server/udp-relay.py) bila udpConfig diisi, kalau tidak dibuang.
 */
class SocksServer(private val dialer: Dialer, private val udpConfig: UdpConfig? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(256)
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    fun start(): Int {
        val s = ServerSocket(0, 128, loopback)
        server = s
        scope.launch {
            while (isActive) {
                val c = try { s.accept() } catch (_: IOException) { break }
                launch { limiter.withPermit { handle(c) } }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        scope.cancel()
    }

    // ------------------------------------------------------------------ TCP

    private suspend fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val ins = DataInputStream(client.getInputStream())
            val outs = client.getOutputStream()

            // greeting
            if (ins.readUnsignedByte() != 5) return
            val nMethods = ins.readUnsignedByte()
            ByteArray(nMethods).also { ins.readFully(it) }
            outs.write(byteArrayOf(5, 0)); outs.flush()

            // request
            if (ins.readUnsignedByte() != 5) return
            val cmd = ins.readUnsignedByte()
            ins.readUnsignedByte() // rsv
            val host = readAddress(ins)
            val port = ins.readUnsignedShort()

            when (cmd) {
                1 -> handleConnect(client, ins, outs, host, port)
                3 -> handleUdpAssociate(client, ins, outs)
                else -> reply(outs, 7)
            }
        } catch (_: IOException) {
            // klien menutup koneksi
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun handleConnect(
        client: Socket, ins: InputStream, outs: OutputStream, host: String, port: Int
    ) {
        val remote = try {
            dialer.dial(host, port)
        } catch (e: Exception) {
            reply(outs, 5)
            return
        }
        try {
            reply(outs, 0)
            val closeAll = {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
            val up = scope.launch { pump(ins, remote.output); closeAll() }
            val down = scope.launch { pump(remote.input, outs); closeAll() }
            joinAll(up, down)
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    // ------------------------------------------------------------------ UDP

    private suspend fun handleUdpAssociate(client: Socket, ins: InputStream, outs: OutputStream) {
        val udp = DatagramSocket(0, loopback)
        // hev memakai satu socket UDP untuk seluruh tunnel; balasan relay dikirim ke sana.
        val hevTarget = AtomicReference<Pair<InetAddress, Int>?>(null)
        var udpOverTcp: UdpOverTcp? = null
        try {
            reply(outs, 0, loopback.address, udp.localPort)
            udpOverTcp = udpConfig?.let { cfg ->
                UdpOverTcp(dialer, cfg.relayHost, cfg.relayPort, scope) { src, srcPort, data ->
                    val target = hevTarget.get() ?: return@UdpOverTcp
                    val packet = buildSocksUdpReply(src, srcPort, data)
                    runCatching {
                        udp.send(DatagramPacket(packet, packet.size, target.first, target.second))
                    }
                }
            }
            val relay = scope.launch { udpLoop(udp, udpOverTcp, hevTarget) }
            // Asosiasi hidup selama koneksi TCP kontrol terbuka.
            try { while (ins.read() >= 0) { /* abaikan */ } } catch (_: IOException) {}
            relay.cancel()
        } finally {
            udpOverTcp?.close()
            udp.close()
        }
    }

    private fun udpLoop(
        udp: DatagramSocket,
        udpOverTcp: UdpOverTcp?,
        hevTarget: AtomicReference<Pair<InetAddress, Int>?>
    ) {
        val buf = ByteArray(65_535)
        while (!udp.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try { udp.receive(pkt) } catch (_: IOException) { break }
            hevTarget.set(pkt.address to pkt.port)
            val data = pkt.data.copyOf(pkt.length)
            val parsed = parseSocksUdp(data) ?: continue
            if (parsed.port == 53) {
                scope.launch { relayDns(udp, pkt.address, pkt.port, parsed) }
            } else if (udpOverTcp != null) {
                scope.launch {
                    val ok = if (parsed.address != null) {
                        udpOverTcp.send(parsed.address, parsed.port, parsed.data)
                    } else {
                        udpOverTcp.sendHost(parsed.host, parsed.port, parsed.data)
                    }
                    if (!ok) {
                        // relay mati / belum jalan: paket dibuang (UDP memang loss-tolerant)
                    }
                }
            }
            // else: UDP non-DNS dibuang (relay tidak aktif)
        }
    }

    private fun relayDns(udp: DatagramSocket, src: InetAddress, srcPort: Int, parsed: SocksUdp) {
        try {
            val answer = dnsOverTcp(parsed.host, parsed.data) ?: return
            val resp = parsed.header + answer
            udp.send(DatagramPacket(resp, resp.size, src, srcPort))
        } catch (_: Exception) {
        }
    }

    private fun dnsOverTcp(host: String, query: ByteArray): ByteArray? {
        val conn = dialer.dial(host, 53)
        val watchdog = scope.launch { delay(5_000); runCatching { conn.close() } }
        try {
            val out = conn.output
            out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val din = DataInputStream(conn.input)
            val len = din.readUnsignedShort()
            val body = ByteArray(len)
            din.readFully(body)
            return body
        } catch (_: IOException) {
            return null
        } finally {
            watchdog.cancel()
            runCatching { conn.close() }
        }
    }

    // ------------------------------------------------------------------ util

    /** Urai header UDP SOCKS5 (RSV + FRAG + ATYP + alamat + port). Null bila rusak. */
    private fun parseSocksUdp(data: ByteArray): SocksUdp? {
        try {
            if (data.size < 10) return null
            if (data[2].toInt() != 0) return null // fragmentasi tidak didukung
            var i = 3
            val host: String
            val address: InetAddress?
            when (data[i++].toInt()) {
                1 -> {
                    if (i + 4 > data.size) return null
                    address = InetAddress.getByAddress(data.copyOfRange(i, i + 4))
                    host = address.hostAddress.orEmpty()
                    i += 4
                }
                4 -> {
                    if (i + 16 > data.size) return null
                    address = InetAddress.getByAddress(data.copyOfRange(i, i + 16))
                    host = address.hostAddress.orEmpty()
                    i += 16
                }
                3 -> {
                    if (i + 1 > data.size) return null
                    val n = data[i++].toInt() and 0xFF
                    if (i + n > data.size) return null
                    host = String(data, i, n, Charsets.US_ASCII)
                    i += n
                    address = null
                }
                else -> return null
            }
            if (i + 2 > data.size) return null
            val port = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            return SocksUdp(host, address, port, data.copyOfRange(0, i), data.copyOfRange(i, data.size))
        } catch (_: Exception) {
            return null
        }
    }

    /** Bangun paket UDP SOCKS5 untuk balasan (header dari alamat sumber + data). */
    private fun buildSocksUdpReply(src: InetAddress, srcPort: Int, data: ByteArray): ByteArray {
        val addr = src.address
        val atyp: Byte = if (addr.size == 4) 1 else 4
        val out = ByteArray(3 + 1 + addr.size + 2 + data.size)
        out[0] = 0 // RSV
        out[1] = 0 // RSV
        out[2] = 0 // FRAG
        out[3] = atyp
        addr.copyInto(out, 4)
        val p = 4 + addr.size
        out[p] = (srcPort ushr 8).toByte()
        out[p + 1] = srcPort.toByte()
        data.copyInto(out, p + 2)
        return out
    }

    private fun readAddress(ins: DataInputStream): String = when (ins.readUnsignedByte()) {
        1 -> ByteArray(4).also { ins.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress.orEmpty() }
        4 -> ByteArray(16).also { ins.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress.orEmpty() }
        3 -> {
            val n = ins.readUnsignedByte()
            ByteArray(n).also { ins.readFully(it) }.toString(Charsets.US_ASCII)
        }
        else -> throw IOException("Unsupported address type")
    }

    private fun reply(
        out: OutputStream, code: Int,
        addr: ByteArray = byteArrayOf(0, 0, 0, 0), port: Int = 0
    ) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1))
        out.write(addr)
        out.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
    }
}
