package com.enjiws.tunnel.profiles

import android.content.Context
import com.enjiws.tunnel.vpn.EnjiwsVpnService
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TunnelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** State profil bersama untuk Home & Profiles. Semua I/O di Dispatchers.IO. */
object ProfileRepo {
    private val _profiles = MutableStateFlow<List<TunnelProfile>>(emptyList())
    private val _activeId = MutableStateFlow<String?>(null)
    val profiles: StateFlow<List<TunnelProfile>> = _profiles.asStateFlow()
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val list = TunnelProfileStore.all(app)
        _profiles.value = list
        _activeId.value = TunnelProfileStore.activeId(app)?.takeIf { id -> list.any { it.id == id } }
    }

    suspend fun save(context: Context, profile: TunnelProfile, activate: Boolean) {
        withContext(Dispatchers.IO) {
            TunnelProfileStore.save(context.applicationContext, profile)
            if (activate) TunnelProfileStore.setActive(context.applicationContext, profile.id)
        }
        refresh(context)
    }

    suspend fun delete(context: Context, id: String) {
        withContext(Dispatchers.IO) { TunnelProfileStore.delete(context.applicationContext, id) }
        refresh(context)
    }

    /** Pilih profil aktif; kalau tunnel sedang jalan, langsung sambung ulang ke profil baru. */
    suspend fun select(context: Context, id: String) {
        withContext(Dispatchers.IO) { TunnelProfileStore.setActive(context.applicationContext, id) }
        refresh(context)
        if (TunnelStateHolder.state.value.status != Status.DISCONNECTED) {
            EnjiwsVpnService.reconnect(context)
        }
    }
}
