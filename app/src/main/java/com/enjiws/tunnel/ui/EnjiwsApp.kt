package com.enjiws.tunnel.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjiws.tunnel.profiles.ProfileRepo
import com.enjiws.tunnel.vpn.EnjiwsVpnService
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TunnelStateHolder
import kotlinx.coroutines.launch

@Composable
fun EnjiwsApp() {
    TunnelTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val state by TunnelStateHolder.state.collectAsStateWithLifecycle()
        val profiles by ProfileRepo.profiles.collectAsStateWithLifecycle()
        val activeId by ProfileRepo.activeId.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showPicker by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { ProfileRepo.refresh(context) }

        val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) EnjiwsVpnService.start(context)
        }
        val prepareAndStart = {
            val intent = VpnService.prepare(context)
            if (intent == null) EnjiwsVpnService.start(context) else vpnLauncher.launch(intent)
        }
        // Notifikasi ditolak tidak apa-apa: VPN tetap jalan, hanya notifikasinya tersembunyi.
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            prepareAndStart()
        }
        val onToggle = {
            if (state.status == Status.DISCONNECTED) {
                val needNotif = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else prepareAndStart()
            } else {
                EnjiwsVpnService.stop(context)
            }
        }

        Scaffold(
            containerColor = TunnelColors.Bg,
            bottomBar = {
                NavigationBar(containerColor = TunnelColors.Surface) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TunnelColors.Online,
                        selectedTextColor = TunnelColors.Online,
                        indicatorColor = TunnelColors.SurfaceHigh,
                        unselectedIconColor = TunnelColors.TextDim,
                        unselectedTextColor = TunnelColors.TextDim
                    )
                    listOf<Pair<String, ImageVector>>(
                        "Home" to Icons.Default.Home,
                        "Profil" to Icons.Default.Dns,
                        "Log" to Icons.Default.Description,
                        "Setelan" to Icons.Default.Settings
                    ).forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i }, colors = colors,
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            val m = Modifier.padding(padding)
            when (tab) {
                0 -> HomeScreen(state, profiles.firstOrNull { it.id == activeId }, onToggle, { showPicker = true }, m)
                1 -> ProfileScreen(m)
                2 -> LogScreen(m)
                else -> SettingsScreen(state, m)
            }
        }

        if (showPicker) {
            ProfilePickerSheet(
                profiles = profiles,
                activeId = activeId,
                onPick = { p ->
                    showPicker = false
                    scope.launch { ProfileRepo.select(context, p.id) }
                },
                onManage = { showPicker = false; tab = 1 },
                onDismiss = { showPicker = false }
            )
        }
    }
}
