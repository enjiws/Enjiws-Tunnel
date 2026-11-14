package com.enjiws.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Payload generator: merakit teks payload HTTP dari form, bukan ketik mentah.
 *
 * Format yang dihasilkan SAMA dengan yang dimengerti TunnelSocket.renderPayload():
 * baris dipisah token [crlf], placeholder [host] / [port] / [host_port] /
 * [protocol] dipertahankan apa adanya, dan payload selalu diakhiri baris kosong
 * ([crlf][crlf]) supaya server membalas. Generator tidak mengubah engine sama sekali.
 */

data class PayloadHeader(val name: String, val value: String)

data class PayloadForm(
    val method: String = "GET",
    val path: String = "/",
    val protocol: String = "HTTP/1.1",
    val headers: List<PayloadHeader> = listOf(PayloadHeader("Host", "[host]"))
)

val PAYLOAD_METHODS = listOf("GET", "POST", "CONNECT", "HEAD", "OPTIONS", "PUT")
val PAYLOAD_PROTOCOLS = listOf("HTTP/1.1", "HTTP/1.0")

/** Preset siap pakai (nama, form). */
fun payloadPresets(): List<Pair<String, PayloadForm>> = listOf(
    "GET standar" to PayloadForm(
        "GET", "/", "HTTP/1.1",
        listOf(PayloadHeader("Host", "[host]"))
    ),
    "WebSocket" to PayloadForm(
        "GET", "/", "HTTP/1.1",
        listOf(
            PayloadHeader("Host", "[host]"),
            PayloadHeader("Upgrade", "websocket"),
            PayloadHeader("Connection", "Upgrade")
        )
    ),
    "CONNECT" to PayloadForm(
        "CONNECT", "[host_port]", "HTTP/1.1",
        listOf(PayloadHeader("Host", "[host_port]"))
    ),
    "CDN / Fronting" to PayloadForm(
        "GET", "/", "HTTP/1.1",
        listOf(
            PayloadHeader("Host", "[host]"),
            PayloadHeader("X-Online-Host", "[host]"),
            PayloadHeader("Connection", "keep-alive")
        )
    ),
    "POST kosong" to PayloadForm(
        "POST", "/", "HTTP/1.1",
        listOf(
            PayloadHeader("Host", "[host]"),
            PayloadHeader("Content-Length", "0")
        )
    )
)

/** Header yang sering dipakai (nama, nilai default). Ketuk untuk menambah baris. */
val COMMON_HEADERS = listOf(
    "Host" to "[host]",
    "User-Agent" to "EnjiwsTunnel",
    "Connection" to "keep-alive",
    "Upgrade" to "websocket",
    "Proxy-Connection" to "keep-alive",
    "X-Online-Host" to "[host]"
)

/** Rakit teks payload dari form (format token [crlf], sama seperti sebelumnya). */
fun buildPayload(form: PayloadForm): String {
    val sb = StringBuilder()
    sb.append(form.method.trim().ifBlank { "GET" })
    sb.append(' ')
    sb.append(form.path.trim().ifBlank { "/" })
    sb.append(' ')
    sb.append(form.protocol.trim().ifBlank { "HTTP/1.1" })
    sb.append("[crlf]")
    form.headers.filter { it.name.isNotBlank() }.forEach { h ->
        sb.append(h.name.trim()).append(": ").append(h.value).append("[crlf]")
    }
    sb.append("[crlf]")
    return sb.toString()
}

/** Urai payload yang sudah ada kembali ke form. Null bila tidak bisa diurai. */
fun parsePayload(text: String): PayloadForm? {
    val normalized = text.replace("\r\n", "[crlf]").replace("\n", "[crlf]")
    val lines = normalized.split("[crlf]").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val first = lines[0].split(" ").filter { it.isNotEmpty() }
    if (first.size != 3) return null
    if (first[0] !in PAYLOAD_METHODS) return null
    if (first[2] !in PAYLOAD_PROTOCOLS) return null
    val headers = lines.drop(1).mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else PayloadHeader(
            line.substring(0, idx).trim(),
            line.substring(idx + 1).trim()
        )
    }
    return PayloadForm(method = first[0], path = first[1], protocol = first[2], headers = headers)
}

