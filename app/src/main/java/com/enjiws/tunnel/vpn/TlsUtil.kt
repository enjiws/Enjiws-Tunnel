package com.enjiws.tunnel.vpn

import com.github.mwiede.jsch.SocketFactory as JschSocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS ke server tunnel milik pengguna (SSH-over-TLS / HTTPS proxy), mirip stunnel.
 * Sertifikat server SELALU diverifikasi terhadap [host]; tidak ada opsi "trust all".
 */
object TlsUtil {
    private val ipLiteral = Regex("^[0-9.]+$|.*:.*")

    fun wrap(raw: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        try {
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (!ipLiteral.matches(host)) params.serverNames = listOf<SNIServerName>(SNIHostName(host))
            ssl.sslParameters = params
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                throw SSLPeerUnverifiedException("Sertifikat server tidak cocok dengan $host")
            }
            return ssl
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw e
        }
    }
}

/** SocketFactory JSch: membungkus koneksi SSH dalam TLS. */
class TlsSocketFactory(private val tlsHost: String) : JschSocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), 15_000)
            return TlsUtil.wrap(raw, tlsHost, port, 15_000)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
