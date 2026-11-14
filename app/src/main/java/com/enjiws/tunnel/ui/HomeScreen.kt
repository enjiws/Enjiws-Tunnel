package com.enjiws.tunnel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.enjiws.tunnel.profiles.TunnelProfile
import com.enjiws.tunnel.vpn.NetworkKind
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TrafficHistory
import com.enjiws.tunnel.vpn.TrafficStats
import com.enjiws.tunnel.vpn.TunnelState
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun HomeScreen(
    state: TunnelState,
    profile: TunnelProfile?,
    onToggle: () -> Unit,
    onPickProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var samples by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) } // (down, up) B/s
    var totals by remember { mutableStateOf(0L to 0L) }
    val context = LocalContext.current
    var usage by remember { mutableStateOf(0L to 0L) } // (hari ini, 7 hari)

    // Satu ticker 1 dtk untuk uptime + traffic; hanya jalan saat CONNECTED dan layar terlihat.
    LaunchedEffect(state.status) {
        if (state.status == Status.CONNECTED) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastD = TrafficStats.downBytes()
                var lastU = TrafficStats.upBytes()
                var tick = 0
                while (true) {
                    now = System.currentTimeMillis()
                    totals = TrafficStats.downBytes() to TrafficStats.upBytes()
                    if (tick++ % 10 == 0) usage = TrafficHistory.total(context, 1) to TrafficHistory.total(context, 7)
                    delay(1000)
                    val d = TrafficStats.downBytes()
                    val u = TrafficStats.upBytes()
                    samples = (samples + ((d - lastD).toFloat() to (u - lastU).toFloat())).takeLast(60)
                    lastD = d
                    lastU = u
                }
            }
        } else {
            samples = emptyList()
            totals = 0L to 0L
            usage = TrafficHistory.total(context, 1) to TrafficHistory.total(context, 7)
        }
    }

    val connected = state.status == Status.CONNECTED
    val tone by animateColorAsState(statusColor(state.status), tween(450), label = "tone")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enjiws", color = TunnelColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Tunnel", color = TunnelColors.TextDim, fontSize = 13.sp)
            }
            NetworkChip(state.network)
        }

        HeroButton(state.status) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(statusTitle(state.status), color = tone, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (connected && state.connectedSince > 0) formatUptime(now - state.connectedSince)
                else statusSubtitle(state),
                color = if (connected) TunnelColors.Text else TunnelColors.TextDim,
                fontSize = if (connected) 28.sp else 13.sp,
                fontWeight = if (connected) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            if (connected && state.autoStopAt > 0) {
                Text(
                    "Berhenti otomatis dalam ${formatUptime(state.autoStopAt - now)}",
                    color = TunnelColors.Busy, fontSize = 12.sp
                )
            }
        }

        Panel(onClick = onPickProfile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Server", color = TunnelColors.TextDim, fontSize = 12.sp)
                    Text(
                        profile?.name ?: "Belum ada profil",
                        color = TunnelColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        profile?.let { "${it.type.name} · ${it.host}:${it.port} · DNS ${it.dns}" }
                            ?: "Ketuk untuk membuat atau memilih profil",
                        color = TunnelColors.TextDim, fontSize = 12.sp
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = "Pilih server", tint = TunnelColors.TextDim)
            }
        }

        TrafficPanel(connected, samples, totals, usage)
    }
}

@Composable
private fun rememberSpin(): Float {
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "rot"
    )
    return r
}

@Composable
private fun HeroButton(status: Status, onClick: () -> Unit) {
    val active = status == Status.CONNECTED
    val busy = status == Status.CONNECTING || status == Status.RECONNECTING || status == Status.WAITING_NETWORK
    val tone by animateColorAsState(statusColor(status), tween(450), label = "heroTone")
    // Animasi infinite hanya ada selama proses connect (hemat baterai).
    val rotation = if (busy) rememberSpin() else 0f

    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val r = size.minDimension / 2 - stroke
            if (active || busy) {
                drawCircle(
                    Brush.radialGradient(listOf(tone.copy(alpha = 0.22f), Color.Transparent), radius = size.minDimension / 2),
                    radius = size.minDimension / 2
                )
            }
            drawCircle(TunnelColors.Outline, radius = r, style = Stroke(stroke))
            if (active) {
                drawCircle(tone, radius = r, style = Stroke(stroke))
            } else if (busy) {
                drawArc(
                    color = tone,
                    startAngle = rotation - 90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(164.dp)
                .clip(CircleShape)
                .background(TunnelColors.Surface)
                .border(1.dp, TunnelColors.Outline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = if (status == Status.DISCONNECTED) "Connect" else "Disconnect",
                tint = tone,
                modifier = Modifier.size(60.dp)
            )
        }
    }
}

