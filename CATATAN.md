# Enjiws Tunnel: update fitur (tahap 2)

Timpa file dengan path yang sama. Belum dikompilasi di sini.

## File baru
- vpn/TlsUtil.kt, vpn/TrafficHistory.kt, profiles/ProfileBackup.kt

## File berubah
- profiles/TunnelProfile.kt (field baru: privateKey, keyPassphrase, tls, sni, favorite), profiles/ProfileLink.kt
- vpn/SshEngine.kt, vpn/HttpConnectEngine.kt, vpn/EnjiwsVpnService.kt, vpn/TunnelState.kt, vpn/TunnelSettings.kt
- ui/ProfileScreen.kt, ui/HomeScreen.kt, ui/SettingsScreen.kt

## Penting
Kalau waktu build pertama lo mengganti import JSch (mis. `com.github.mwiede.jsch.*` -> `com.jcraft.jsch.*`),
lakukan hal yang sama di SshEngine.kt dan TlsUtil.kt (baris `import ... SocketFactory as JschSocketFactory`).
