package com.enjiws.tunnel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.enjiws.tunnel.vpn.Status

object TunnelColors {
    val Bg = Color(0xFF0A0E13)
    val Surface = Color(0xFF121820)
    val SurfaceHigh = Color(0xFF1A232D)
    val Outline = Color(0xFF263341)
    val Text = Color(0xFFE9EFF5)
    val TextDim = Color(0xFF8795A5)
    val Online = Color(0xFF2EE6A6)
    val Busy = Color(0xFFFFB84D)
    val Danger = Color(0xFFFF5C6C)
    val Info = Color(0xFF5B9DFF)
}

fun statusColor(s: Status): Color = when (s) {
    Status.CONNECTED -> TunnelColors.Online
    Status.DISCONNECTED -> TunnelColors.TextDim
    else -> TunnelColors.Busy
}

@Composable
fun TunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TunnelColors.Online,
            onPrimary = Color(0xFF00241A),
            background = TunnelColors.Bg,
            onBackground = TunnelColors.Text,
            surface = TunnelColors.Surface,
            onSurface = TunnelColors.Text,
            surfaceVariant = TunnelColors.SurfaceHigh,
            onSurfaceVariant = TunnelColors.TextDim,
            outline = TunnelColors.Outline,
            error = TunnelColors.Danger
        ),
        content = content
    )
}
