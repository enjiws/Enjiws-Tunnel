# Enjiws Tunnel

Native Android tunneling client focused on stability, efficient throughput, and battery-conscious operation.

## V0.3 scope
- Profile manager with Android Storage Access Framework import for `.hc` files.
- Imported files are stored unchanged in app-private storage, with SHA-256 fingerprint and metadata.
- The current importer treats HTTP Custom files as opaque artifacts. It does not decrypt undocumented formats or expose credentials; imported files are not connectable until a documented/authorized format mapping is implemented.

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
