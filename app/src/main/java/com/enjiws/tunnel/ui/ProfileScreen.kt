package com.enjiws.tunnel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjiws.tunnel.profiles.ImportedProfile
import com.enjiws.tunnel.profiles.ProfileBackup
import com.enjiws.tunnel.profiles.ProfileLink
import com.enjiws.tunnel.profiles.ProfileProbe
import com.enjiws.tunnel.profiles.ProfileRepo
import com.enjiws.tunnel.profiles.ProfileStore
import com.enjiws.tunnel.profiles.TunnelProfile
import com.enjiws.tunnel.profiles.TunnelType
import com.enjiws.tunnel.vpn.EnjiwsVpnService
import com.enjiws.tunnel.vpn.Status
import com.enjiws.tunnel.vpn.TunnelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PingState {
    data object Testing : PingState
    data object Fail : PingState
    data class Ok(val ms: Long) : PingState
}

private data class EditorTarget(val profile: TunnelProfile?, val exists: Boolean)

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val profiles by ProfileRepo.profiles.collectAsStateWithLifecycle()
    val activeId by ProfileRepo.activeId.collectAsStateWithLifecycle()
    val pings = remember { mutableStateMapOf<String, PingState>() }
    val imports = remember { mutableStateListOf<ImportedProfile>() }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<TunnelProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { ProfileStore.all(context) }
        imports.clear(); imports.addAll(saved)
    }
    LaunchedEffect(message) {
        if (message != null) { delay(4000); message = null }
    }

    fun testPing(p: TunnelProfile) {
        scope.launch {
            pings[p.id] = PingState.Testing
            val ms = ProfileProbe.ping(p.host, p.port)
            pings[p.id] = if (ms == null) PingState.Fail else PingState.Ok(ms)
        }
    }

    fun pasteLinks() {
        val found = ProfileLink.parseAll(clipboard.getText()?.text.orEmpty())
        when {
            found.isEmpty() -> message = "Clipboard tidak berisi link ssh:// atau http://"
            found.size == 1 -> editor = EditorTarget(found[0], exists = false)
            else -> scope.launch {
                found.forEach { ProfileRepo.save(context, it, activate = false) }
                message = "${found.size} profil diimpor. Lengkapi password di tiap profil bila perlu."
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ProfileBackup.write(context, uri, profiles) } }
                .onSuccess { message = "Profil diekspor (password dan key tidak ikut)." }
                .onFailure { message = it.message ?: "Ekspor gagal" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ProfileBackup.read(context, uri) } }
                .onSuccess { list ->
                    list.forEach { ProfileRepo.save(context, it, activate = false) }
                    message = "${list.size} profil diimpor. Isi ulang password/key di tiap profil."
                }
                .onFailure { message = it.message ?: "Impor gagal" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ProfileStore.importHc(context, uri) } }
                    .onSuccess {
                        imports.removeAll { old -> old.id == it.id }
                        imports.add(0, it)
                        message = "File disimpan. Format .hc masih perlu decoder kompatibel sebelum bisa dipakai koneksi."
                    }
                    .onFailure { message = it.message ?: "Import gagal" }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Profil", color = TunnelColors.Text, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { editor = EditorTarget(null, exists = false) }) {
                Icon(Icons.Default.Add, contentDescription = "Profil baru", tint = TunnelColors.Online)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TunnelColors.TextDim)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Tes semua ping") },
                        onClick = { menuOpen = false; profiles.forEach { testPing(it) } })
                    DropdownMenuItem(text = { Text("Tempel link dari clipboard") },
                        onClick = { menuOpen = false; pasteLinks() })
                    DropdownMenuItem(text = { Text("Ekspor profil (tanpa password)") },
                        onClick = { menuOpen = false; exportLauncher.launch("enjiws-profil.json") })
                    DropdownMenuItem(text = { Text("Impor profil dari file") },
                        onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "*/*")) })
                }
            }
        }

        message?.let { Text(it, color = TunnelColors.Busy, fontSize = 12.sp) }

        if (profiles.isEmpty()) {
            Panel {
                Text("Belum ada profil", color = TunnelColors.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ketuk + untuk membuat profil SSH atau HTTP proxy, atau tempel link ssh:// / http:// dari clipboard.",
                    color = TunnelColors.TextDim, fontSize = 13.sp
                )
            }
        }

        profiles.sortedByDescending { it.favorite }.forEach { p ->
            ProfileCard(
                profile = p,
                active = p.id == activeId,
                ping = pings[p.id],
                onSelect = { scope.launch { ProfileRepo.select(context, p.id); message = "\"${p.name}\" aktif." } },
                onPing = { testPing(p) },
                onEdit = { editor = EditorTarget(p, exists = true) },
                onToggleFavorite = {
                    scope.launch { ProfileRepo.save(context, p.copy(favorite = !p.favorite), activate = false) }
                },
                onDuplicate = {
                    scope.launch {
                        ProfileRepo.save(
                            context,
                            p.copy(id = "p_${System.currentTimeMillis()}", name = "${p.name} (salinan)"),
                            activate = false
                        )
                    }
                },
                onCopyLink = {
                    clipboard.setText(AnnotatedString(ProfileLink.format(p)))
                    message = "Link disalin (tanpa password)."
                },
                onDelete = { deleteTarget = p }
            )
        }

        // ---- Import .hc (disimpan utuh, belum bisa dipakai koneksi) ----
        SectionLabel("Impor HTTP Custom (.hc)")
        Panel {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pilih file .hc", color = TunnelColors.Text)
            }
            if (imports.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Belum ada file konfigurasi yang diimpor.", color = TunnelColors.TextDim, fontSize = 12.sp)
            }
            imports.forEach { item ->
                Spacer(Modifier.height(10.dp))
                Text(item.name, color = TunnelColors.Text, fontSize = 14.sp)
                Text(
                    "${item.sizeBytes} bytes · SHA-256 ${item.sha256.take(16)}…",
                    color = TunnelColors.TextDim, fontSize = 11.sp
                )
                Text(
                    "Belum dapat dijalankan: format internal .hc belum dipetakan ke profil Enjiws.",
                    color = TunnelColors.TextDim, fontSize = 12.sp
                )
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ProfileStore.delete(context, item) }
                        imports.remove(item)
                    }
                }) { Text("Hapus", color = TunnelColors.Danger) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "File .hc disimpan apa adanya di penyimpanan privat aplikasi. Enjiws tidak mengekstrak " +
                    "password, key, atau data server dari format terenkripsi/tidak terdokumentasi.",
                color = TunnelColors.TextDim, fontSize = 11.sp
            )
        }
    }

    editor?.let { target ->
        ProfileEditorSheet(
            initial = target.profile,
            title = if (target.exists) "Edit profil" else "Profil baru",
            onDismiss = { editor = null },
            onSave = { saved ->
                editor = null
                scope.launch {
                    ProfileRepo.save(context, saved, activate = !target.exists && activeId == null)
                    // Mengedit profil yang sedang dipakai: terapkan langsung.
                    if (target.exists && saved.id == activeId &&
                        TunnelStateHolder.state.value.status != Status.DISCONNECTED
                    ) EnjiwsVpnService.reconnect(context)
                    message = "Profil \"${saved.name}\" disimpan."
                }
            }
        )
    }

    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = TunnelColors.Surface,
            title = { Text("Hapus profil?", color = TunnelColors.Text) },
            text = { Text("\"${p.name}\" akan dihapus beserta password-nya.", color = TunnelColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (p.id == activeId && TunnelStateHolder.state.value.status != Status.DISCONNECTED) {
                        message = "Putuskan koneksi dulu sebelum menghapus profil yang sedang dipakai."
                    } else scope.launch { ProfileRepo.delete(context, p.id) }
                }) { Text("Hapus", color = TunnelColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Batal", color = TunnelColors.TextDim) }
            }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: TunnelProfile,
    active: Boolean,
    ping: PingState?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Panel(highlight = if (active) TunnelColors.Online else null, onClick = onSelect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name, color = TunnelColors.Text, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    if (profile.favorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, contentDescription = "Favorit", tint = TunnelColors.Busy, modifier = Modifier.size(14.dp))
                    }
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text("AKTIF", color = TunnelColors.Online, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "${profile.type.name} · ${profile.host}:${profile.port}" +
                        (if (profile.tls) " · TLS" else "") +
                        (if (profile.privateKey.isNotBlank()) " · KEY" else ""),
                    color = TunnelColors.TextDim, fontSize = 12.sp, maxLines = 1
                )
            }
            PingChip(ping, onPing)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TunnelColors.TextDim)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplikat") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text(if (profile.favorite) "Hapus favorit" else "Jadikan favorit") },
                        onClick = { menu = false; onToggleFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text("Salin link") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; onCopyLink() }
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus", color = TunnelColors.Danger) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PingChip(ping: PingState?, onClick: () -> Unit) {
    val (text, color) = when (ping) {
        null -> "Tes" to TunnelColors.TextDim
        PingState.Testing -> "…" to TunnelColors.TextDim
        PingState.Fail -> "Gagal" to TunnelColors.Danger
        is PingState.Ok -> "${ping.ms} ms" to when {
            ping.ms < 150 -> TunnelColors.Online
            ping.ms < 400 -> TunnelColors.Busy
            else -> TunnelColors.Danger
        }
    }
    TextButton(onClick = onClick) { Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

private val DnsPresets = listOf(
    "Cloudflare" to "1.1.1.1",
    "Google" to "8.8.8.8",
    "Quad9" to "9.9.9.9",
    "AdGuard" to "94.140.14.14"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(
    initial: TunnelProfile?,
    title: String,
    onDismiss: () -> Unit,
    onSave: (TunnelProfile) -> Unit
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial?.type ?: TunnelType.SSH) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.password.orEmpty()) }
    var dns by remember { mutableStateOf(initial?.dns ?: "1.1.1.1") }
    var key by remember { mutableStateOf(initial?.privateKey.orEmpty()) }
    var keyPass by remember { mutableStateOf(initial?.keyPassphrase.orEmpty()) }
    var tls by remember { mutableStateOf(initial?.tls ?: false) }
    var sni by remember { mutableStateOf(initial?.sni.orEmpty()) }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            error = when {
                text == null -> "Gagal membaca file key."
                text.length > 20_000 -> "File terlalu besar untuk private key."
                !text.contains("-----BEGIN") -> "Bukan file private key (PEM/OpenSSH)."
                else -> { key = text.trim() + "\n"; null }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = TunnelColors.Surface, contentColor = TunnelColors.Text) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = TunnelColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TunnelType.values().forEach { t ->
                    Pill(t.name, selected = type == t, onClick = {
                        // Ganti port default hanya kalau pengguna belum mengubahnya sendiri.
                        val oldDefault = if (type == TunnelType.SSH) "22" else "8080"
                        if (port == oldDefault) port = if (t == TunnelType.SSH) "22" else "8080"
                        type = t
                    })
                }
            }
            val colors = tunnelFieldColors()
            OutlinedTextField(name, { name = it }, label = { Text("Nama profil") }, singleLine = true,
                colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Host / IP server") }, singleLine = true,
                colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(user, { user = it.trim() },
                label = { Text(if (type == TunnelType.SSH) "Username SSH" else "Username proxy (opsional)") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it },
                label = { Text(if (type == TunnelType.SSH) "Password (kosongkan kalau pakai key)" else "Password") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(
                            if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Tampilkan password", tint = TunnelColors.TextDim
                        )
                    }
                })

            if (type == TunnelType.SSH) {
                SectionLabel("Private key (opsional)")
                if (key.isBlank()) {
                    OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Pilih file private key", color = TunnelColors.Text)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Key terpasang (${key.length} karakter)", color = TunnelColors.Online,
                            fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { key = ""; keyPass = "" }) { Text("Hapus", color = TunnelColors.Danger) }
                    }
                    OutlinedTextField(keyPass, { keyPass = it }, label = { Text("Passphrase key (kalau ada)") },
                        singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation())
                }
            }

            SectionLabel("TLS")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bungkus koneksi dengan TLS", color = TunnelColors.Text, fontSize = 14.sp)
                    Text(
                        "Untuk server di belakang stunnel / Nginx / CDN milikmu. Sertifikat selalu diverifikasi.",
                        color = TunnelColors.TextDim, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = tls, onCheckedChange = { tls = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = TunnelColors.Online, checkedThumbColor = Color(0xFF00241A))
                )
            }
            if (tls) {
                OutlinedTextField(sni, { sni = it.trim() },
                    label = { Text("Hostname sertifikat / SNI (kosong = host server)") },
                    singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            }

            SectionLabel("DNS (lewat tunnel)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DnsPresets.forEach { (label, ip) -> Pill(label, selected = dns == ip, onClick = { dns = ip }) }
            }
            OutlinedTextField(dns, { dns = it.trim() }, label = { Text("DNS server") }, singleLine = true,
                colors = colors, modifier = Modifier.fillMaxWidth())

            error?.let { Text(it, color = TunnelColors.Danger, fontSize = 12.sp) }

            Button(
                onClick = {
                    val p = port.toIntOrNull()
                    val ssh = type == TunnelType.SSH
                    error = when {
                        host.isBlank() -> "Isi host / IP server dulu."
                        p == null || p !in 1..65_535 -> "Port tidak valid (1-65535)."
                        ssh && user.isBlank() -> "Isi username SSH."
                        ssh && pass.isBlank() && key.isBlank() -> "Isi password atau pilih private key."
                        else -> null
                    }
                    if (error == null && p != null) {
                        onSave(
                            TunnelProfile(
                                id = initial?.id ?: "p_${System.currentTimeMillis()}",
                                name = name.ifBlank { "$host:$p" },
                                type = type, host = host, port = p,
                                username = user, password = pass,
                                dns = dns.ifBlank { "1.1.1.1" },
                                privateKey = if (ssh) key else "",
                                keyPassphrase = if (ssh) keyPass else "",
                                tls = tls, sni = if (tls) sni else "",
                                favorite = initial?.favorite ?: false
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TunnelColors.Online, contentColor = Color(0xFF00241A)
                )
            ) { Text("Simpan", fontWeight = FontWeight.Bold) }
        }
    }
}
