package com.enjiws.tunnel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enjiws.tunnel.profiles.ImportedProfile
import com.enjiws.tunnel.profiles.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { mutableStateListOf<ImportedProfile>() }
    varStatus(profiles, context)
    var messageState = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { ProfileStore.importHc(context, uri) }
                }
                result.onSuccess {
                    profiles.removeAll { old -> old.id == it.id }
                    profiles.add(0, it)
                    messageState.value = "File disimpan. Format .hc masih perlu decoder kompatibel sebelum bisa dipakai koneksi."
                }.onFailure { messageState.value = it.message ?: "Import gagal" }
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Profiles", color = EnjiwsColors.Text, fontSize = 26.sp)
        Text("Simpan konfigurasi tunnel dan kelola file impor.", color = EnjiwsColors.Grey)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import HTTP Custom (.hc)")
        }
        messageState.value?.let { Text(it, color = EnjiwsColors.RedGlow, fontSize = 12.sp) }
        if (profiles.isEmpty()) {
            Text("Belum ada file konfigurasi yang diimpor.", color = EnjiwsColors.Grey)
        }
        profiles.forEach { profile ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = EnjiwsColors.Panel)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(profile.name, color = EnjiwsColors.Text, fontSize = 15.sp)
                    Text("HTTP Custom · file tersimpan", color = EnjiwsColors.RedGlow, fontSize = 12.sp)
                    Text("${profile.sizeBytes} bytes · SHA-256 ${profile.sha256.take(16)}…", color = EnjiwsColors.Grey, fontSize = 11.sp)
                    Text("Belum dapat dijalankan: format internal .hc belum dipetakan ke profil Enjiws.", color = EnjiwsColors.Grey, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ProfileStore.delete(context, profile) }
                                profiles.remove(profile)
                            }
                        }) { Text("Hapus", color = EnjiwsColors.RedGlow) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Catatan: file .hc disimpan apa adanya di penyimpanan privat aplikasi. Enjiws tidak mengekstrak password, key, atau data server dari format terenkripsi/tidak terdokumentasi.", color = EnjiwsColors.Grey, fontSize = 12.sp)
    }
}

@Composable
private fun varStatus(
    profiles: androidx.compose.runtime.snapshots.SnapshotStateList<ImportedProfile>,
    context: android.content.Context
) {
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { ProfileStore.all(context) }
        profiles.clear(); profiles.addAll(saved)
    }
}
