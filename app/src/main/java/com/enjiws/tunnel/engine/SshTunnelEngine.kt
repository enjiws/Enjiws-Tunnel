package com.enjiws.tunnel.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import com.enjiws.tunnel.data.Profile
import com.enjiws.tunnel.data.ProfileStore
import com.enjiws.tunnel.vpn.TunnelConfig
import com.enjiws.tunnel.vpn.TunnelEngine
import hev.htproxy.TProxyService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security

/**
 * SSH (opsional lewat TLS/SNI + payload HTTP) -> SOCKS5 lokal -> tun2socks (hev-socks5-tunnel).
 */
class SshTunnelEngine(context: Context) : TunnelEngine {

    private val appContext = context.applicationContext
    private val store = ProfileStore.get(appContext)

    @Volatile private var client: SSHClient? = null
    private var socks: SocksServer? = null
    private val lost = CompletableDeferred<Unit>()

    override var dnsServer: String = "1.1.1.1"
        private set

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val profile = store.load() ?: throw IOException("No profile configured")
        dnsServer = profile.dns
        val ssh = openSsh(profile)

        val server = SocksServer { host, port -> SshRemoteConn(ssh.newDirectConnection(host, port)) }
        server.start()
        socks = server
        Unit
    }

    override suspend fun run(tun: ParcelFileDescriptor) {
        val server = socks ?: throw IOException("Engine not connected")
        val cfg = File(appContext.cacheDir, "hev.yml")
        cfg.writeText(hevConfig(server.port))
        TProxyService.TProxyStartService(cfg.absolutePath, tun.fd)
        lost.await() // selesai (dengan exception) kalau SSH putus
    }

    override fun close() {
        runCatching { TProxyService.TProxyStopService() }
        runCatching { socks?.stop() }
        runCatching { client?.close() } // menutup socket => membuka blokir connect() yang tertahan
        socks = null
        client = null
    }

    // ------------------------------------------------------------------ SSH

    private fun openSsh(p: Profile): SSHClient {
        installBouncyCastle()

        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val ssh = SSHClient(config)
        client = ssh // supaya close() bisa menutupnya walau connect belum selesai

        ssh.connectTimeout = 15_000
        ssh.socketFactory = TunnelSocketFactory(p)
        ssh.transport.setDisconnectListener(DisconnectListener { _, message ->
            lost.completeExceptionally(IOException("SSH disconnected: $message"))
        })
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                val known = store.hostKey(p.host, p.port)
                if (known == null) {
                    store.saveHostKey(p.host, p.port, fp) // trust on first use
                    return true
                }
                return known == fp
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        ssh.connect(p.host, p.port)
        ssh.authPassword(p.username, p.password)
        ssh.connection.keepAlive.keepAliveInterval = 25
        return ssh
    }

    private fun hevConfig(socksPort: Int) = """
        tunnel:
          mtu: ${TunnelConfig.MTU}
          ipv4: ${TunnelConfig.TUN_IPV4}
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'
        misc:
          task-stack-size: 20480
          connect-timeout: 5000
          read-write-timeout: 60000
          log-file: stderr
          log-level: warn
    """.trimIndent() + "\n"

    private class SshRemoteConn(
        private val conn: net.schmizz.sshj.connection.channel.direct.DirectConnection
    ) : RemoteConn {
        override val input: InputStream get() = conn.inputStream
        override val output: OutputStream get() = conn.outputStream
        override fun close() { conn.close() }
    }

    companion object {
        private var bcInstalled = false

        /** Android membawa BC versi terpotong; sshj butuh BC lengkap. */
        @Synchronized
        private fun installBouncyCastle() {
            if (bcInstalled) return
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            bcInstalled = true
        }
    }
}
