package com.enjiws.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enjiws.tunnel.data.Profile
import com.enjiws.tunnel.data.ProfileStore

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ProfileStore.get(context) }
    val initial = remember { store.load() ?: Profile() }

    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var tls by remember { mutableStateOf(initial.tlsEnabled) }
    var sni by remember { mutableStateOf(initial.sni) }
    var insecure by remember { mutableStateOf(initial.tlsInsecure) }
    var payload by remember { mutableStateOf(initial.payload) }
    var proxyHost by remember { mutableStateOf(initial.proxyHost) }
    var proxyPort by remember { mutableStateOf(initial.proxyPort.toString()) }
    var dns by remember { mutableStateOf(initial.dns) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Profile", color = EnjiwsColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Use only servers you own or are allowed to use.",
            color = EnjiwsColors.Grey, fontSize = 13.sp
        )

        Field("Server host", host, { host = it }, KeyboardType.Uri)
        Field("SSH port", port, { port = it.filter(Char::isDigit) }, KeyboardType.Number)
        Field("Username", user, { user = it })
        Field("Password", pass, { pass = it }, KeyboardType.Password, secret = true)

        SwitchRow("TLS (SSL)", tls) { tls = it }
        if (tls) {
            Field("SNI (blank = server host)", sni, { sni = it }, KeyboardType.Uri)
            SwitchRow("Skip certificate check", insecure) { insecure = it }
        }

        Field("HTTP proxy host (optional)", proxyHost, { proxyHost = it }, KeyboardType.Uri)
        if (proxyHost.isNotBlank()) {
            Field("HTTP proxy port", proxyPort, { proxyPort = it.filter(Char::isDigit) }, KeyboardType.Number)
        }
        Field(
            "Payload (optional)", payload, { payload = it },
            minLines = 3, hint = "GET / HTTP/1.1[crlf]Host: example.com[crlf]Upgrade: websocket[crlf][crlf]"
        )
        Field("DNS server", dns, { dns = it }, KeyboardType.Uri)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val p = Profile(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = user.trim(),
                        password = pass,
                        tlsEnabled = tls,
                        sni = sni.trim(),
                        tlsInsecure = insecure,
                        payload = payload,
                        proxyHost = proxyHost.trim(),
                        proxyPort = proxyPort.toIntOrNull() ?: 8080,
                        dns = dns.trim().ifBlank { "1.1.1.1" }
                    )
                    if (p.isValid) {
                        store.save(p)
                        info = "Saved"
                    } else {
                        info = "Host and username are required"
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EnjiwsColors.Red),
                modifier = Modifier.weight(1f)
            ) { Text("Save") }

            OutlinedButton(
                onClick = {
                    store.forgetHostKey(host.trim(), port.toIntOrNull() ?: 22)
                    info = "Pinned host key cleared"
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Reset host key", color = EnjiwsColors.GreyLight) }
        }
        info?.let { Text(it, color = EnjiwsColors.Grey, fontSize = 13.sp) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    minLines: Int = 1,
    hint: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it, color = EnjiwsColors.Outline) } },
        singleLine = minLines == 1,
        minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EnjiwsColors.RedGlow,
            unfocusedBorderColor = EnjiwsColors.Outline,
            focusedLabelColor = EnjiwsColors.RedGlow,
            unfocusedLabelColor = EnjiwsColors.Grey,
            cursorColor = EnjiwsColors.RedGlow,
            focusedTextColor = EnjiwsColors.Text,
            unfocusedTextColor = EnjiwsColors.Text,
            focusedContainerColor = EnjiwsColors.Charcoal,
            unfocusedContainerColor = EnjiwsColors.Charcoal
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = EnjiwsColors.Text, fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = EnjiwsColors.Red,
                checkedThumbColor = EnjiwsColors.Text,
                uncheckedTrackColor = EnjiwsColors.Panel,
                uncheckedThumbColor = EnjiwsColors.Grey,
                uncheckedBorderColor = EnjiwsColors.Outline
            )
        )
    }
}
