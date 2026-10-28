# Perombakan Interactive Liquid Glass & Peningkatan UX AlexBrowser

Perombakan menyeluruh sistem visual **AlexBrowser** menjadi desain **Interactive Liquid Glass** (optik kaca 3D berlapis, pantulan tepi *specular*, pendaran cahaya radial saat disentuh, fisika pegas cair, dan lensa kaca pada toggle/slider) yang responsif di mode Mobile, Tablet, Desktop, Split-Screen, dan Floating Window (Xiaomi HyperOS/Freeform), dilengkapi dengan perlindungan Background Playback YouTube, Smart Splash Screen berbasis sesi RAM, serta Pull-to-Refresh Material 3.

## User Review & Critical Decisions

> [!IMPORTANT]
> Berikut adalah keputusan utama yang telah dikonfirmasi dan akan diterapkan secara menyeluruh:

- **Keputusan Terkonfirmasi 1 — Pull-to-Refresh Material 3**: Menggunakan `PullToRefreshBox` bawaan Material 3 dengan indikator loading bergaya *Liquid Glass* yang hanya aktif ketika posisi scroll vertikal halaman web berada paling atas (`scrollY == 0` dan `!canScrollVertically(-1)`), menggantikan ketergantungan eksternal agar bebas konflik *nested scrolling* dan tetap stabil 120 FPS.
- **Keputusan Terkonfirmasi 2 — Cakupan Interactive Liquid Glass di Bilah Atas**: Menerapkan `interactiveLiquidGlass` secara seragam pada kapsul Omnibox maupun tombol-tombol aksi di sekitarnya pada mode Mobile, Tablet, dan Desktop, disertai *safe drawing top inset* dan jarak aman ekstra pada jendela mengambang (`maxHeight < 560.dp`) agar tidak pernah tertutup oleh titik tiga kontrol jendela sistem (HyperOS/Freeform).
- **Keputusan Terkonfirmasi 3 — Komponen Toggle Switch & Slider Lensa Kaca**: Menerapkan komponen toggle switch dan slider bergaya kapsul lensa kaca bening (sesuai video referensi 00:32–00:39 & 00:54–00:57) pada menu **Pengaturan** serta dialog **AlexShield & Keamanan**.

---

## 1. Overview & Core Concept

