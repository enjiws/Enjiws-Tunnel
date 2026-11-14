package com.enjiws.tunnel.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.enjiws.tunnel.profiles.TunnelProfile
import com.github.mwiede.jsch.ChannelDirectTCPIP
import com.github.mwiede.jsch.HostKey
import com.github.mwiede.jsch.HostKeyRepository
import com.github.mwiede.jsch.JSch
import com.github.mwiede.jsch.Session
import com.github.mwiede.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine SSH: tiap koneksi TCP dari TUN dibuka sebagai channel direct-tcpip
 * ke tujuan lewat server SSH (port forwarding SSH standar).
 *
 * Keamanan host key: TOFU (Trust On First Use). Kunci host disimpan di
 * SharedPreferences saat pertama konek; kalau server mengganti kuncinya,
 * koneksi DITOLAK (perlindungan MITM). Tidak ada StrictHostKeyChecking=no.
 */
class SshEngine(
    private val context: Context,
    private val profile: TunnelProfile
) : TunnelEngine {
    override val capturesTraffic = true
    private val raw = SshUpstream(context, profile)
    private val upstream: Upstream = CountingUpstream(raw)

    override suspend fun run(tun: ParcelFileDescriptor) {
        val tunIn = FileInputStream(tun.fileDescriptor)
        val tunOut = FileOutputStream(tun.fileDescriptor)
        val forwarder = TcpForwarder(tunIn, tunOut, upstream, DnsRelay(upstream))
        try {
            // Fail fast: kalau SSH tidak bisa konek, lempar error supaya service
            // langsung masuk mode reconnect (backoff), bukan pura-pura CONNECTED.
            withContext(Dispatchers.IO) { raw.warmUp() }
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

class SshUpstream(
    private val context: Context,
    private val profile: TunnelProfile
) : Upstream {
    private val jsch = JSch()
    private val sessions = mutableListOf<Session>()
    private val nextIdx = AtomicInteger(0)
    private val lock = Any()

    init {
        jsch.hostKeyRepository = HostKeyStore(context)
        if (profile.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "enjiws-key",
                profile.privateKey.toByteArray(Charsets.UTF_8),
                null,
                profile.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            )
        }
    }

    /** Buka satu sesi SSH (dipakai warm-up). */
    fun warmUp() {
        getSession()
    }

    override fun openStream(host: String, port: Int): TunnelStream {
        var lastError: Exception? = null
        repeat(2) {
            val session = try {
                getSession()
            } catch (e: Exception) {
                lastError = e
                return@repeat
            }
            try {
                val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                ch.setHost(host)
                ch.setPort(port)
                ch.connect(CHANNEL_TIMEOUT_MS)
                return TunnelStream(
                    ch.inputStream,
                    ch.outputStream,
                    onClose = { runCatching { ch.disconnect() } },
                    onShutdownOutput = { runCatching { ch.outputStream.close() } }
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "direct-tcpip failed, retry on another session", e)
            }
        }
        throw lastError ?: IOException("SSH stream failed")
    }

    override fun close() {
        synchronized(lock) {
            sessions.forEach { runCatching { it.disconnect() } }
            sessions.clear()
        }
    }

    /**
     * Ambil sesi hidup secara round-robin. Server SSH umumnya membatasi channel
     * per sesi (MaxSessions, default 10), jadi dipakai pool 3 sesi.
     * Connect dilakukan di luar lock pool supaya koneksi lain tidak ikut macet.
     */
    private fun getSession(): Session {
        val candidate: Session = synchronized(lock) {
            if (sessions.isNotEmpty()) {
                repeat(sessions.size) {
                    val s = sessions[(nextIdx.getAndIncrement() and Int.MAX_VALUE) % sessions.size]
                    if (s.isConnected) return s
                }
            }
            if (sessions.size < POOL_SIZE) {
                val s = buildSession()
                sessions.add(s)
                return s
            }
            sessions.firstOrNull { !it.isConnected } ?: sessions[0]
        }
        synchronized(candidate) {
            if (!candidate.isConnected) candidate.connect(CONNECT_TIMEOUT_MS)
        }
        return candidate
    }

    private fun buildSession(): Session {
        val s = jsch.getSession(profile.username, profile.host, profile.port)
        if (profile.password.isNotEmpty()) s.setPassword(profile.password)
        if (profile.tls) s.setSocketFactory(TlsSocketFactory(profile.tlsHost))
        // "ask" + UserInfo yang menerima kunci baru = TOFU; kunci yang BERUBAH ditolak JSch.
        s.setConfig("StrictHostKeyChecking", "ask")
        val hasKey = profile.privateKey.isNotBlank()
        s.setConfig(
            "PreferredAuthentications",
            when {
                hasKey && profile.password.isNotEmpty() -> "publickey,password"
                hasKey -> "publickey"
                else -> "password"
            }
        )
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        s.userInfo = AcceptingUserInfo
        return s
    }

    companion object {
        private const val TAG = "SshUpstream"
        private const val POOL_SIZE = 3
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 10_000
    }
}

private object AcceptingUserInfo : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    /** Terima kunci host baru (TOFU). Kunci yang berubah sudah ditolak JSch sebelum prompt ini. */
    override fun promptYesNo(message: String?): Boolean = true
    override fun showMessage(message: String?) {}
}

/**
 * Penyimpanan kunci host SSH di SharedPreferences.
 * check(): OK bila blob sama, CHANGED bila server mengganti kunci, NOT_INCLUDED bila baru.
 */
class HostKeyStore(context: Context) : HostKeyRepository {
    private val prefs = context.getSharedPreferences("tunnel_hostkeys", Context.MODE_PRIVATE)

    override fun check(host: String, key: ByteArray): Int {
        val stored = prefs.getString(keyOf(host), null) ?: return NOT_INCLUDED
        val storedBytes = runCatching { Base64.decode(stored, Base64.DEFAULT) }.getOrNull()
            ?: return NOT_INCLUDED
        return if (storedBytes.contentEquals(key)) OK else CHANGED
    }

    override fun add(hostkey: HostKey, userinfo: UserInfo?) {
        prefs.edit()
            .putString(keyOf(hostkey.host), Base64.encodeToString(hostkey.key, Base64.NO_WRAP))
            .apply()
    }

    override fun remove(host: String, key: ByteArray?) {
        prefs.edit().remove(keyOf(host)).apply()
    }

    override fun getKnownHostsRepositoryID(): String = "enjiws-prefs"

    override fun getHostKey(): Array<HostKey> =
        prefs.all.keys.filter { it.startsWith("hk_") }.mapNotNull { k ->
            runCatching {
                val host = k.removePrefix("hk_")
                val blob = Base64.decode(prefs.getString(k, "") ?: "", Base64.DEFAULT)
                HostKey(host, blob)
            }.getOrNull()
        }.toTypedArray()

    override fun getHostKey(host: String, type: String): Array<HostKey> =
        getHostKey().filter { it.host == host }.toTypedArray()

    override fun setKnownHosts(filename: String) {
        // tidak dipakai: kunci disimpan di SharedPreferences, bukan file
    }

    private fun keyOf(host: String) = "hk_$host"

    companion object {
        const val OK = 0
        const val NOT_INCLUDED = 1
        const val CHANGED = 2
    }
}
