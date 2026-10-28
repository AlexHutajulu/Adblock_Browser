# Atribusi Daftar Filter Eksternal

Aplikasi ini tidak menyertakan salinan daftar filter pihak ketiga di dalam
repositori. Saat berjalan, `ResourceRegistry.kt` dapat mengunduh daftar berikut
langsung dari sumber resminya:

- EasyList & EasyPrivacy — https://easylist.to/
  Lisensi: Creative Commons Attribution-ShareAlike 3.0 / GPLv3 (lihat header daftar).
- Peter Lowe's Ad and tracking server list — https://pgl.yoyo.org/adservers/
  Lisensi: gunakan dengan atribusi kepada Peter Lowe; periksa ketentuan penggunaan komersial pada sumber asli.
- uBlock Origin filter lists (`filters.txt`, `badware.txt`, `privacy.txt`, `unbreak.txt`) — https://github.com/uBlockOrigin/uAssets
  Lisensi: GPLv3 (repositori uAssets).

Catatan untuk pemilik proyek: mengunduh berkas teks aturan saat runtime berbeda
dari menyalin kode GPL ke dalam biner, tetapi beberapa daftar tetap mensyaratkan
atribusi pada layar "Tentang / About" aplikasi. Tampilkan atribusi ini di UI.
