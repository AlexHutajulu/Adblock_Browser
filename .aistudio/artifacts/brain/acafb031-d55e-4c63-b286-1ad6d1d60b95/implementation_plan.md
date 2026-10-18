# Optimasi Performa, Bar Pintasan Navigasi Bawah, Logo AI Asli & Sistem Keamanan Stealth Anti-Mod

Memperbarui browser agar menggunakan gambar logo asli **Gemini**, **Claude**, dan **ChatGPT** yang dikirim pengguna, memindahkan dan menyatukan **Bar Pintasan (Auto Putar, Ekstensi, Script)** ke dalam **Bar Navigasi Bawah Utama** (lengkap dengan fitur buka/tutup dan geser Atas/Bawah), mengoptimalkan efisiensi baterai serta mencegah *memory leak*, dan menanamkan **Sistem Keamanan Stealth Anti-Mod & Anti-Tamper Berlapis** yang tersembunyi untuk mencegah modifikasi APK oleh pihak ketiga.

## User Review & Critical Decisions

> [!IMPORTANT]
> Rencana ini disusun berdasarkan seluruh pilihan yang telah Anda konfirmasi:

- **Logo AI Menggunakan Gambar yang Dikirim**: Mengonversi dan menanamkan langsung 3 gambar yang Anda kirim (**Google Gemini 2025**, **Claude AI**, dan **OpenAI ChatGPT Blossom**) sebagai aset *drawable* aplikasi sehingga tampil persis sesuai foto asli di layar *Splash Load* dan *Settings About*.
- **Posisi Bar Pintasan (Auto Putar, Ekstensi, Script)**: Digabung secara *default* ke bagian **Bar Navigasi Bawah Utama** (tidak lagi memenuhi bagian atas layar), dilengkapi tombol **Buka/Tutup (*Collapse/Expand*)** di bar navigasi bawah serta tombol **Pindah Posisi Atas/Bawah** kapan saja.
- **Performa Cepat, Stabil, Hemat Baterai & Anti-Memory Leak**: Manajemen siklus hidup *WebView* otomatis (menghentikan timer JS, *rendering*, dan *observer* saat tab tidak aktif atau aplikasi di latar belakang, membatasi ukuran *pool* WebView, dan membersihkan referensi memori saat tab ditutup).
- **Sistem Keamanan Stealth Anti-Mod & Anti-Tamper**:
  - **Lapisan Proteksi**: Verifikasi integritas tanda tangan sertifikat APK & *checksum* `classes.dex`, penguncian identitas paket (`com.aistudio.alexbrowser.wdjkpq`) & atribut `AndroidManifest.xml`, serta deteksi alat modifikasi pihak ketiga (*APK Editor, Lucky Patcher, MT Manager, NP Manager, Xposed, EdXposed, LSPosed, Frida, Substrate*).
  - **Arsitektur Tersembunyi (*Stealth Obfuscation*)**: Nama kelas, metode, dan *string* deteksi disamarkan sebagai modul metrik/telemetri sistem internal (dengan *byte-array XOR encoding* tanpa teks *plain-text* seperti `"AntiMod"` atau `"Tamper"`) dan tersebar di beberapa titik eksekusi (*ContentProvider init, Application/Activity lifecycle, WebView bridge*).
  - **Reaksi Saat Terdeteksi Mod**: Langsung melakukan *silent force-close / native process termination* (`Process.killProcess` & `Runtime.getRuntime().halt`) tanpa pesan peringatan apa pun sehingga pembuat *mod* tidak dapat melacak titik pemicunya.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. Menampilkan aset foto asli ketiga logo AI (**Gemini**, **Claude**, **ChatGPT**) pada atribusi **Developed by Alex Hutajulu**.
  2. Merapikan tata letak browser dengan menyatukan **Bar Pintasan** ke dalam **Bar Navigasi Bawah Utama** yang dapat dibuka/tutup dengan 1 ketukan dan tetap bisa dipindah ke atas atau bawah.
  3. Menjamin performa *scrolling* dan pemuatan halaman yang ringan, hemat baterai, serta bebas *memory leak*.
  4. Melindungi keaslian aplikasi melalui mesin verifikasi integritas berlapis yang tersembunyi (*stealth*) agar APK yang dibongkar/dimodifikasi menggunakan *APK Editor* atau *patcher* langsung *crash/force-close* saat dijalankan.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Bar Pintasan Terpadu di Navigasi Bawah**:
     - Secara *default*, bagian atas layar hanya menampilkan *Omnibox* Chrome yang bersih sehingga pandangan ke halaman web sangat luas dan nyaman.
     - Baris pintasan (**Auto Putar**, **Ekstensi**, **Bikin JS**, **Mode Gelap**, dll.) terintegrasi menyatu tepat di dalam/atas kontainer **Bar Navigasi Bawah Utama**.
     - Tersedia tombol ikon **Pintasan (Buka/Tutup)** langsung pada bar navigasi bawah untuk menyembunyikan atau menampilkan baris pintasan dengan animasi halus, serta tombol panah untuk menggeser posisinya ke Atas atau Bawah sesuai selera.
  2. **Tampilan Logo AI Asli**:
     - Ketiga foto yang dikirim pengguna (`Google_Gemini_icon_2025`, `claude-ai-logo`, `OAI_OpenAI-Blossom_White`) dirender menggunakan komponen `Image(painterResource(...))` dari aset *drawable* presisi tinggi di dalam kartu *badge* modern pada layar *Splash Load* dan *Settings About*.
  3. **Pengalaman Browsing Cepat & Hemat Baterai**:
     - Saat pengguna berpindah tab atau meminimalkan aplikasi, tab yang tidak aktif otomatis memasuki mode *Low-Power Hibernation* (`onPause()`, `pauseTimers()`) sehingga tidak menguras CPU/baterai di latar belakang.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Arsitektur Keamanan Tersembunyi (*Multi-Point Stealth Integrity Verification*)**
  - *Chosen Approach*: Menyembunyikan logika verifikasi di balik komponen bernama netral (seperti pengelola *layout metrics* dan *rendering cache*) yang menggunakan *XOR-encoded byte arrays* untuk menyembunyikan seluruh nama paket terlarang, jalur *socket* Frida/Xposed, dan *hash* tanda tangan. Pengecekan dipicu secara senyap di tiga titik berbeda: saat inisialisasi awal sebelum `Activity` tampil, saat transisi siklus hidup `MainActivity`, dan saat inisialisasi mesin `WebView`.
  - *Why*: Jika *modder* mencoba mencari kata kunci seperti `"mod"`, `"hack"`, `"signature"`, atau `"apkeditor"` di dalam DEX menggunakan *MT Manager* atau *APK Editor*, mereka tidak akan menemukan satu pun *string* tersebut. Jika salah satu komponen dihapus, aplikasi tetap gagal menginisialisasi konfigurasi *WebView* dan langsung *force-close*.
