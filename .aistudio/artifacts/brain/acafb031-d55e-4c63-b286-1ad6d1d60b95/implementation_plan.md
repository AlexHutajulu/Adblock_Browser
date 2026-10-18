# Upgrade Sistem Stealth Anti-AdBlock, Fitur Baru & Optimasi Performa/RAM/Baterai

Peningkatan menyeluruh mesin **AdBlock Browser** agar tidak terdeteksi oleh skrip **Anti-AdBlock** modern (Admiral, BlockAdBlock, FuckAdBlock, Google FundingChoices, PageFair, AdDefend), penambahan **3 fitur unggulan baru** sesuai pilihan Anda, serta **optimasi keseimbangan RAM, CPU, dan baterai** tanpa kebocoran memori (*zero memory leak*).

## User Review & Critical Decisions

> [!IMPORTANT]
> Rencana ini mengimplementasikan seluruh preferensi yang telah Anda pilih:

1. **Full Stealth Anti-AdBlock Suite (Surrogate JS, Bait Spoof, Native Proxy)**:
   - **Surrogate Script & Pixel Spoofing (`AdBlockWebViewClient.kt`)**: Mengembalikan objek tiruan realistis (`window.adsbygoogle`, `window.googletag`, `window.pbjs`, `window.fuckAdBlock`, `window.blockAdBlock`, `window.admiral`, `google_ad_status = 1`) dengan status HTTP `200 OK` untuk skrip iklan, serta GIF transparan `1x1` (`image/gif`) untuk piksel pelacak agar *event* `onerror` tidak pernah terpicu.
   - **DOM Bait / Honeypot Dimension Spoofing**: Mengganti penyembunyian naif `display: none` yang mudah terdeteksi pada elemen *bait* (`.adsbox`, `.ad-banner`, `.textads`, `.banner-ads`, `ins.adsbygoogle`) dengan penyembunyian tak kasatmata (`opacity: 0.001; pointer-events: none; clip-path: inset(100%)`) serta *Proxy* pada `offsetHeight`, `offsetWidth`, `clientHeight`, `clientWidth`, `getBoundingClientRect()`, dan `getComputedStyle()` agar skrip pendeteksi selalu membaca dimensi iklan sebagai terpasang normal (`> 0px`).
   - **Zero-Footprint Native Function Cloaking**: Menyembunyikan *monkey-patch* `window.fetch`, `XMLHttpRequest.prototype.open`, dan `navigator.sendBeacon` menggunakan `Function.prototype.toString` *cloaking* (`function fetch() { [native code] }`) serta membungkus seluruh variabel internal di dalam *closure* anonim tanpa meninggalkan properti `window.__Alex*` atau ID `<style>` yang dapat di-*fingerprint*.
   - **Auto-Neutralizer Modal Anti-AdBlock**: Mendeteksi dan menutup otomatis *overlay* peringatan "Please disable your adblocker" / FundingChoices (`fc-ab-root`, `tp-modal`, `admiral-modal`) serta memulihkan kunci gulir (`overflow: auto !important` pada `html, body`).

2. **Tiga Fitur Unggulan Baru**:
   - **Mode Hibernasi Tab Otomatis (*Auto-Suspend Tab Hemat RAM*)**: Tab latar belakang yang tidak diakses selama periode tertentu (atau di luar kapasitas *pool* aktif) otomatis di-*suspend* untuk membebaskan memori `WebView` secara penuh sambil menyimpan URL, judul, dan posisi gulir, disertai lencana status *Sleeping/Eco* di *Tab Switcher* dan tombol *Hibernasi Tab Lainnya* satu ketukan.
   - **Pemilih Elemen Pengganggu (*Custom Element Zapper*)**: Mode interaktif langsung di halaman web untuk mengetuk dan menghilangkan elemen pengganggu (*floating banner*, *widget* mengambang, *overlay* promosi) dengan pratinjau sorotan visual (*highlight border*), tombol *Undo* / *Reset*, dan penyimpanan aturan CSS per-domain secara permanen.
   - **Panel Statistik & Monitor Anti-AdBlock Real-Time per Situs**: Peningkatan *Chrome AdShield Dialog* dengan indikator status **Stealth Anti-Detect Active**, penghitung *Surrogate Spoofed*, *Anti-AdBlock Traps Defused*, dan tombol pintas *Element Zapper* serta *RAM Hibernation*.

3. **Profil Optimasi Seimbang (Pool 3 WebView Aktif + Throttling Background)**:
   - Membatasi `MAX_WEBVIEW_POOL_SIZE = 3` (LRU) agar pemakaian RAM tetap rendah sekaligus menjaga perpindahan 3 tab terakhir tetap instan.
   - Membekukan eksekusi CPU/baterai (`onPause()`, `pauseTimers()` saat tidak ada tab aktif yang memutar audio) pada `WebView` latar belakang dan saat aplikasi masuk ke *background* (`ON_STOP`), serta menangani `ComponentCallbacks2.onTrimMemory` di `MainActivity` untuk memangkas *cache* dan membebaskan `WebView` tidak aktif saat sistem kekurangan RAM.

---

## 1. Overview & Core Concept

