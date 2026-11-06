package com.enjiws.tunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.enjiws.tunnel.MainActivity
import com.enjiws.tunnel.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

private data class ActiveNet(val network: Network, val kind: NetworkKind)

class EnjiwsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var notificationJob: Job? = null
    private val engine: TunnelEngine = IdleEngine()

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networks = LinkedHashMap<Network, NetworkKind>()
    private val currentNet = MutableStateFlow<ActiveNet?>(null)
    private var callbackRegistered = false

    // Event-driven: tidak ada polling status jaringan.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(networks) {
                networks[network] = kindOf(caps)
                recomputeBestNetwork()
            }
        }

        override fun onLost(network: Network) {
            synchronized(networks) {
                networks.remove(network)
                recomputeBestNetwork()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel() // ACTION_START dan always-on VPN dari sistem
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Pengguna mencabut izin VPN / VPN lain mengambil alih.
        stopTunnel()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        scope.cancel()
        TunnelStateHolder.reset()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startTunnel() {
        if (sessionJob?.isActive == true) return
        promoteToForeground()
        registerNetworkCallback()
        TunnelStateHolder.update { TunnelState(status = Status.CONNECTING) }

        notificationJob = scope.launch {
            TunnelStateHolder.state.collect { s ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(s))
            }
        }
        sessionJob = scope.launch { runSession() }
    }

    private fun stopTunnel() {
        notificationJob?.cancel()
        sessionJob?.cancel() // finally{} di runSession menutup TUN
        sessionJob = null
        unregisterNetworkCallback()
        TunnelStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- session loop

    private suspend fun runSession() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            publishConnecting(attempt)
            val net = awaitNetwork()
            var tun: ParcelFileDescriptor? = null
            var upSince = 0L
            try {
                tun = buildTun(net)
                upSince = SystemClock.elapsedRealtime()
                TunnelStateHolder.update {
                    it.copy(
                        status = Status.CONNECTED,
                        connectedSince = System.currentTimeMillis(),
                        attempt = 0,
                        message = null
                    )
                }
                runEngine(tun, net)
                attempt = 0 // keluar normal = jaringan berganti, sambung ulang langsung
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stable = upSince != 0L &&
                    SystemClock.elapsedRealtime() - upSince >= STABLE_MS
                attempt = if (stable) 1 else attempt + 1
                TunnelStateHolder.update {
                    it.copy(
                        status = Status.RECONNECTING,
                        connectedSince = 0L,
                        attempt = attempt,
                        message = e.message ?: e.javaClass.simpleName
                    )
                }
                delay(backoffMillis(attempt))
            } finally {
                runCatching { tun?.close() }
            }
        }
    }

    /** Jalankan engine; berhenti (return normal) kalau jaringan aktif berganti, lempar error kalau engine mati sendiri. */
    private suspend fun runEngine(tun: ParcelFileDescriptor, net: ActiveNet) = coroutineScope {
        val engineJob = launch { engine.run(tun) }
        val watcher = launch {
            currentNet.first { it?.network != net.network }
            engineJob.cancel()
        }
        engineJob.join()
        if (watcher.isActive) {
            watcher.cancel()
            error("Tunnel engine stopped unexpectedly")
        }
    }

    private suspend fun awaitNetwork(): ActiveNet {
        currentNet.value?.let { return it }
        TunnelStateHolder.update { it.copy(status = Status.WAITING_NETWORK) }
        return currentNet.filterNotNull().first()
    }

    private fun publishConnecting(attempt: Int) {
        TunnelStateHolder.update {
            it.copy(status = if (attempt == 0) Status.CONNECTING else Status.RECONNECTING)
        }
    }

    private fun buildTun(net: ActiveNet): ParcelFileDescriptor {
        val b = Builder()
            .setSession("Enjiws Tunnel")
            .setMtu(TunnelConfig.MTU)
            .addAddress("10.255.0.1", 24)
            .setBlocking(true)
            .setUnderlyingNetworks(arrayOf(net.network))

        if (TunnelConfig.CAPTURE_ALL_TRAFFIC) {
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("1.1.1.1")
        } else {
            b.addRoute("10.255.0.0", 24)
        }
        // Socket milik app ini tidak masuk TUN sendiri (anti routing loop untuk engine nanti).
        runCatching { b.addDisallowedApplication(packageName) }

        return b.establish() ?: error("VPN permission revoked or establish() failed")
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = min(30_000L, 1_000L shl min(attempt - 1, 5))
        return base + Random.nextLong(0, 500)
    }

    // ---------------------------------------------------------------- network tracking

    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivity.registerNetworkCallback(request, networkCallback)
        callbackRegistered = true
    }

    private fun unregisterNetworkCallback() {
        if (!callbackRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        synchronized(networks) {
            networks.clear()
            currentNet.value = null
        }
    }

    /** Wajib dipanggil di dalam synchronized(networks). Prioritas: Wi-Fi/Ethernet > seluler > lainnya. */
    private fun recomputeBestNetwork() {
        val best = networks.entries.maxByOrNull { priority(it.value) }
        val next = best?.let { ActiveNet(it.key, it.value) }
        currentNet.value = next
        TunnelStateHolder.update { it.copy(network = next?.kind ?: NetworkKind.NONE) }
    }

    private fun priority(kind: NetworkKind) = when (kind) {
        NetworkKind.WIFI, NetworkKind.ETHERNET -> 3
        NetworkKind.CELLULAR -> 2
        else -> 1
    }

    private fun kindOf(caps: NetworkCapabilities) = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
        else -> NetworkKind.OTHER
    }

    // ---------------------------------------------------------------- notification

    private fun promoteToForeground() {
        val n = buildNotification(TunnelStateHolder.state.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(s: TunnelState): Notification {
        val text = when (s.status) {
            Status.CONNECTED -> "Connected"
            Status.CONNECTING -> "Connecting…"
            Status.RECONNECTING -> "Reconnecting (attempt ${s.attempt})…"
            Status.WAITING_NETWORK -> "Waiting for network…"
            Status.DISCONNECTED -> "Disconnected"
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, EnjiwsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("Enjiws Tunnel")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setColor(0xFFC62828.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.enjiws.tunnel.START"
        const val ACTION_STOP = "com.enjiws.tunnel.STOP"
        private const val CHANNEL_ID = "tunnel_status"
        private const val NOTIF_ID = 1
        private const val STABLE_MS = 10_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EnjiwsVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EnjiwsVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
