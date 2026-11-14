#!/bin/sh
# EnjiwsTunnel Stage 0: hapus folder bersarang usang EnjiwsTunnel-V0.2/.
#
# Folder itu salinan V0.2 dari 6 Nov yang TIDAK dibangun (settings.gradle.kts
# hanya include root :app). Isi yang unik di dalamnya — desain multi-profil
# terenkripsi (ProtocolProfileStore) dan editornya — TETAP tersimpan di git
# history (commit eba9867) dan bisa diambil lagi dengan:
#   git show eba9867:EnjiwsTunnel-V0.2/app/src/main/java/com/enjiws/tunnel/profiles/ProtocolProfileStore.kt
#
# Jalankan dari root repo:  sh cleanup-stage0.sh
# Lalu cek: git status  (harus: 21 file deleted + README.md + ProfileScreen.kt modified)
set -e
cd "$(dirname "$0")"
git rm -r --ignore-unmatch EnjiwsTunnel-V0.2
echo "OK: EnjiwsTunnel-V0.2 dihapus dari index. Cek git status, lalu commit + push."
