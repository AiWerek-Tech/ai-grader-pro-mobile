# WMVAA AI Grader Pro — Android Mobile Wrapper (Kotlin)

Aplikasi Android native berbasis Kotlin yang membungkus versi web **WMVAA AI Grader Pro** melalui jaringan Wi-Fi lokal sekolah (LAN).

## Fitur Utama

1. **Koneksi Server Lokal Fleksibel (`ServerSetupActivity.kt` & `LanServerDiscovery.kt`)**:
   - **Scan QR Akses HP Guru**: Arahkan kamera HP ke QR Code di pojok kanan atas Dashboard Web PC untuk langsung terhubung.
   - **Auto-Discovery Wi-Fi**: Memindai subnet Wi-Fi lokal pada port `3000` (`/api/system/network`) untuk menemukan komputer server sekolah secara otomatis.
   - **Riwayat & Input IP Manual**: Menyimpan hingga 5 alamat server terakhir.
2. **Kamera & Galeri Native Terintegrasi (`MainActivity.kt`)**:
   - Mendukung tombol **"Kamera HP Cepat (1-Klik)"** (`<input type="file" capture="environment">`) dengan tangkapan gambar resolusi penuh melalui `FileProvider`.
   - Mendukung **Multi-Upload Galeri** (`MODE_OPEN_MULTIPLE`) untuk mengunggah banyak foto LJK sekaligus.
   - Mendukung **Live WebRTC Viewfinder** (`RESOURCE_VIDEO_CAPTURE`) saat server dijalankan pada mode HTTPS lokal (Mode 3 di `START_SERVER_SEKOLAH.bat`), termasuk dialog persetujuan sertifikat HTTPS lokal yang aman khusus untuk IP LAN.
3. **PWA, Cache Offline, & Pemulihan Koneksi (`NetworkMonitor.kt`, `public/sw.js`, `PwaOfflineBridge.tsx`)**:
   - Memantau status Wi-Fi secara real-time dan menampilkan banner status offline tanpa membuang draft scan di `localStorage`.
   - **Splash & Linear Progress Loader** saat memuat halaman web.
   - **Layar Pemulihan Offline Native** jika server PC belum aktif atau jaringan terputus, lengkap dengan tombol **Coba Sambungkan Lagi**, **Buka Cache Offline**, **Scan QR / Ganti Server**, dan **Auto-Reconnect** saat Wi-Fi tersambung kembali.
4. **Cetak LJK & Ekspor Laporan (`NativeWebBridge.kt`)**:
   - Mendukung cetak / simpan PDF langsung via `PrintManager` Android saat `window.print()` dipanggil.
   - Mendukung ekspor Excel/CSV (`blob:`) dari halaman Hasil, Kunci, Guru, dan Siswa langsung ke folder `Downloads/WMVAA-Grader/`.
5. **Kenyamanan Scan**:
   - Opsi **Layar Tetap Menyala** (`FLAG_KEEP_SCREEN_ON`) agar layar HP tidak mati saat memindai puluhan lembar LJK.
   - **Pull-to-Refresh** pintar (hanya aktif di posisi scroll paling atas).

## Cara Membuka & Build APK

1. Buka folder `E:\MY_DEV\WMVAA AI Grader Pro\wmvaa-ai-grader-pro-mobile` menggunakan **Android Studio**.
2. Jika diminta mengunduh **Android SDK (API 34)** dan Build Tools, klik *Install / Download* melalui SDK Manager di Android Studio (otomatis tersimpan ke `C:\Users\wmvaa\AppData\Local\Android\Sdk`).
3. Tunggu Gradle Sync selesai, lalu pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)** (atau jalankan `.\gradlew.bat assembleDebug` di terminal setelah Android SDK terpasang).
4. File APK siap pasang akan tersedia di `app\build\outputs\apk\debug\app-debug.apk`.
