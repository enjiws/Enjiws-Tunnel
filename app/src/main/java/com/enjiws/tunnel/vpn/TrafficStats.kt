package com.enjiws.tunnel.vpn

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/** Penghitung byte lewat tunnel. Murni atomic; UI membacanya 1x/detik saat layar terlihat (tanpa flow per paket). */
object TrafficStats {
    private val down = AtomicLong()
    private val up = AtomicLong()

    fun downBytes() = down.get()
    fun upBytes() = up.get()
    fun addDown(n: Long) { down.addAndGet(n) }
    fun addUp(n: Long) { up.addAndGet(n) }
    fun reset() { down.set(0); up.set(0) }
}

/** Pembungkus Upstream: menghitung traffic tanpa menyentuh TcpForwarder. */
class CountingUpstream(private val delegate: Upstream) : Upstream {
    override fun openStream(host: String, port: Int): TunnelStream {
        val s = delegate.openStream(host, port)
        return TunnelStream(
            input = CountingInput(s.input),
            output = CountingOutput(s.output),
            onClose = { s.close() },
            onShutdownOutput = { s.shutdownOutput() }
        )
    }

    override fun close() = delegate.close()
}

private class CountingInput(inner: InputStream) : FilterInputStream(inner) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) TrafficStats.addDown(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) TrafficStats.addDown(n.toLong())
        return n
    }
}

private class CountingOutput(private val inner: OutputStream) : FilterOutputStream(inner) {
    override fun write(b: Int) {
        inner.write(b)
        TrafficStats.addUp(1)
    }

    // FilterOutputStream default menulis byte-per-byte; delegasikan langsung supaya tidak lambat.
    override fun write(b: ByteArray, off: Int, len: Int) {
        inner.write(b, off, len)
        TrafficStats.addUp(len.toLong())
    }

    override fun flush() = inner.flush()
    override fun close() = inner.close()
}
