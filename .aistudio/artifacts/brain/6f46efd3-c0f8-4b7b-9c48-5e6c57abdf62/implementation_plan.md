# Rencana Implementasi: Perbaikan Error Startup, Navigasi Tab Baru, dan Koneksi Halaman

## 1. Akar Masalah yang Ditemukan

Berdasarkan audit menyeluruh pada kode aplikasi, ditemukan empat masalah utama yang menyebabkan aplikasi tidak dapat digunakan secara normal:

1. **Logika Terbalik pada Pembukaan Tab Baru & Pop-up (`CustomWebView.kt` & `SecurityGuardEngine.kt`)**
   - Pengaturan `openPopupsInNewTab` ("Buka pop-up sebagai tab baru") dan `blockHiddenPopups` aktif (`true`) secara default di `SettingsManager.kt`.
   - Namun pada `CustomWebView.kt` di dalam `WebChromeClient.onCreateWindow` (baris 1389 & 1438) dan `alexNativeBridge.openUrlInNewTab` (baris 485), kondisi yang tertulis adalah `if (viewModel.openPopupsInNewTab.value || ...)` yang justru **membatalkan dan memblokir** pembuatan tab baru serta menampilkan pesan *"Pop-up tab baru diblokir otomatis"*.
   - Selain itu, pada `SecurityGuardEngine.buildClientSecurityScript` (baris 496), ketika `blockHiddenPopups` bernilai `true`, fungsi `window.open` di JavaScript ditimpa secara mutlak menjadi `return null` tanpa memeriksa apakah panggilan tersebut berasal dari klik/interaksi pengguna yang sah. Akibatnya, semua tautan `target="_blank"`, tombol login, unduhan, atau navigasi jendela baru gagal berfungsi.

2. **Inisialisasi Sinkron Database SQLCipher & Android Keystore di Main Thread (`BrowserViewModel.kt` & `MainActivity.kt`)**
   - Saat aplikasi pertama kali dibuka, konstruktor `BrowserViewModel` langsung memanggil `AppDatabase.getDatabase(application)` secara sinkron di **Main Thread (UI Thread)**.
   - Proses ini memuat native library `sqlcipher`, menginisialisasi `MasterKey` Android Keystore (`EncryptedSharedPreferences`), membuka koneksi database terenkripsi (`writableDatabase`), dan menjalankan migrasi data secara sinkron sebelum frame pertama UI dirender. Pada perangkat atau streaming emulator, operasi I/O dan kriptografi sinkron di Main Thread ini menyebabkan aplikasi membeku (hang/ANR) atau gagal merender tampilan awal.

3. **Kegagalan Memuat URL `http://` karena `ERR_CLEARTEXT_NOT_PERMITTED` (`BrowserViewModel.kt` & `CustomWebView.kt`)**
   - Konfigurasi `network_security_config.xml` menonaktifkan cleartext HTTP (`cleartextTrafficPermitted="false"`).
   - Namun ketika pengguna mengetik URL `http://`, membuka bookmark lama, atau memanggil `webView.loadUrl()`, Android WebView tidak memanggil `shouldOverrideUrlLoading`, sehingga upgrade `http://` ke `https://` terlewat dan koneksi langsung ditolak oleh sistem dengan error `ERR_CLEARTEXT_NOT_PERMITTED`.

4. **Sinkronisasi Status Loading & Pemulihan Halaman WebView (`CustomWebView.kt`)**
   - Ketika beralih antar-tab atau memuat ulang halaman dari keadaan `about:blank`, terdapat kondisi di mana indikator loading (`isLoading`) atau pemuatan awal URL tidak tersinkronisasi jika aksi navigasi dikonsumsi sebelum `WebView` selesai diinisialisasi di dalam `webViewPool`.

---

## 2. Rencana Perubahan Kode

