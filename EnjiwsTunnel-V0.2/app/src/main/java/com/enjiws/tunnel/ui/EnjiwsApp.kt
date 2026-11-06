package com.enjiws.tunnel.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.enjiws.tunnel.vpn.EnjiwsVpnService
import com.enjiws.tunnel.vpn.NetworkKind
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TunnelState
import com.enjiws.tunnel.vpn.TunnelStateHolder
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun EnjiwsApp() {
    val context = LocalContext.current
    val state by TunnelStateHolder.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) EnjiwsVpnService.start(context)
    }

    val prepareAndStart = {
        val intent = VpnService.prepare(context)
        if (intent == null) EnjiwsVpnService.start(context) else vpnLauncher.launch(intent)
    }

    // Notifikasi ditolak tidak apa-apa: VPN tetap jalan, hanya notifikasinya tersembunyi.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { prepareAndStart() }

    val onToggle = {
        if (state.status == Status.DISCONNECTED) {
            val needNotif = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else prepareAndStart()
        } else {
            EnjiwsVpnService.stop(context)
        }
    }

    Scaffold(
        containerColor = EnjiwsColors.Black,
        bottomBar = {
            NavigationBar(containerColor = EnjiwsColors.Charcoal) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = EnjiwsColors.Text,
                    selectedTextColor = EnjiwsColors.RedGlow,
                    indicatorColor = EnjiwsColors.RedDark,
                    unselectedIconColor = EnjiwsColors.Grey,
                    unselectedTextColor = EnjiwsColors.Grey
                )
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Profiles") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 }, colors = colors,
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Config") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 }, colors = colors,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(state, onToggle, Modifier.padding(padding))
            1 -> ProfileScreen(Modifier.padding(padding))
            2 -> ProtocolConfigScreen(Modifier.padding(padding))
            else -> PlaceholderScreen(
                "Settings",
                "DNS, routing and battery options will live here.",
                Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun HomeScreen(state: TunnelState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Ticker uptime hanya jalan saat connected DAN layar terlihat (hemat baterai).
    LaunchedEffect(state.status) {
        if (state.status == Status.CONNECTED) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
        }
    }

    val uptime = if (state.status == Status.CONNECTED && state.connectedSince > 0)
        formatUptime(now - state.connectedSince) else "--:--:--"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Enjiws", color = EnjiwsColors.RedGlow,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
            )
            Text(
                "Tunnel", color = EnjiwsColors.Text,
                fontSize = 34.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        ConnectButton(state.status) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                statusTitle(state.status), color = EnjiwsColors.Text,
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                statusSubtitle(state, uptime), color = EnjiwsColors.Grey,
                fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Network", networkLabel(state.network), Modifier.weight(1f))
            MetricCard("Uptime", uptime, Modifier.weight(1f))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = EnjiwsColors.Panel)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Test mode", color = EnjiwsColors.RedGlow,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text(
                    "The tunnel interface comes up on a private test route, so your normal " +
                        "internet is not touched yet. SSH and HTTP engines plug in next.",
                    color = EnjiwsColors.Grey, fontSize = 13.sp, lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun ConnectButton(status: Status, onClick: () -> Unit) {
    val on = status == Status.CONNECTED
    val busy = status == Status.CONNECTING ||
        status == Status.RECONNECTING ||
        status == Status.WAITING_NETWORK

    val fill by animateColorAsState(
        targetValue = when {
            on -> EnjiwsColors.Red
            busy -> EnjiwsColors.RedDark
            else -> EnjiwsColors.Panel
        },
        animationSpec = tween(400), label = "fill"
    )
    val ring by animateColorAsState(
        targetValue = if (on || busy) EnjiwsColors.RedGlow else EnjiwsColors.Outline,
        animationSpec = tween(400), label = "ring"
    )

    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        if (busy) PulseRing() // animasi infinite hanya ada selama proses connect
        Box(
            modifier = Modifier
                .size(168.dp)
                .border(2.dp, ring, CircleShape)
                .clip(CircleShape)
                .background(fill)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = if (status == Status.DISCONNECTED) "Connect" else "Disconnect",
                tint = if (on || busy) EnjiwsColors.Text else EnjiwsColors.Grey,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val s by transition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Restart
        ),
        label = "scale"
    )
    val a by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "alpha"
    )
    Box(
        Modifier
            .size(168.dp)
            .graphicsLayer { scaleX = s; scaleY = s; alpha = a }
            .background(EnjiwsColors.RedGlow, CircleShape)
    )
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = EnjiwsColors.Panel)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = EnjiwsColors.Grey, fontSize = 12.sp)
            Text(
                value, color = EnjiwsColors.Text,
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = EnjiwsColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(description, color = EnjiwsColors.Grey, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

private fun statusTitle(s: Status) = when (s) {
    Status.DISCONNECTED -> "Disconnected"
    Status.CONNECTING -> "Connecting"
    Status.CONNECTED -> "Connected"
    Status.RECONNECTING -> "Reconnecting"
    Status.WAITING_NETWORK -> "Waiting for network"
}

private fun statusSubtitle(state: TunnelState, uptime: String) = when (state.status) {
    Status.DISCONNECTED -> "Tap the button to connect"
    Status.CONNECTING -> "Setting up the tunnel"
    Status.CONNECTED -> "Tunnel is up for $uptime"
    Status.RECONNECTING -> "Attempt ${state.attempt}" +
        (state.message?.let { " · $it" } ?: "")
    Status.WAITING_NETWORK -> "No usable connection yet"
}

private fun networkLabel(k: NetworkKind) = when (k) {
    NetworkKind.WIFI -> "Wi-Fi"
    NetworkKind.CELLULAR -> "Mobile"
    NetworkKind.ETHERNET -> "Ethernet"
    NetworkKind.OTHER -> "Other"
    NetworkKind.NONE -> "--"
}

private fun formatUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
