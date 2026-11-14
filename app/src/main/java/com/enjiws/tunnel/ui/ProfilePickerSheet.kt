package com.enjiws.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enjiws.tunnel.profiles.TunnelProfile

/** Pemilih server cepat dari layar Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePickerSheet(
    profiles: List<TunnelProfile>,
    activeId: String?,
    onPick: (TunnelProfile) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = TunnelColors.Surface, contentColor = TunnelColors.Text) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Pilih server", color = TunnelColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (profiles.isEmpty()) {
                Text("Belum ada profil.", color = TunnelColors.TextDim, fontSize = 13.sp)
            }
            profiles.forEach { p ->
                Panel(highlight = if (p.id == activeId) TunnelColors.Online else null, onClick = { onPick(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = TunnelColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${p.type.name} · ${p.host}:${p.port}", color = TunnelColors.TextDim, fontSize = 12.sp, maxLines = 1)
                        }
                        RadioButton(
                            selected = p.id == activeId, onClick = { onPick(p) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TunnelColors.Online, unselectedColor = TunnelColors.TextDim
                            )
                        )
                    }
                }
            }
            TextButton(onClick = onManage) { Text("Kelola profil", color = TunnelColors.Info) }
        }
    }
}
