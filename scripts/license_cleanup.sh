#!/usr/bin/env bash
set -euo pipefail

# 1. Hapus berkas yang mengandung salinan dari BlockAds (GPLv3)
git rm -f app/src/main/assets/adblock/youtube_processor.js || rm -f app/src/main/assets/adblock/youtube_processor.js

# 2. Hapus aset pihak ketiga / non-kode dari repo
git rm -f public/assets/aistudio/meta-muse-seeklogo_83f3c66f.png || rm -f public/assets/aistudio/meta-muse-seeklogo_83f3c66f.png
git rm -f public/assets/aistudio/*.mp4 || rm -f public/assets/aistudio/*.mp4
git rm -f public/assets/aistudio/*.jpg || rm -f public/assets/aistudio/*.jpg

echo "Pembersihan tahap 1 selesai. Lanjutkan dengan mengganti HeuristicsEngine.kt (KODE C), menambah youtube_guard.js (KODE E), dan mem-patch AdBlockWebViewClient.kt (KODE F)."
