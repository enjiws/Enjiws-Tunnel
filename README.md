# Enjiws Tunnel

Native Android tunneling client focused on stability, efficient throughput, and battery-conscious operation.

## V0.3: engine SSH + payload/SNI + tun2socks

Alur: `TCP (langsung / via HTTP proxy) -> TLS+SNI (opsional) -> payload HTTP (opsional) -> SSH -> SOCKS5 lokal -> hev-socks5-tunnel -> TUN`

- Engine SSH (sshj) + SOCKS5 lokal + tun2socks native (hev-socks5-tunnel, NDK).
- Host key SSH disimpan dengan trust-on-first-use (tombol "Reset host key" di tab Profile).
- Profil + password disimpan terenkripsi (Android Keystore).
- DNS: query UDP port 53 diubah jadi DNS-over-TCP lewat SSH. UDP lain diteruskan lewat relay UDP-over-TCP bila diaktifkan di profil (lihat "UDP & SlowDNS" di bawah).
- IPv6 masuk TUN lalu dibuang (anti bocor).
- `EnjiwsVpnService`: foreground VpnService, TUN via `Builder.establish()`, event-driven network tracking (`NetworkCallback`, tanpa polling), reconnect dengan exponential backoff + jitter, restart otomatis saat Wi-Fi/seluler berganti. Koneksi SSH dibuat dulu sebelum TUN dibuka (tidak ada blackhole saat handshake).
- UI: tema hitam / abu-abu / merah gelap, tombol connect beranimasi, uptime live.
- Import file `.hc` (HTTP Custom) tersedia di tab Profile — file disimpan utuh sebagai arsip; formatnya terenkripsi/tidak terdokumentasi sehingga belum bisa dipakai koneksi.

### Wajib sekali: tambah submodule tun2socks
```bash
git submodule add https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
git submodule update --init --recursive
```

## UDP & SlowDNS

### UDP forwarding (game / voice / QUIC)
SSH hanya meneruskan TCP. Supaya UDP jalan, paket UDP dibungkus jadi frame TCP
(UDP-over-TCP) dan dikirim ke relay kecil di VPS, yang meneruskannya sebagai UDP
asli. DNS (port 53) tidak butuh relay — tetap lewat DNS-over-TCP seperti biasa.

1. Di HP: tab Profile → nyalakan **UDP forwarding**, isi relay host (default
   `127.0.0.1`) dan port (default `7300`), tekan Save.
2. Di VPS: jalankan relaynya (Python 3, tanpa dependensi):
   ```bash
   wget https://raw.githubusercontent.com/enjiws/Enjiws-Tunnel/main/server/udp-relay.py
   python3 udp-relay.py
   ```
   Supaya jalan terus setelah reboot, pakai `server/udp-relay.service`
   (sesuaikan User dan path dengan VPS-mu, lalu `systemctl enable --now udp-relay`).
3. Tes: sambungkan tunnel, lalu main game / voice call / buka situs QUIC.
   Tanpa relay yang jalan, UDP non-DNS tetap dibuang (TCP dan DNS tidak terganggu).

Batasan: relay hanya mengirim UDP IPv4 (tujuan IPv6 dibuang dan dicatat di log);
UDP memang tidak dijamin — paket yang gagal dibuang dan aplikasi mengirim ulang
sendiri. Satu stream TCP per asosiasi UDP, pembacaan blocking tanpa polling,
jadi tidak ada beban baterai tambahan yang berarti.

### SlowDNS — belum ada
SlowDNS (tunnel lewat query DNS) butuh tiga hal yang belum tersedia:
(1) domain sendiri yang NS record-nya mengarah ke VPS, (2) daemon server
SlowDNS di VPS, (3) client DNS-tunnel di aplikasi (pengodean data ke subdomain,
fragmentasi, dan keandalan di atas UDP yang lossy). Kecepatannya juga lambat
(orde kbps) — cocok untuk jaringan yang hanya membuka port 53, bukan untuk
pemakaian harian. Ini stage tersendiri setelah domain disiapkan.

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
