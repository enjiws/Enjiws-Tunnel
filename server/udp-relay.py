#!/usr/bin/env python3
"""EnjiwsTunnel UDP-over-TCP relay (sisi server, jalan di VPS).

SSH hanya meneruskan TCP, jadi paket UDP dari HP dibungkus jadi frame TCP,
dikirim lewat SSH ke relay ini, dan relay meneruskannya sebagai UDP asli.
Balasan dikembalikan lewat stream TCP yang sama.

Cara pakai di VPS:
    python3 udp-relay.py [--host 127.0.0.1] [--port 7300]
    (default 127.0.0.1 supaya hanya bisa dijangkau lewat SSH — lebih aman)
    Supaya jalan terus: salin udp-relay.service ke /etc/systemd/system/
    lalu: sudo systemctl enable --now udp-relay

Protokol (harus sama persis dengan app/.../engine/UdpOverTcp.kt):
    Frame: [panjang 4 byte big-endian][payload]
    Payload: [family 1 byte][alamat][port 2 byte big-endian][data UDP]
        family 4 = IPv4 (4 byte alamat)
        family 6 = IPv6 (16 byte alamat)
        family 3 = domain (1 byte panjang + nama domain)
    Balasan dari server memakai format yang sama (alamat sumber, selalu IP).
    Catatan: relay ini hanya mengirim UDP IPv4 (socket AF_INET); tujuan IPv6
    akan gagal terkirim dan dicatat di log.
"""
import argparse
import ipaddress
import select
import socket
import struct
import sys
import threading
import time

MAX_FRAME = 65554
IDLE_TIMEOUT = 120.0


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def parse_payload(payload):
    """Kembalikan (family, alamat_str, port, data) atau None bila rusak."""
    if len(payload) < 1 + 4 + 2:
        return None
    family = payload[0]
    if family == 4:
        if len(payload) < 1 + 4 + 2:
            return None
        addr = str(ipaddress.IPv4Address(payload[1:5]))
        off = 5
    elif family == 6:
        if len(payload) < 1 + 16 + 2:
            return None
        addr = str(ipaddress.IPv6Address(payload[1:17]))
        off = 17
    elif family == 3:
        n = payload[1]
        if len(payload) < 2 + n + 2:
            return None
        addr = payload[2:2 + n].decode("ascii", "ignore")
        off = 2 + n
    else:
        return None
    if len(payload) < off + 2:
        return None
    port = struct.unpack(">H", payload[off:off + 2])[0]
    return family, addr, port, payload[off + 2:]


def build_frame(family, addr_str, port, data):
    if family == 4:
        addr = ipaddress.IPv4Address(addr_str).packed
    elif family == 6:
        addr = ipaddress.IPv6Address(addr_str).packed
    else:
        raise ValueError("balasan hanya IPv4/IPv6")
    payload = bytes([family]) + addr + struct.pack(">H", port) + data
    return struct.pack(">I", len(payload)) + payload


def handle_client(tcp, client_addr):
    log(f"client {client_addr}")
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setblocking(False)
    tcp.setblocking(False)
    buf = bytearray()
    last_active = time.time()
    try:
        while True:
            if time.time() - last_active > IDLE_TIMEOUT:
                log(f"client {client_addr}: idle timeout")
                break
            r, _, _ = select.select([tcp, udp], [], [], 1.0)
            if tcp in r:
                try:
                    chunk = tcp.recv(65536)
                except OSError:
                    break
                if not chunk:
                    break
                buf += chunk
                last_active = time.time()
                while True:
                    if len(buf) < 4:
                        break
                    (length,) = struct.unpack(">I", buf[:4])
                    if length <= 0 or length > MAX_FRAME:
                        log(f"client {client_addr}: frame rusak (len={length})")
                        return
                    if len(buf) < 4 + length:
                        break
                    payload = bytes(buf[4:4 + length])
                    del buf[:4 + length]
                    parsed = parse_payload(payload)
                    if parsed is None:
                        continue
                    family, addr, port, data = parsed
                    if family == 6:
                        log(f"IPv6 belum didukung relay: {addr}:{port} (paket dibuang)")
                        continue
                    try:
                        udp.sendto(data, (addr, port))
                    except OSError as e:
                        log(f"udp send gagal ke {addr}:{port}: {e}")
            if udp in r:
                try:
                    data, (src_ip, src_port) = udp.recvfrom(65535)
                except OSError:
                    continue
                last_active = time.time()
                try:
                    family = 6 if ":" in src_ip else 4
                    tcp.sendall(build_frame(family, src_ip, src_port, data))
                except OSError:
                    break
    finally:
        try:
            udp.close()
        except OSError:
            pass
        try:
            tcp.close()
        except OSError:
            pass
        log(f"client {client_addr}: tutup")


def main():
    ap = argparse.ArgumentParser(description="EnjiwsTunnel UDP-over-TCP relay")
    ap.add_argument("--host", default="127.0.0.1",
                    help="alamat listen (default 127.0.0.1, hanya via SSH)")
    ap.add_argument("--port", type=int, default=7300)
    args = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(16)
    log(f"udp-relay jalan di {args.host}:{args.port} (Ctrl+C untuk berhenti)")
    try:
        while True:
            tcp, addr = srv.accept()
            threading.Thread(target=handle_client, args=(tcp, addr), daemon=True).start()
    except KeyboardInterrupt:
        log("berhenti")


if __name__ == "__main__":
    main()