- **What It Does**: Menghadirkan pengalaman penjelajahan web berkecepatan tinggi dengan antarmuka kaca cair interaktif (*Interactive Liquid Glass*) yang merespons setiap sentuhan jari melalui pendaran cahaya radial dan animasi pegas, sambil menjamin pemutaran media latar belakang (seperti YouTube) tidak terputus saat pengguna berpindah aplikasi.
- **Target Audience / Persona**: Pengguna ponsel dan tablet Android (termasuk pengguna multitasking Split-Screen dan Floating Window pada HyperOS/OneUI) yang menginginkan estetika kaca modern setara iOS/visionOS tanpa mengorbankan performa rendering WebView 120 FPS.
- **Key Value**:
  - Visual kaca 3D berlapis yang ringan di GPU (*zero RenderEffect blur lag* di atas WebView).
  - Tata letak bebas tabrakan (*anti-clipping*) dengan kontrol jendela mengambang OS.
  - Satu bilah bawah kapsul kaca terpadu yang dapat berekspansi secara cair (*fluid morphing*) saat bilah pintasan dibuka.
  - Pemutaran audio/video latar belakang tanpa jeda (*Anti-Kill*) dan *Smart Splash Screen* yang hanya tampil sekali per sesi RAM.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Membuka Aplikasi (Cold vs Warm Start)**: Saat aplikasi baru dimuat ke RAM (*cold start*), *SplashScreen* muncul singkat lalu masuk ke beranda. Saat pengguna keluar ke *Home screen* dan kembali lagi, aplikasi langsung menampilkan halaman aktif tanpa mengulang *SplashScreen*.
  2. **Interaksi Sentuhan Kaca (Touch Glow & Spring Morphing)**: Saat pengguna menyentuh tombol navigasi (`<`, `>`, `+`, Pintasan, Tab, atau AdShield), kapsul kaca mengecil sedikit dengan fisika pegas (`scale = 0.93f`, `DampingRatioLowBouncy`) dan memancarkan cahaya radial putih lembut dari titik tengah kapsul. Ketika tombol Pintasan (Bolt) ditekan, bilah bawah berekspansi secara cair menggunakan `animateContentSize` dalam satu permukaan kaca yang sama.
  3. **Mode Jendela Mengambang (Floating Window / HyperOS)**: Saat jendela diperkecil menjadi jendela melayang (`maxHeight < 560.dp`), bilah atas otomatis memberi jarak aman terhadap titik tiga kontrol jendela sistem dan menyembunyikan tombol angka tab duplikat di bilah atas ponsel agar teks URL di Omnibox tetap lega.
  4. **Toggle Lensa Kaca & Pull-to-Refresh**: Di menu Pengaturan dan dialog AlexShield, pengguna menggeser/menekan switch yang memiliki jempol (*thumb*) berbentuk kapsul lensa kaca transparan berlapis di atas jalur warna aktif. Saat berada di bagian paling atas halaman web, menarik layar ke bawah menampilkan indikator kaca cair untuk memuat ulang halaman.
  5. **Background Playback YouTube**: Saat pengguna memutar video YouTube lalu menekan tombol Home atau membuka aplikasi lain, WebView menahan sinyal visibilitas `GONE` dan tidak memanggil `onPause()`, sehingga audio/video tetap berjalan mulus di latar belakang.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: **Interactive Optical Liquid Glass** — perpaduan kaca bening berlapis (*multi-stop vertical refraction*), garis tepi *specular highlight* tajam di bagian atas-kiri, bayangan kedalaman di bawah-kanan, dan pendaran *radial glow* saat disentuh.
  - *Color Palette & Mood*:
    - **Light Glass Surface**: `GlassSurfaceLightTop` (`#D9FFFFFF`), `GlassSurfaceLightMid` (`#B8F4F7FC`), `GlassSurfaceLightBottom` (`#CCDFE7F2`), dengan tepi `GlassBorderLightHighlight` (`#FFFFFFFF`) dan bayangan `GlassBorderLightShadow` (`#38000000`).
    - **Dark Glass Surface**: `GlassSurfaceDarkTop` (`#D92C2F36`), `GlassSurfaceDarkMid` (`#B81F2126`), `GlassSurfaceDarkBottom` (`#CC17181C`), dengan tepi `GlassBorderDarkHighlight` (`#8CFFFFFF`) dan bayangan `GlassBorderDarkShadow` (`#66000000`).
    - **Interactive Accents**: `LiquidActiveTint` (`#590B57D0`) untuk cairan aksen aktif dan `LiquidTouchGlow` (`#73FFFFFF`) untuk pendaran radial sentuhan.
  - *Typography & Hierarchy*: Skala tipografi Material 3 yang bersih dan tajam dengan ukuran minimum `11.sp` untuk lencana (*badge*) dan label sekunder, menjaga keterbacaan tinggi di atas permukaan translusen.
  - *Component Styling & Layout*:
    - Target sentuh minimum `44.dp–48.dp` untuk seluruh aksi utama.
    - Penghapusan latar belakang abu-abu solid pada taskbar bawah, digantikan oleh satu kapsul kaca tunggal (`RoundedCornerShape(30.dp)`) yang menghormati `WindowInsets.navigationBars`.
    - Komponen **LiquidGlassSwitch** dan **LiquidGlassSlider** dengan jempol (*thumb*) berbentuk kapsul kaca 3D yang memantulkan warna jalur di bawahnya.

- **Interactive Feedback & Motion**:
  - Fisika pegas (`Spring.DampingRatioLowBouncy`, `Spring.StiffnessMedium`) pada seluruh penekanan tombol kaca dan perubahan ukuran kontainer (`animateContentSize`).
  - Pendaran radial (`Brush.radialGradient`) dengan transisi opasitas `180ms` saat ditekan (`isPressed`) maupun saat berstatus aktif (`isActive`).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Shader/Canvas Multi-Stop + Specular Rim dibanding Real-Time RenderEffect Blur di Atas WebView**
  - *Chosen Approach*: Membangun `Modifier.interactiveLiquidGlass` menggunakan *multi-stop translucent gradient*, *inner specular refraction rect*, *radial touch glow*, dan *gradient rim border* berbasis `drawWithContent`.
  - *Why*: `RenderEffect.createBlurEffect` di atas `AndroidView(WebView)` memicu penyalinan tekstur *offscreen* setiap frame saat halaman web digulir atau memutar video, yang menyebabkan *drop frame* dan kedipan hitam pada banyak GPU Android. Pendekatan optik vektor/gradien ini memberikan tampilan kaca 3D yang identik dengan video referensi pada 120 FPS stabil.
- **Decision 2: PullToRefreshBox Material 3 Terkawal Posisi Scroll WebView**
  - *Chosen Approach*: Menggunakan `PullToRefreshBox` dari Material 3 yang dikombinasikan dengan pelacakan apakah `WebView` berada di titik paling atas (`scrollY == 0` dan `!canScrollVertically(-1)`), lengkap dengan indikator kapsul kaca.
  - *Why*: Menghilangkan kebutuhan dependency eksternal tambahan sekaligus mencegah refresh tidak sengaja saat pengguna menggulir halaman web panjang ke atas.
