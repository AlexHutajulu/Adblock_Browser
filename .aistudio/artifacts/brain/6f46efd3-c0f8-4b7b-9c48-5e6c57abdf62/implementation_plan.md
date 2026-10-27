# Alex Browser v3 — Perbaikan Engine AdBlock, Tata Letak UI, Liquid Glass & Lokalisasi Penuh

Rencana ini merinci peningkatan menyeluruh pada Alex Browser untuk mengeliminasi kerusakan tata letak situs akibat pemblokiran kosmetik/heuristik yang terlalu agresif, memperbaiki tumpang-tindih bar bawah pada situs mobile seperti YouTube, menghadirkan permukaan Liquid Glass yang ringan di bar atas, menambahkan sistem ikon situs lokal (Favicon & Brand), menyediakan Quick Link desktop yang dapat dikustomisasi, serta menuntaskan lokalisasi bahasa aplikasi secara utuh dan merapikan menu Pengaturan.

---

## User Review & Keputusan Penting

> [!IMPORTANT]
> Berdasarkan konfirmasi Anda pada tahap klarifikasi, berikut adalah keputusan arsitektur utama yang ditetapkan dalam rencana ini:

- **Keputusan 1 (Struktur Pengaturan Lengkap)**: Menu Pengaturan akan diorganisasikan ulang ke dalam 5 kelompok utama (*Tampilan*, *Privasi & Keamanan*, *AdBlock*, *Bahasa*, dan *Lanjutan*) menggunakan komponen kartu Material 3 yang seragam **tanpa menghapus** fitur lanjutan yang sudah ada (seperti DNS/DoH, Ekstensi, Custom JS, Izin Situs, User-Agent, dan Proxy).
- **Keputusan 2 (Pergantian Bahasa Tanpa Kehilangan State Tab)**: Mengintegrasikan `AppCompatDelegate.setApplicationLocales` (dengan `locales_config.xml`) dikombinasikan dengan pengelolaan konfigurasi yang menjaga agar state tab WebView yang sedang aktif tidak hilang saat pengguna mengganti bahasa.
- **Keputusan 3 (Penanganan BrandIcons & Privasi Favicon)**: `BrandIcons` disiapkan dengan fallback otomatis ke `FaviconStore` lokal (mengambil ikon langsung dari callback `WebChromeClient.onReceivedIcon` dengan cache memori 8 MB + penyimpanan disk lokal) dan ikon globe bawaan, tanpa memanggil layanan favicon pihak ketiga dan tanpa menebak aset logo brand sebelum file resmi ditambahkan.

---

## 1. Gambaran Umum & Konsep Inti

- **Apa yang Dilakukan**: Menyempurnakan mesin pemfilteran iklan agar cerdas membedakan elemen iklan dari struktur halaman utama, memperbaiki ergonomi navigasi bawah dan atas, menambahkan manajemen pintasan cepat desktop, serta memastikan seluruh teks antarmuka mendukung Bahasa Indonesia dan Inggris secara konsisten.
- **Target Pengguna**: Pengguna mobile dan tablet/desktop yang menginginkan penjelajahan web cepat, bersih dari iklan dan pelacak, hemat baterai/RAM, dengan tampilan modern yang tidak merusak fungsi situs populer (e-commerce, perbankan, berita, dan streaming video).
- **Nilai Utama**:
  1. Situs web tampil utuh tanpa elemen kontainer atau modal penting yang hilang.
  2. Bar navigasi bawah situs (seperti *pivot bar* YouTube Mobile) terlihat penuh di atas bar browser.
  3. Efek visual *Liquid Glass* yang elegan namun tetap mempertahankan *frame time* di bawah 16 ms.
  4. Privasi penuh untuk ikon situs (100% lokal dari WebView, nol permintaan ke server pihak ketiga).

---

## 2. Pengalaman Pengguna & Desain Visual

### Alur Pengguna Utama
1. **Penjelajahan & Tata Letak Bawah (Docking Flow)**:
   - Saat membuka situs video seperti YouTube Mobile, area WebView berada di dalam alur kolom utama di atas *DockContainer* bawah. Bar bawah situs tidak pernah tertutup oleh toolbar bawah maupun *shortcut bar*.
   - Saat masuk ke mode layar penuh video (*immersive fullscreen*), dock bawah otomatis disembunyikan sehingga video tampil memenuhi layar.
