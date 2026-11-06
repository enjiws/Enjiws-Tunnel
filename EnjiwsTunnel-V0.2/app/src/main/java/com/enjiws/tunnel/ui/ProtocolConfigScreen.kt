package com.enjiws.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enjiws.tunnel.profiles.ProtocolProfile
import com.enjiws.tunnel.profiles.ProtocolProfileStore
import java.util.UUID

private val protocols = listOf("SSH", "HTTP Proxy", "SSL/TLS", "SSH + SSL/TLS", "SSH + HTTP Proxy")

@Composable
fun ProtocolConfigScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saved = remember { mutableStateListOf<ProtocolProfile>() }
    var name by remember { mutableStateOf("My server") }
    var protocol by remember { mutableStateOf(protocols.first()) }
    var menu by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("8080") }
    var sni by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { saved.clear(); saved.addAll(ProtocolProfileStore.all(context)) }
    val needsSsh = protocol.contains("SSH")
    val needsProxy = protocol.contains("HTTP")
    val needsTls = protocol.contains("SSL")

    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connection profile", color = EnjiwsColors.Text, fontSize = 25.sp)
        Text("Buat profil server. Kredensial disimpan terenkripsi dengan Android Keystore.", color = EnjiwsColors.Grey, fontSize = 12.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("Protocol: $protocol  ▾") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            protocols.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { protocol = item; menu = false }) }
        }
        Text("Server", color = EnjiwsColors.RedGlow)
        OutlinedTextField(host, { host = it }, label = { Text("Server host / IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Server port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (needsSsh) {
            Text("SSH authentication", color = EnjiwsColors.RedGlow)
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Password (optional for key auth later)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
        if (needsProxy) {
            Text("HTTP proxy", color = EnjiwsColors.RedGlow)
            OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Proxy host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, label = { Text("Proxy port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(payload, { payload = it }, label = { Text("Custom HTTP payload (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        }
        if (needsTls) {
            Text("TLS settings", color = EnjiwsColors.RedGlow)
            OutlinedTextField(sni, { sni = it }, label = { Text("SNI / TLS server name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Button(onClick = {
            val p = port.toIntOrNull(); val pp = proxyPort.toIntOrNull() ?: 0
            message = when {
                name.isBlank() -> "Nama profil wajib diisi"
                host.isBlank() -> "Server host wajib diisi"
                p == null || p !in 1..65535 -> "Port server harus 1–65535"
                needsSsh && username.isBlank() -> "Username SSH wajib diisi"
                needsProxy && proxyHost.isBlank() -> "Proxy host wajib diisi"
                needsProxy && pp !in 1..65535 -> "Port proxy harus 1–65535"
                needsTls && sni.isBlank() -> "SNI wajib diisi untuk profil TLS"
                else -> null
            }
            if (message == null) {
                val profile = ProtocolProfile(UUID.randomUUID().toString(), name.trim(), protocol, host.trim(), p!!,
                    username.trim(), password, proxyHost.trim(), pp, sni.trim(), payload)
                runCatching { ProtocolProfileStore.save(context, profile) }
                    .onSuccess { saved.add(0, profile); message = "Profil tersimpan terenkripsi. Engine koneksi belum diaktifkan." }
                    .onFailure { message = it.message ?: "Gagal menyimpan profil" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        message?.let { Text(it, color = EnjiwsColors.RedGlow, fontSize = 12.sp) }
        Text("Saved profiles (${saved.size})", color = EnjiwsColors.Text, fontSize = 18.sp)
        saved.forEach { item ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = EnjiwsColors.Panel)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, color = EnjiwsColors.Text)
                        Text("${item.protocol} · ${item.host}:${item.port}", color = EnjiwsColors.Grey, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        runCatching { ProtocolProfileStore.delete(context, item.id) }
                        saved.removeAll { it.id == item.id }
                        message = "Profil dihapus"
                    }) { Text("Hapus", color = EnjiwsColors.RedGlow) }
                }
            }
        }
        Text("Catatan: ini adalah editor dan penyimpanan profil. SSH handshake, HTTP CONNECT, TLS transport, dan forwarding TUN belum dijalankan oleh versi ini. Sertifikat TLS nantinya tetap diverifikasi.", color = EnjiwsColors.Grey, fontSize = 11.sp)
    }
}
