package com.enjiws.tunnel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Kartu dasar semua layar. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlight: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TunnelColors.Surface)
            .border(1.dp, highlight ?: TunnelColors.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) TunnelColors.Online else TunnelColors.TextDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .background(if (selected) TunnelColors.Online.copy(alpha = 0.14f) else TunnelColors.SurfaceHigh)
            .border(1.dp, if (selected) TunnelColors.Online else TunnelColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        color = TunnelColors.TextDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
fun tunnelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TunnelColors.Online,
    unfocusedBorderColor = TunnelColors.Outline,
    focusedLabelColor = TunnelColors.Online,
    unfocusedLabelColor = TunnelColors.TextDim,
    cursorColor = TunnelColors.Online,
    focusedTextColor = TunnelColors.Text,
    unfocusedTextColor = TunnelColors.Text
)

fun formatBytes(n: Long): String {
    val v = n.coerceAtLeast(0).toDouble()
    return when {
        v < 1024 -> "${n.coerceAtLeast(0)} B"
        v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
        v < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024 * 1024))
        else -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024 * 1024))
    }
}

fun formatRate(bytesPerSec: Float) = formatBytes(bytesPerSec.toLong()) + "/s"

fun formatUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