- **What It Does**: Menjadikan AdBlock Browser memiliki pertahanan berlapis terhadap deteksi Anti-AdBlock, dilengkapi kontrol zapping elemen kustom oleh pengguna, manajemen siklus hidup tab otomatis yang hemat RAM/baterai, dan telemetri privasi *real-time* per situs.
- **Key Value**: Situs berita, streaming, dan portal web dengan proteksi Anti-AdBlock tetap dapat diakses mulus tanpa gangguan pop-up pemblokiran, sementara konsumsi RAM dan baterai perangkat tetap ringan meskipun membuka banyak tab.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Stealth Browsing Otomatis**: Pengguna membuka situs web yang memasang pendeteksi AdBlock; mesin *Surrogate & Bait Spoofer* bekerja otomatis di balik layar dan mencatat jumlah *Anti-AdBlock Traps Defused* pada panel *Chrome AdShield*.
  2. **Custom Element Zapper**: Pengguna membuka *Chrome AdShield* atau *Shortcut Bar* → memilih **Pemilih Elemen (Zapper)** → mengetuk elemen pengganggu di halaman → elemen disorot dan dapat langsung dihapus & disimpan untuk domain tersebut (atau dibatalkan).
  3. **Auto-Suspend Tab Hemat RAM**: Tab tidak aktif di luar 3 tab terbaru otomatis masuk mode hemat RAM (*Eco Hibernated*) dan langsung dipulihkan mulus saat diketuk kembali.

- **Visual Identity & Theme**:
  - Mengikuti bahasa desain **Material Design 3 (M3)** yang responsif untuk *Compact* (ponsel) hingga *Expanded* (tablet/desktop), lengkap dengan *floating control pill* untuk *Element Zapper* dan kartu metrik *Stealth Shield* yang informatif.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Surrogate Response Berbasis Konteks (`200 OK` vs `204 No Content`)**
  - *Chosen Approach*: Memberikan payload JS *stub* ringan (`adsbygoogle`, `googletag`, `pbjs`, `fuckAdBlock`) untuk permintaan `.js` iklan dan GIF transparan `1x1` (`43 byte`) untuk permintaan gambar iklan, alih-alih respons kosong yang memicu `script.onerror` / `img.onerror`.
  - *Why*: Lebih dari 80% sistem Anti-AdBlock mendeteksi pemblokir iklan melalui kegagalan pemuatan jaringan (`onerror`) atau absennya objek global SDK iklan.

- **Decision 2: Kapasitas LRU Pool 3 WebView + Lifecycle TrimMemory**
  - *Chosen Approach*: Menurunkan batas pool `WebView` dari 6 menjadi **3 instans aktif**, menghentikan *timer/rendering* tab latar belakang yang tidak memutar audio, dan menghubungkan `onTrimMemory()` sistem Android ke pembersihan pool `WebView`.
  - *Why*: Setiap instans `WebView` Chromium memakan 35–80 MB RAM; membatasi maksimal 3 instans aktif memangkas penggunaan RAM hingga ~50% dan menghentikan pengurasan baterai oleh skrip latar belakang.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
```
┌────────────────────────────────────────────────────────────────────────────┐
│                        UI Layer (BrowserScreen.kt)                         │
│  • ChromeAdShieldSheet: Statistik Stealth, Anti-Detect Counter & Controls  │
│  • ElementZapperOverlayBar: Floating Mode Zapper (Tap, Confirm, Undo, Clear)│
│  • MobileTabsDialog / TabBar: Indikator Eco-Suspended Tab & 1-Tap Hibernate│
└───────────────────┬────────────────────────────────────┬───────────────────┘
                    │                                    │
                    ▼                                    ▼
┌───────────────────────────────────────┐  ┌─────────────────────────────────┐
│     ViewModel & Lifecycle Manager     │  │   Stealth AdBlock Engine V6     │
│  • BrowserViewModel.kt                │  │  • AdBlockWebViewClient.kt      │
│    ├─ Custom Zapper Rules (per-host)  │  │    ├─ JS/GIF Surrogate Engine   │
│    ├─ Tab Hibernation (isSuspended)   │  │    ├─ Native toString() Cloaking│
│    └─ Stealth Counter StateFlows      │  │    ├─ Bait offsetHeight Proxy   │
│  • MainActivity.kt (onTrimMemory)     │  │    └─ Anti-AdBlock Modal Killer │
└───────────────────────────────────────┘  └─────────────────────────────────┘
```

- **Key Files to Modify**:
  - `app/src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt`: Implementasi *Stealth Surrogate Engine* (JS stubs + 1x1 GIF), *Native Function Cloaking* (`toString()` proxy), *Bait Dimension Spoofing*, *Anti-AdBlock Modal Neutralizer*, dan *Debounced Idle MutationObserver*.
  - `app/src/main/java/com/example/ui/viewmodel/BrowserViewModel.kt`: Penyimpanan aturan *Custom Element Zapper* per domain, manajemen status hibernasi tab (`isSuspended` & `hibernateInactiveTabs()`), serta *StateFlow* metrik *Stealth Anti-Detect*.
  - `app/src/main/java/com/example/ui/components/CustomWebView.kt`: Integrasi jembatan JS `onElementZapped(selector)` dan `onStealthTrapDefused(kind)`, penginjeksian CSS *Custom Zapper*, serta *throttling* hemat baterai saat tab di latar belakang.
  - `app/src/main/java/com/example/ui/screens/BrowserScreen.kt`: Batas `MAX_WEBVIEW_POOL_SIZE = 3`, UI *Floating Element Zapper*, peningkatan *Chrome AdShield Sheet* (panel *Stealth Anti-Detect* & hibernasi RAM), serta indikator tab *Eco-Suspended*.
  - `app/src/main/java/com/example/MainActivity.kt`: Penanganan `onTrimMemory(level)` dan siklus hidup hemat baterai.
  - `app/src/test/java/com/example/AdBlockTest.kt`: Pengujian Robolectric untuk *Stealth Surrogate Response* (JS stubs, 1x1 GIF), *Native Function Cloaking*, dan *Element Zapper* rules.