@Composable
private fun NetworkChip(kind: NetworkKind) {
    val (icon, label) = when (kind) {
        NetworkKind.WIFI -> Icons.Default.Wifi to "Wi-Fi"
        NetworkKind.CELLULAR -> Icons.Default.SignalCellularAlt to "Seluler"
        NetworkKind.ETHERNET -> Icons.Default.Lan to "Ethernet"
        NetworkKind.OTHER -> Icons.Default.Lan to "Lainnya"
        NetworkKind.NONE -> Icons.Default.Lan to "Offline"
    }
    val color = if (kind == NetworkKind.NONE) TunnelColors.TextDim else TunnelColors.Info
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(TunnelColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TrafficPanel(
    connected: Boolean, samples: List<Pair<Float, Float>>, totals: Pair<Long, Long>, usage: Pair<Long, Long>
) {
    Panel {
        Row(Modifier.fillMaxWidth()) {
            TrafficStat(
                Icons.Default.ArrowDownward, "Download", TunnelColors.Online,
                if (connected) samples.lastOrNull()?.first?.let { formatRate(it) } ?: "0 B/s" else "—",
                if (connected) "Total ${formatBytes(totals.first)}" else "Total —",
                Modifier.weight(1f)
            )
            TrafficStat(
                Icons.Default.ArrowUpward, "Upload", TunnelColors.Info,
                if (connected) samples.lastOrNull()?.second?.let { formatRate(it) } ?: "0 B/s" else "—",
                if (connected) "Total ${formatBytes(totals.second)}" else "Total —",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        Sparkline(samples, Modifier.fillMaxWidth().height(72.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            "Pemakaian: hari ini ${formatBytes(usage.first)} · 7 hari ${formatBytes(usage.second)}",
            color = TunnelColors.TextDim, fontSize = 11.sp
        )
    }
}

@Composable
private fun TrafficStat(
    icon: ImageVector, title: String, color: Color, rate: String, total: String, modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = TunnelColors.TextDim, fontSize = 12.sp)
        }
        Text(rate, color = TunnelColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(total, color = TunnelColors.TextDim, fontSize = 11.sp)
    }
}

/** Grafik 60 detik terakhir: area = download, garis = upload. */
@Composable
private fun Sparkline(samples: List<Pair<Float, Float>>, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(TunnelColors.Outline, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        if (samples.size < 2) return@Canvas
        val maxV = max(10_240f, samples.maxOf { max(it.first, it.second) })
        val step = w / 59f
        val startX = w - (samples.size - 1) * step
        val pad = 4.dp.toPx()
        fun y(v: Float) = h - (v / maxV) * (h - pad)

        val area = Path().apply {
            moveTo(startX, h)
            samples.forEachIndexed { i, s -> lineTo(startX + i * step, y(s.first)) }
            lineTo(w, h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(TunnelColors.Online.copy(alpha = 0.35f), Color.Transparent)))

        val line = Path().apply {
            samples.forEachIndexed { i, s ->
                val x = startX + i * step
                if (i == 0) moveTo(x, y(s.second)) else lineTo(x, y(s.second))
            }
        }
        drawPath(line, TunnelColors.Info, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun statusTitle(s: Status) = when (s) {
    Status.DISCONNECTED -> "Terputus"
    Status.CONNECTING -> "Menyambung"
    Status.CONNECTED -> "Terhubung"
    Status.RECONNECTING -> "Menyambung ulang"
    Status.WAITING_NETWORK -> "Menunggu jaringan"
}

private fun statusSubtitle(state: TunnelState) = when (state.status) {
    Status.DISCONNECTED -> "Ketuk tombol untuk menyambung"
    Status.CONNECTING -> "Menyiapkan tunnel…"
    Status.CONNECTED -> ""
    Status.RECONNECTING -> "Percobaan ${state.attempt}" + (state.message?.let { " · $it" } ?: "")
    Status.WAITING_NETWORK -> "Belum ada koneksi internet yang bisa dipakai"
}