- **Decision 3: Perlindungan Visibilitas WebView untuk Background Playback**
  - *Chosen Approach*: Meng-override perilaku `onWindowVisibilityChanged` pada instance `WebView` agar tetap melaporkan `View.VISIBLE` ketika `backgroundPlaybackEnabled` aktif, serta menjaga `onResume()` dan `resumeTimers()` tetap hidup saat aplikasi berada di latar belakang.
  - *Why*: Chromium WebView secara internal menghentikan pemutaran video HTML5 (seperti YouTube) segera setelah menerima sinyal `onWindowVisibilityChanged(View.GONE)` meskipun `onPause()` tidak dipanggil.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         MainActivity & Lifecycle                          │
│  • Smart Splash Gate (viewModel.isColdStartCompleted)                     │
│  • Background Playback Lifecycle Guard (onPause / onStop / onResume)      │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                  Interactive Liquid Glass Design Engine                   │
│  • Modifier.interactiveLiquidGlass (Spring Scale + Radial Glow + Rim)     │
│  • LiquidGlassSwitch & LiquidGlassSlider (3D Capsule Glass Lens Thumb)    │
│  • WindowLayoutProfile (Mobile / Split-Screen / Tablet / Floating Guard)  │
└───────────────┬─────────────────────────────┬─────────────────────────────┘
                │                             │
                ▼                             ▼
┌───────────────────────────────┐ ┌─────────────────────────────────────────┐
│     Browser Chrome & Home     │ │   WebView Viewport & Settings Layer     │
│ • SafeDrawing + Floating Pad  │ │ • M3 PullToRefreshBox (scrollY == 0)    │
│ • Top Toolbar Glass Capsules  │ │ • Anti-Kill WebView (Visibility Guard)  │
│ • Unified Glass Bottom Bar    │ │ • Settings & AlexShield Glass Toggles   │
│ • HomeScreen Glass Hub & Grid │ │ • AlexShield v5 AdBlock Engine          │
└───────────────────────────────┘ └─────────────────────────────────────────┘
```

- **Data Model & State**:
  - `BrowserViewModel.isColdStartCompleted`: Flag boolean di memori `ViewModel` yang bertahan selama proses aplikasi hidup di RAM dan mencegah pemuatan ulang `SplashScreen` saat rotasi atau keluar-masuk aplikasi.
  - `SettingsManager.isBackgroundPlaybackEnabled`: Disetel `true` secara default dan disinkronkan melalui `StateFlow` ke `MainActivity`, `BrowserScreen`, dan `CustomWebView`.
  - `WindowLayoutProfile(maxWidth, maxHeight)`: Menghitung `isFloatingOrCompactHeight`, `isTabletOrWide`, dan jarak ekstra atas (`maxHeight < 560.dp`) secara reaktif.

- **Interactive Component & State Mapping**:
  - **Top Toolbar (`TabletOrMobileToolbar`, `DesktopToolbar`, `TabletHorizontalTabBar`, `ChromeFindInPageBar`)**: Menerapkan `.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))` + padding ekstra saat `maxHeight < 560.dp`, membungkus Omnibox dan tombol aksi dengan `.interactiveLiquidGlass(...)`, serta menyembunyikan `ChromeTabCountButton` di bilah atas saat `!isTablet`.
  - **Unified Bottom Taskbar (`ChromeMobileBottomBar`)**: Menggunakan satu kontainer `.interactiveLiquidGlass(shape = RoundedCornerShape(30.dp))` dengan `.animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy))` yang menyatukan baris `ChromeShortcutAndJsBar` dan 6 tombol aksi utama (`Back`, `Forward`, `+` Lensa Kaca Aktif, `Bolt`, `Tab Counter`, `AdShield`).
  - **Lensa Kaca Switch & Slider (`LiquidGlassSwitch`, `LiquidGlassSlider`)**: Menggantikan switch standar pada `SettingsSwitchRow`, dialog `WebGuardShieldDialog`, dan pengatur skala/zoom dengan trek berwarna aktif dan jempol (*thumb*) kapsul kaca bening bertepi *specular* yang membal saat digeser.
  - **Pull-to-Refresh & Anti-Kill WebView (`CustomWebView`)**: Membungkus `AndroidView` dengan `PullToRefreshBox` Material 3 berindikator kaca cair yang memicu `viewModel.reload()`, serta menggunakan subclass `WebView` yang menjaga `onWindowVisibilityChanged(View.VISIBLE)` saat `backgroundPlaybackEnabled` aktif.