2. **Bar Atas Liquid Glass & Quick Link Desktop**:
   - Bar alamat atas (mobile dan desktop) tampil dengan permukaan kaca transparan berlapis gradien halus, garis tepi cahaya (*specular rim*), dan bayangan lembut.
   - Di mode desktop/tablet, bar *Quick Link* menampilkan daftar tautan cepat dengan ikon situs (`SiteIcon`). Pengguna dapat mengetuk untuk membuka halaman, menekan lama (*long-press*) chip untuk mengedit judul/URL/status atau menghapusnya, serta menekan tombol `+` untuk menambah tautan baru dengan validasi URL.
3. **Pengaturan Terstruktur & Bahasa Instan**:
   - Pengguna membuka Pengaturan dan melihat 5 bagian terkelompok rapi dengan tinggi baris minimal 56 dp, ikon bergaya seragam, dan subjudul penjelas.
   - Saat memilih bahasa (*Bahasa Indonesia*, *English*, atau *Default Sistem*), seluruh label antarmuka, dialog, toast, dan deskripsi aksesibilitas langsung diperbarui.

### Identitas Visual & Tema
- **Arah Estetika**: *Modern Translucent Utility* — perpaduan presisi Material Design 3 dengan aksen permukaan *Liquid Glass* yang bersih dan ringan.
- **Palet Warna & Atmosfer**:
  - Mode Gelap: Gradien kaca vertikal (`#CC24262C` ke `#B3181A1F`) dengan tepi cahaya putih transparan (`alpha 0.30f` ke `0.04f`).
  - Mode Terang: Gradien kaca vertikal (`#E6FFFFFF` ke `#CCF2F4F8`) dengan tepi cahaya cerah (`alpha 0.95f` ke `0.04f`).
  - Kartu Pengaturan: Menggunakan warna semantik `MaterialTheme.colorScheme.surfaceContainer` dengan sudut membulat `16.dp` dan tombol aksi berisiko (reset/hapus) menggunakan warna `error`.
- **Tipografi & Hierarki**: Label bagian menggunakan `labelLarge` berwarna `primary`, judul item menggunakan `bodyLarge`, dan keterangan efek menggunakan `bodySmall` berwarna `onSurfaceVariant`.
- **Umpan Balik Interaktif**: Setiap baris pengaturan dan chip memiliki *ripple* sentuh yang jelas, target sentuh minimal `48.dp` / tinggi baris `56.dp`, serta validasi input URL secara langsung pada dialog Quick Link.

---

## 3. Keputusan Produk & Pertimbangan Teknis

- **Keputusan 1: Liquid Glass Tanpa Blur Real-Time di Atas WebView**
  - *Pendekatan*: Menggunakan permukaan transparan berbasis gradien (`Brush.verticalGradient`), *specular rim border*, dan elevasi bayangan lembut tanpa merender ulang tampilan WebView ke bitmap blur setiap frame. Fitur ini aktif secara default pada perangkat dengan RAM >= 6 GB dan mati secara default pada perangkat dengan RAM < 6 GB.
  - *Alasan*: Blur real-time di atas permukaan `WebView` Android membutuhkan penyalinan tekstur hardware di setiap frame scroll yang menguras baterai dan menurunkan *frame rate*. Pendekatan kaca gradien menjaga *frame time* stabil di bawah 16 ms.
- **Keputusan 2: Pemisahan Ketat Aturan First-Party & Validasi Substring Kosmetik**
  - *Pendekatan*: Menolak selektor atribut substring pendek atau yang memuat kata umum (`head`, `load`, `down`, `read`, dll.), mengecualikan aturan jaringan generik (tanpa domain anchor) dari permintaan *first-party*, membatasi heuristik path hanya untuk `SCRIPT` dan `SUBDOCUMENT`, serta membatasi `unlockScroll()` hanya ketika terdeteksi overlay `.fc-ab-root` / `#fc-ab-root`.
  - *Alasan*: Mencegah aturan seperti `div[id*="ad"]` menyembunyikan `#header` atau `#thread`, serta mencegah aturan `/ads/` memblokir stylesheet internal situs.
- **Keputusan 3: Penyatuan Jalur Injeksi Kosmetik**
  - *Pendekatan*: Menjadikan injeksi *DocumentStart* sebagai jalur utama sebelum halaman dirender, dan hanya menjalankan injektor fallback apabila fitur `DOCUMENT_START_SCRIPT` tidak didukung oleh WebView perangkat.
  - *Alasan*: Menghindari duplikasi injeksi DOM dan beban eksekusi ganda pada setiap pemuatan halaman.

---

## 4. Arsitektur Teknis & Strategi Data