/** Tampilkan payload seperti yang akan diterima server (placeholder diisi host/port profil). */
fun previewPayload(payload: String, host: String, port: Int): String {
    return payload
        .replace("[host_port]", "${host.ifBlank { "example.com" }}:$port")
        .replace("[host]", host.ifBlank { "example.com" })
        .replace("[port]", port.toString())
        .replace("[protocol]", "HTTP/1.1")
        .replace("[crlf]", "\n")
        .replace("[cr]", "\n")
        .replace("[lf]", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
}

@Composable
fun PayloadGenerator(
    host: String,
    port: Int,
    initialPayload: String,
    onUse: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var form by remember { mutableStateOf(parsePayload(initialPayload) ?: PayloadForm()) }
    var lastHeader by remember { mutableIntStateOf(-1) }
    val generated = buildPayload(form)
    val preview = previewPayload(generated, host, port)

    fun updateHeader(idx: Int, h: PayloadHeader) {
        val list = form.headers.toMutableList()
        if (idx in list.indices) {
            list[idx] = h
            form = form.copy(headers = list)
        }
    }

    fun removeHeader(idx: Int) {
        val list = form.headers.toMutableList()
        if (idx in list.indices) {
            list.removeAt(idx)
            form = form.copy(headers = list)
            if (lastHeader >= list.size) lastHeader = list.size - 1
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Payload generator",
            color = EnjiwsColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "Membuka generator tidak mengubah apa pun sampai kamu tekan \"Pakai payload ini\". " +
                "Token yang tersedia: [host] [port] [host_port] [protocol] [crlf].",
            color = EnjiwsColors.Grey, fontSize = 12.sp, lineHeight = 17.sp
        )

        Text("Preset", color = EnjiwsColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        payloadPresets().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, preset) ->
                    ChoiceButton(name, selected = false) { form = preset }
                }
            }
        }

        Text("Method", color = EnjiwsColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        PAYLOAD_METHODS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    ChoiceButton(m, selected = form.method == m) { form = form.copy(method = m) }
                }
            }
        }

        OutlinedTextField(
            value = form.path,
            onValueChange = { form = form.copy(path = it) },
            label = { Text("Path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PAYLOAD_PROTOCOLS.forEach { p ->
                ChoiceButton(p, selected = form.protocol == p) { form = form.copy(protocol = p) }
            }
        }

        Text("Header", color = EnjiwsColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        form.headers.forEachIndexed { idx, h ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = h.name,
                    onValueChange = { updateHeader(idx, h.copy(name = it)) },
                    label = { Text("Nama") },
                    singleLine = true,
                    modifier = Modifier.weight(0.9f)
                )
                OutlinedTextField(
                    value = h.value,
                    onValueChange = {
                        updateHeader(idx, h.copy(value = it))
                        lastHeader = idx
                    },
                    label = { Text("Nilai") },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f)
                )
                TextButton(onClick = { removeHeader(idx) }) {
                    Text("✕", color = EnjiwsColors.RedGlow)
                }
            }
        }
        Button(
            onClick = {
                val list = form.headers.toMutableList()
                list.add(PayloadHeader("", ""))
                form = form.copy(headers = list)
                lastHeader = list.size - 1
            }
        ) { Text("Tambah header") }

        Text("Header umum (ketuk untuk menambah):", color = EnjiwsColors.Grey, fontSize = 12.sp)
        COMMON_HEADERS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, value) ->
                    ChoiceButton(name, selected = false) {
                        val list = form.headers.toMutableList()
                        list.add(PayloadHeader(name, value))
                        form = form.copy(headers = list)
                        lastHeader = list.size - 1
                    }
                }
            }
        }

        Text("Sisipkan token ke nilai header terakhir diedit:", color = EnjiwsColors.Grey, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("[host]", "[port]", "[host_port]").forEach { token ->
                ChoiceButton(token, selected = false) {
                    if (lastHeader in form.headers.indices) {
                        val h = form.headers[lastHeader]
                        updateHeader(lastHeader, h.copy(value = h.value + token))
                    }
                }
            }
        }

        Text("Preview (yang diterima server):", color = EnjiwsColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = EnjiwsColors.Charcoal)
        ) {
            Text(
                preview,
                color = EnjiwsColors.Text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(14.dp)
            )
        }
        Text(
            "Payload selalu diakhiri baris kosong ([crlf][crlf]) supaya server membalas.",
            color = EnjiwsColors.Grey, fontSize = 12.sp
        )

        Button(
            onClick = { onUse(generated) },
            colors = ButtonDefaults.buttonColors(containerColor = EnjiwsColors.Red),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Pakai payload ini") }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) EnjiwsColors.Red else EnjiwsColors.Panel,
            contentColor = EnjiwsColors.Text
        )
    ) { Text(text, fontSize = 13.sp) }
}
