package com.enjiws.tunnel.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.enjiws.tunnel.vpn.EnjiwsVpnService
import com.enjiws.tunnel.vpn.SplitMode
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TunnelSettings
import com.enjiws.tunnel.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: TunnelState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(TunnelSettings.splitMode(context)) }
    var apps by remember { mutableStateOf(TunnelSettings.splitApps(context)) }
    var autoReconnect by remember { mutableStateOf(TunnelSettings.autoReconnect(context)) }
    var autoStop by remember { mutableStateOf(TunnelSettings.autoStopMinutes(context)) }
    var failover by remember { mutableStateOf(TunnelSettings.failover(context)) }
    var showPicker by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val running = state.status != Status.DISCONNECTED

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setelan", color = TunnelColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

        if (running && dirty) {
            Panel(highlight = TunnelColors.Busy) {
                Text("Perubahan berlaku setelah sambung ulang.", color = TunnelColors.Text, fontSize = 13.sp)
                OutlinedButton(onClick = {
                    EnjiwsVpnService.reconnect(context)
                    dirty = false
                }) { Text("Sambung ulang sekarang", color = TunnelColors.Busy) }
            }
        }

        SectionLabel("Split tunneling")
        Panel {
            ModeRow("Semua aplikasi lewat tunnel", mode == SplitMode.ALL) {
                mode = SplitMode.ALL; TunnelSettings.setSplitMode(context, mode); dirty = true
            }
            ModeRow("Hanya aplikasi pilihan", mode == SplitMode.ONLY) {
                mode = SplitMode.ONLY; TunnelSettings.setSplitMode(context, mode); dirty = true
            }
            ModeRow("Semua kecuali aplikasi pilihan", mode == SplitMode.EXCEPT) {
                mode = SplitMode.EXCEPT; TunnelSettings.setSplitMode(context, mode); dirty = true
            }
            if (mode != SplitMode.ALL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pilih aplikasi (${apps.size})", color = TunnelColors.Text)
                }
                if (mode == SplitMode.ONLY && apps.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Belum ada aplikasi dipilih, jadi semua aplikasi tetap lewat tunnel.",
                        color = TunnelColors.Busy, fontSize = 12.sp
                    )
                }
            }
        }

        SectionLabel("Koneksi")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sambung ulang otomatis", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Coba lagi dengan jeda bertahap saat koneksi putus. Kalau mati, tunnel berhenti saat gagal.",
                        color = TunnelColors.TextDim, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it; TunnelSettings.setAutoReconnect(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = TunnelColors.Online, checkedThumbColor = Color(0xFF00241A)
                    )
                )
            }
        }

        SectionLabel("Otomatis")
        Panel {
            Text("Berhenti otomatis", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
            Text(
                "Putuskan tunnel setelah waktu tertentu. Berlaku mulai sambungan berikutnya.",
                color = TunnelColors.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Mati", 30 to "30 mnt", 60 to "1 jam", 120 to "2 jam", 240 to "4 jam").forEach { (m, label) ->
                    Pill(label, selected = autoStop == m, onClick = {
                        autoStop = m; TunnelSettings.setAutoStopMinutes(context, m)
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Failover antar profil", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Setelah 3 kali gagal berturut-turut, pindah otomatis ke profil berikutnya di daftar.",
                        color = TunnelColors.TextDim, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = failover,
                    onCheckedChange = { failover = it; TunnelSettings.setFailover(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = TunnelColors.Online, checkedThumbColor = Color(0xFF00241A)
                    )
                )
            }
        }

        SectionLabel("Kill switch")
        Panel {
            Text("Blokir internet saat VPN putus", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Aplikasi tidak bisa memaksanya sendiri. Android menyediakannya di pengaturan VPN sistem: " +
                    "aktifkan \"VPN selalu aktif\" dan \"Blokir koneksi tanpa VPN\" untuk Enjiws Tunnel.",
                color = TunnelColors.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TunnelColors.SurfaceHigh, contentColor = TunnelColors.Text
                )
            ) { Text("Buka pengaturan VPN") }
        }

        SectionLabel("Tentang")
        Panel {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text("Enjiws Tunnel ${version.orEmpty()}", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
            Text("SSH (direct-tcpip) dan HTTP CONNECT. DNS lewat tunnel.", color = TunnelColors.TextDim, fontSize = 12.sp)
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = apps,
            onDismiss = { showPicker = false },
            onDone = {
                apps = it
                TunnelSettings.setSplitApps(context, it)
                dirty = true
                showPicker = false
            }
        )
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = TunnelColors.Online, unselectedColor = TunnelColors.TextDim)
        )
        Text(label, color = TunnelColors.Text, fontSize = 14.sp)
    }
}

private data class AppItem(val pkg: String, val label: String)

@Composable
private fun AppPickerDialog(selected: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(TunnelColors.Bg).systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Pilih aplikasi", color = TunnelColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Cari aplikasi") }, colors = tunnelFieldColors()
            )
            val list = apps
            if (list == null) {
                Text("Memuat daftar aplikasi…", color = TunnelColors.TextDim, fontSize = 13.sp)
            }
            val shown = list.orEmpty().filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.pkg }) { app ->
                    val checked = app.pkg in picked
                    val icon = remember(app.pkg) {
                        runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { picked = if (checked) picked - app.pkg else picked + app.pkg }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        else Spacer(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = TunnelColors.Text, fontSize = 14.sp, maxLines = 1)
                            Text(app.pkg, color = TunnelColors.TextDim, fontSize = 11.sp, maxLines = 1)
                        }
                        Checkbox(
                            checked = checked, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = TunnelColors.Online, uncheckedColor = TunnelColors.TextDim,
                                checkmarkColor = Color(0xFF00241A)
                            )
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Batal", color = TunnelColors.TextDim)
                }
                Button(
                    onClick = { onDone(picked) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TunnelColors.Online, contentColor = Color(0xFF00241A))
                ) { Text("Simpan (${picked.size})", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