### Diagram Arsitektur Sistem

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         LAPISAN UI (Jetpack Compose)                      │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ Layar Utama Browser                                                 │  │
│  │  ├─ Bar Atas (Mobile & Desktop) ──► LiquidGlassSurface              │  │
│  │  ├─ Desktop Quick Link Bar      ──► SiteIcon + Dialog Tambah/Edit   │  │
│  │  ├─ Area Konten (weight 1f)     ──► CustomWebView                   │  │
│  │  └─ DockContainer Bawah         ──► Shortcut Bar + Bottom Toolbar   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ Layar Pengaturan (5 Kelompok: Tampilan, Privasi, AdBlock, Bahasa,   │  │
│  │ Lanjutan)                       ──► SettingsSection / Switch / Nav  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────┬───────────────────────────────────────────┬───────────────┘
                │                                           │
                ▼                                           ▼
┌───────────────────────────────────────┐   ┌───────────────────────────────┐
│     MANAJER FITUR & PENYIMPANAN       │   │   ENGINE ADBLOCK & WEBVIEW    │
│                                       │   │                               │
│  • AppLanguageManager (AppCompat)     │   │  • AbpRuleEngine              │
│    └─ Resolusi id / en / sistem       │   │    └─ Guard First-Party       │
│  • FaviconStore                       │   │  • HeuristicsEngine           │
│    └─ LruCache (8MB) + Disk SHA-256   │   │    └─ Filter SCRIPT/SUBDOC    │
│  • BrandIcons                         │   │  • DocumentStartCosmetics     │
│    └─ Pemetaan Host -> Fallback       │   │    └─ Jalur Utama Kosmetik    │
│  • QuickLinkStore                     │   │  • CosmeticFilterInjector     │
│    └─ CRUD JSON + Normalisasi URL     │   │    └─ Validator Substring     │
│  • SettingsManager (EncryptedPrefs)   │   │  • Neutralizer Anti-Adblock   │
│    └─ Liquid Glass RAM >= 6GB Default │   │    └─ Khusus Funding Choices  │
└───────────────────────────────────────┘   └───────────────────────────────┘
```

### Model Data & Pemetaan Interaksi
1. **Pembersihan Kode Mati & Konsolidasi Model**:
   - Menghapus kelas mesin lama yang tidak terpakai setelah memverifikasi tidak ada referensi aktif.
   - Memindahkan model `BlockedRequest` ke paket adblock utama dan membersihkan referensi mesin warisan hingga tuntas.
2. **Alur Penyimpanan Favicon (`FaviconStore`)**:
   - Saat halaman dimuat, `WebChromeClient.onReceivedIcon` menangkap bitmap ikon, mengubah ukurannya menjadi `64x64 px`, menyimpannya ke `LruCache` (batas 8 MB) berdasarkan host bersih (tanpa `www.`), dan menulis file PNG bernama hash SHA-256 ke direktori internal aplikasi.
   - Composable `SiteIcon` memeriksa `BrandIcons` terlebih dahulu, lalu `FaviconStore`, dan kembali ke ikon globe standar jika belum tersedia.
3. **Alur Kustomisasi Quick Link (`QuickLinkStore`)**:
   - Menyimpan daftar objek `(id, title, url, enabled)` dalam format JSON pada `EncryptedPreferences`.
   - Saat pengguna menambah atau mengedit item, input URL divalidasi melalui `normalizeUrl` (otomatis menambahkan `https://` jika belum ada skema dan memverifikasi keberadaan host). Jika tidak valid, tampil pesan kesalahan terlokalisasi.
   - Menyediakan opsi "Kembalikan default" di Pengaturan untuk memulihkan 4 tautan awal (Google, YouTube, Wikipedia, GitHub).
4. **Strategi Lokalisasi & Pengujian Paritas String**:
   - Mengeksternalisasi seluruh string UI yang masih tertulis langsung di kode ke dalam `values/strings.xml` (Bahasa Indonesia) dan `values-en/strings.xml` (Bahasa Inggris) dengan penamaan berawalan konteks layar.
   - Menambahkan unit test `StringsParityTest` untuk memastikan kesetaraan kunci 1:1 antara Bahasa Indonesia dan Bahasa Inggris tanpa nilai kosong, serta skrip pemeriksa string hardcode.
   - Menambahkan unit test JVM untuk memverifikasi validator selektor CSS (B1), perlindungan aset *first-party* (B2), pembatasan tipe resource heuristik (B3), dan perilaku neutralizer (B4).