### A. Perbaikan Navigasi Tab Baru & Pemblokiran Pop-up Otomatis
- **`app/src/main/java/com/example/ui/components/CustomWebView.kt`**:
  - Memperbaiki `WebChromeClient.onCreateWindow`:
    - Hanya memblokir pop-up apabila dipicu **tanpa interaksi pengguna** (`!isUserGesture && viewModel.blockHiddenPopups.value`) atau terdeteksi sebagai domain iklan/judi/berbahaya oleh `AdBlockEngine` / `HeuristicsEngine` / `AdGuardDnsFilter`.
    - Apabila berasal dari klik pengguna yang sah (`isUserGesture == true`) dan URL aman:
      - Jika `viewModel.openPopupsInNewTab.value == true` (default): membuka tautan di tab baru (`viewModel.openUrlInNewTab(targetUrl, select = true)`).
      - Jika `viewModel.openPopupsInNewTab.value == false`: membuka tautan di tab yang sedang aktif (`viewModel.navigateTo(targetUrl)`).
  - Memperbaiki `alexNativeBridge.openUrlInNewTab` (untuk klik tengah / `auxclick` / pembukaan tab baru via bridge):
    - Menghapus kondisi terbalik `if (viewModel.openPopupsInNewTab.value || viewModel.blockHiddenPopups.value)` yang selama ini menggagalkan fungsi tersebut, dan menggantinya dengan validasi keamanan URL (`SecurityGuardEngine.isSafeWebNavigationUrl`) serta pengecekan domain iklan/judi.
- **`app/src/main/java/com/example/manager/SecurityGuardEngine.kt`**:
  - Memperbaiki `buildClientSecurityScript`:
    - Melacak event interaksi pengguna (`pointerdown`, `touchstart`, `mousedown`, `click`, `keydown`) dengan jendela waktu interaksi aktif (`1500ms`).
    - Saat `blockHiddenPopups` aktif, `window.open` hanya memblokir panggilan otomatis di latar belakang (tanpa interaksi pengguna) atau URL dengan skema berbahaya (`javascript:`, `data:`, `vbscript:`), sementara klik pengguna yang sah diteruskan secara normal.

### B. Optimasi Startup Non-Blocking (Mencegah Hang / ANR)
- **`app/src/main/java/com/example/ui/viewmodel/BrowserViewModel.kt`**:
  - Mengubah inisialisasi `AppDatabase` dan aliran data `historyList`, `bookmarks`, serta `downloads` agar dieksekusi secara asinkron di `Dispatchers.IO` (menggunakan `flow { emitAll(...) }.flowOn(Dispatchers.IO)`), sehingga pembuatan `BrowserViewModel` di Main Thread tidak memblokir UI saat memuat `sqlcipher` dan Android Keystore.
- **`app/src/main/java/com/example/MainActivity.kt`**:
  - Memindahkan inisialisasi disk/jaringan awal (`FaviconStore.init` dan `AdGuardDnsFilter.setEnabled`) ke background coroutine `lifecycleScope.launch(Dispatchers.IO)` agar render layar pertama berlangsung instan.

### C. Upgrade Otomatis HTTP ke HTTPS di Seluruh Jalur Navigasi
- **`app/src/main/java/com/example/ui/viewmodel/BrowserViewModel.kt` & `app/src/main/java/com/example/ui/components/CustomWebView.kt`**:
  - Memastikan `navigateTo()`, `openUrlInNewTab()`, `addNewTab()`, serta blok `LoadUrl` dan `Reload` pada `CustomWebView.kt` menerapkan `SecurityGuardEngine.upgradeToHttpsIfNeeded()` sebelum memanggil `webView.loadUrl()`, sehingga halaman tidak gagal akibat `ERR_CLEARTEXT_NOT_PERMITTED`.
  - Memastikan pemulihan otomatis apabila `webViewPool[tab.id]` baru saja dibuat ketika `currentNavAction` dieksekusi, sehingga perintah `LoadUrl` tidak pernah hilang saat pembuatan tab baru.

---

## 3. Verifikasi & Pengujian

1. Menjalankan kompilasi penuh menggunakan `compile_applet` untuk memastikan tidak ada error kompilasi maupun konflik resource.
2. Menambahkan dan memperbarui unit test regresi pada `SecurityFixesTest.kt` untuk memverifikasi perilaku `buildClientSecurityScript` (memblokir pop-up tersembunyi tanpa mematikan klik sah pengguna) serta alur upgrade URL dan pembukaan tab baru.
3. Menjalankan seluruh rangkaian unit test (`gradle :app:testDebugUnitTest`) hingga 100% lulus.
