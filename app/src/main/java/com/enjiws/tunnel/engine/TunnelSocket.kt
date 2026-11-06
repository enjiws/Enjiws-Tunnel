package com.enjiws.tunnel.engine

import android.annotation.SuppressLint
import com.enjiws.tunnel.data.Profile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** SocketFactory untuk sshj: setiap socket yang dibuat menjalankan rantai TCP -> TLS -> payload. */
class TunnelSocketFactory(private val profile: Profile) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(profile)
    override fun createSocket(host: String?, port: Int): Socket = unsupported()
    override fun createSocket(host: String?, port: Int, l: InetAddress?, lp: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
    override fun createSocket(a: InetAddress?, p: Int, l: InetAddress?, lp: Int): Socket = unsupported()
    private fun unsupported(): Socket = throw UnsupportedOperationException("Use createSocket()")
}

/**
 * Socket "palsu" yang membungkus socket asli. connect() melakukan:
 *  1. TCP ke proxy (kalau diisi) atau langsung ke server SSH
 *  2. TLS dengan SNI custom (kalau aktif)
 *  3. kirim payload HTTP dan tunggu respons 2xx/101 (kalau diisi)
 * Setelah itu stream-nya dipakai SSH seperti biasa.
 */
class TunnelSocket(private val p: Profile) : Socket() {

    @Volatile private var real: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val viaProxy = p.proxyHost.isNotBlank()
        val dialHost = if (viaProxy) p.proxyHost else p.host
        val dialPort = if (viaProxy) p.proxyPort else p.port

        val tcp = Socket()
        real = tcp
        tcp.tcpNoDelay = true
        tcp.keepAlive = true
        tcp.connect(InetSocketAddress(dialHost, dialPort), if (timeout > 0) timeout else 15_000)

        var sock: Socket = tcp
        if (p.tlsEnabled) {
            sock = wrapTls(tcp, p.sni.ifBlank { p.host }, dialPort)
            real = sock
        }
        val ins = sock.getInputStream()
        val outs = sock.getOutputStream()

        if (p.payload.isNotBlank()) {
            outs.write(renderPayload().toByteArray(Charsets.ISO_8859_1))
            outs.flush()
            checkHttpResponse(ins)
        }
        input = ins
        output = outs
    }

    private fun wrapTls(tcp: Socket, sniHost: String, port: Int): SSLSocket {
        val factory: SSLSocketFactory =
            if (p.tlsInsecure) insecureFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(tcp, sniHost, port, true) as SSLSocket
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(sniHost))
        if (!p.tlsInsecure) params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    private fun renderPayload(): String =
        p.payload
            .replace("[host_port]", "${p.host}:${p.port}")
            .replace("[host]", p.host)
            .replace("[port]", p.port.toString())
            .replace("[protocol]", "HTTP/1.1")
            .replace("[crlf]", "\n")
            .replace("[cr]", "\n")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")

    /** Baca header respons byte per byte supaya tidak ada byte SSH yang ikut terbaca. */
    private fun checkHttpResponse(ins: InputStream) {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = ins.read()
            if (b < 0) throw IOException("Connection closed during payload handshake")
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val statusLine = sb.lineSequence().firstOrNull().orEmpty()
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
        if (code == null || !(code in 200..299 || code == 101)) {
            throw IOException("Payload rejected: ${statusLine.take(80)}")
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun insecureFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }.socketFactory
    }

    // ---- delegasi ke socket asli ----
    override fun getInputStream(): InputStream = input ?: throw SocketException("Not connected")
    override fun getOutputStream(): OutputStream = output ?: throw SocketException("Not connected")
    override fun isConnected(): Boolean = input != null && real?.isConnected == true
    override fun isClosed(): Boolean = real?.isClosed ?: super.isClosed()
    override fun setSoTimeout(timeout: Int) { real?.soTimeout = timeout }
    override fun getSoTimeout(): Int = real?.soTimeout ?: 0
    override fun setTcpNoDelay(on: Boolean) { real?.tcpNoDelay = on }
    override fun setKeepAlive(on: Boolean) { real?.keepAlive = on }
    override fun getInetAddress(): InetAddress? = real?.inetAddress
    override fun getPort(): Int = real?.port ?: 0
    override fun shutdownInput() { real?.shutdownInput() }
    override fun shutdownOutput() { real?.shutdownOutput() }

    @Synchronized
    override fun close() {
        runCatching { real?.close() }
        super.close()
    }
}
