#!/usr/bin/env bash
set -euo pipefail

echo "=== Pemeriksaan kata kunci sensitif lisensi ==="
if grep -RIn --exclude-dir=.git --exclude-dir=build --exclude="ATRIBUSI.md" --exclude="license_check.sh" --exclude="license_cleanup.sh" \
    -E "BlockAds|pass-with-high-score|youtube_sanitizer|browser_rules|BrowserAdBlocker|funcGetvastAdx|funcJWonReadyVAST|bannerAdxAllowed|hideInplayerBanner|__ytSanitizerInstalled" \
    app/ ENGINE.md README.md 2>/dev/null; then
    echo "GAGAL: masih ada jejak identifier yang harus dibersihkan."
    exit 1
fi

if [ -f "app/src/main/assets/adblock/youtube_processor.js" ]; then
    echo "GAGAL: youtube_processor.js masih ada."
    exit 1
fi

echo "LULUS: tidak ada identifier bermasalah yang ditemukan."
