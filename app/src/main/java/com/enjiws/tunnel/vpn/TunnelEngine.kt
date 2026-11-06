package com.enjiws.tunnel.vpn

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.awaitCancellation

object TunnelConfig {
    const val MTU = 1400

    /**
     * false = TUN hanya punya rute test privat (10.255.0.0/24), internet normal TIDAK terganggu.
     * Ubah ke true HANYA setelah engine asli (SSH/HTTP + tun2socks) terpasang,
     * kalau tidak semua traffic HP akan masuk TUN dan terbuang.
     */
    const val CAPTURE_ALL_TRAFFIC = false
}

/**
 * Titik colok engine tunnel. run() harus suspend selama tunnel hidup,
 * lempar exception kalau gagal (service akan reconnect dengan backoff),
 * dan patuh pada cancellation.
 */
interface TunnelEngine {
    suspend fun run(tun: ParcelFileDescriptor)
}

/** Placeholder: tidak membaca paket, tidak memakai CPU, hanya menahan sesi tetap hidup. */
class IdleEngine : TunnelEngine {
    override suspend fun run(tun: ParcelFileDescriptor) {
        awaitCancellation()
    }
}