- **Decision 2: Pencegahan Memory Leak & Penghematan Baterai**
  - *Chosen Approach*: Membatasi jumlah *instance* `WebView` aktif di dalam memori (maksimal 5 tab aktif sekaligus di *pool*, dengan tab lama otomatis disimpan status URL-nya dan dibebaskan memorinya), menghentikan *JavaScript timers* saat aplikasi di-*pause*, dan membersihkan seluruh *callback* sebelum `WebView.destroy()`.
  - *Why*: Mencegah *OutOfMemoryError (OOM)* dan menjaga suhu perangkat tetap dingin meski membuka banyak tab.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                 Stealth Multi-Point Integrity Architecture                │
│                                                                           │
│  [1. Early Init Hook] ──► [2. Activity Frame Sync] ──► [3. WebView Core]  │
│          │                           │                         │          │
│          ▼                           ▼                         ▼          │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │        Internal Core Verifier (XOR-Encoded Byte Signatures)         │  │
│  │  • Package ID & Manifest Attribute Lock (applicationId, debug/rel)  │  │
│  │  • APK Signature & classes.dex CRC/Zip Tamper Verification          │  │
│  │  • Mod Tool & Hook Detection (APK Editor, Lucky Patcher, MT/NP Mgr, │  │
│  │    Xposed/LSPosed, Frida Server/Ports, Substrate, Repackage Flags)  │  │
│  └───────────────────────────────────┬─────────────────────────────────┘  │
│                                      │ (Jika Terdeteksi Modifikasi)       │
│                                      ▼                                    │
│             [Silent Native Termination: killProcess / halt(0)]            │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│                Unified Bottom Navigation & Shortcut Dock                  │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ [Collapsible Shortcut Strip: Auto Putar | Ekstensi | JS | ⇅ Posisi] │  │
│  ├─────────────────────────────────────────────────────────────────────┤  │
│  │ [  ← Back  |  → Forward  |  + Tab Baru  |  ⚡ Pintasan  |  [N]  | 🛡 ]│  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Interactive Component & State Mapping**:
  - **Aset Drawable Logo AI**: Menyimpan aset gambar `img_logo_gemini`, `img_logo_claude`, dan `img_logo_chatgpt` di `app/src/main/res/drawable/` dan memuatnya di `AiAttributionComponents.kt`.
  - **Unified Bottom Dock (`ChromeMobileBottomBar`)**: Mengubah posisi *default* `shortcutBarPosition` menjadi `BOTTOM` dan menggabungkan `ChromeShortcutAndJsBar` ke dalam satu kesatuan `Surface` dengan `ChromeMobileBottomBar`, dilengkapi tombol pembuka/penutup baris pintasan (`⚡ Pintasan`) dan tombol penggeser posisi Atas/Bawah.
  - **Memory & Battery Optimizer**: Mengintegrasikan `LifecycleEventObserver` pada `CustomWebView` dan `BrowserScreen` untuk memanggil `webView.onPause()` / `webView.pauseTimers()` saat aplikasi di latar belakang, serta *LRU eviction* aman pada `webViewPool`.
