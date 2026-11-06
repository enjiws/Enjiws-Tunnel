# Enjiws Tunnel

Native Android tunneling client focused on stability, efficient throughput, and battery-conscious operation.

## V0.2 scope
- `EnjiwsVpnService`: foreground VpnService, TUN via `Builder.establish()`, event-driven network tracking (`NetworkCallback`, tanpa polling), reconnect dengan exponential backoff + jitter, restart otomatis saat Wi-Fi/seluler berganti.
- `TunnelEngine`: titik colok engine (SSH/HTTP/TLS + tun2socks). Saat ini `IdleEngine` (placeholder).
- `TunnelConfig.CAPTURE_ALL_TRAFFIC = false`: aman untuk testing, internet normal tidak terganggu. Set `true` hanya setelah engine asli terpasang.
- UI baru: tema hitam / abu-abu / merah gelap, tombol connect beranimasi, uptime live.

## V0.1 scope
This foundation includes the Android/Kotlin/Compose project, a dark-green dashboard, basic in-app navigation, and a GitHub Actions debug-build workflow. It is **not yet a functional VPN client**. The VPN service, packet forwarding, SSH, HTTP proxy, TLS, DNS, and UDP engines are planned for later stages.

## Performance-first engineering rules
- No busy loops or frequent polling for connection state.
- Structured concurrency; cancel work when its owner stops.
- Keep networking and packet processing off the main thread.
- Use bounded exponential backoff for reconnect attempts.
- Keep TLS certificate verification enabled.
- Measure throughput, latency, packet loss, CPU, thermal behavior, and battery drain on real devices before claiming improvements.

## Build
GitHub Actions installs JDK 17 and Gradle 8.11.1, then runs:
`gradle --no-daemon :app:assembleDebug`

The APK is uploaded as the `Enjiws-Tunnel-debug-apk` workflow artifact.

## Security
Only use servers and networks you own or are authorized to use. Never commit passwords, tokens, or private keys.

## V0.3: engine SSH + payload/SNI + tun2socks

Alur: `TCP (langsung / via HTTP proxy) -> TLS+SNI (opsional) -> payload HTTP (opsional) -> SSH -> SOCKS5 lokal -> hev-socks5-tunnel -> TUN`

- Host key SSH disimpan dengan trust-on-first-use (tombol "Reset host key" di tab Profile).
- Profil + password disimpan terenkripsi (Android Keystore).
- DNS: query UDP port 53 diubah jadi DNS-over-TCP lewat SSH. UDP lain dibuang.
- IPv6 masuk TUN lalu dibuang (anti bocor).

### Wajib sekali: tambah submodule tun2socks
```bash
git submodule add https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
git submodule update --init --recursive
```
