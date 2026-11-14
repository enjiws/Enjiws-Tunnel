# Enjiws Tunnel

Native Android tunneling client focused on stability, efficient throughput, and battery-conscious operation.

## V0.3: engine SSH + payload/SNI + tun2socks

Alur: `TCP (langsung / via HTTP proxy) -> TLS+SNI (opsional) -> payload HTTP (opsional) -> SSH -> SOCKS5 lokal -> hev-socks5-tunnel -> TUN`

- Engine SSH (sshj) + SOCKS5 lokal + tun2socks native (hev-socks5-tunnel, NDK).
- Host key SSH disimpan dengan trust-on-first-use (tombol "Reset host key" di tab Profile).
- Profil + password disimpan terenkripsi (Android Keystore).
- DNS: query UDP port 53 diubah jadi DNS-over-TCP lewat SSH. UDP lain dibuang.
- IPv6 masuk TUN lalu dibuang (anti bocor).
- `EnjiwsVpnService`: foreground VpnService, TUN via `Builder.establish()`, event-driven network tracking (`NetworkCallback`, tanpa polling), reconnect dengan exponential backoff + jitter, restart otomatis saat Wi-Fi/seluler berganti. Koneksi SSH dibuat dulu sebelum TUN dibuka (tidak ada blackhole saat handshake).
- UI: tema hitam / abu-abu / merah gelap, tombol connect beranimasi, uptime live.
- Import file `.hc` (HTTP Custom) tersedia di tab Profile — file disimpan utuh sebagai arsip; formatnya terenkripsi/tidak terdokumentasi sehingga belum bisa dipakai koneksi.

### Wajib sekali: tambah submodule tun2socks
```bash
git submodule add https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
git submodule update --init --recursive
```

## Riwayat versi

- **V0.3**: engine SSH + payload/SNI + tun2socks (lihat di atas).
- **V0.2**: fondasi VpnService + reconnect backoff + UI merah gelap + import file .hc (arsip).
- **V0.1**: kerangka proyek Android/Kotlin/Compose + workflow GitHub Actions.

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
Workflow checkout memakai `submodules: recursive` supaya kode native hev ikut terambil.

## Security
Only use servers and networks you own or are authorized to use. Never commit passwords, tokens, or private keys.
