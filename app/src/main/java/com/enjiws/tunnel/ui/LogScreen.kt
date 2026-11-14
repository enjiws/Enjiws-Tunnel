package com.enjiws.tunnel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjiws.tunnel.vpn.LogLevel
import com.enjiws.tunnel.vpn.TunnelLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val entries by TunnelLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Log", color = TunnelColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(entries.joinToString("\n") {
                    "${fmt.format(Date(it.time))} ${it.level.name} ${it.message}"
                }))
            }) { Icon(Icons.Default.ContentCopy, contentDescription = "Salin log", tint = TunnelColors.Info) }
            IconButton(onClick = { TunnelLog.clear() }) {
                Icon(Icons.Default.Delete, contentDescription = "Bersihkan log", tint = TunnelColors.TextDim)
            }
        }

        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(shape)
                .background(TunnelColors.Surface).border(1.dp, TunnelColors.Outline, shape)
        ) {
            if (entries.isEmpty()) {
                Text("Belum ada aktivitas. Sambungkan tunnel untuk melihat log.",
                    color = TunnelColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries) { e ->
                        val color = when (e.level) {
                            LogLevel.INFO -> TunnelColors.Text
                            LogLevel.WARN -> TunnelColors.Busy
                            LogLevel.ERROR -> TunnelColors.Danger
                        }
                        Text(
                            "${fmt.format(Date(e.time))}  ${e.message}",
                            color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
