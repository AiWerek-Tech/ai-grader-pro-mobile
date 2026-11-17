# WMVAA AI Grader Pro — Android Mobile App (Kotlin)

Aplikasi Android native berbasis Kotlin untuk **WMVAA AI Grader Pro** yang menghubungkan guru dan admin langsung ke server cloud sekolah maupun server LAN lokal dengan dukungan kamera resolusi penuh, pemindai QR, cetak A4, ekspor Excel/CSV, serta perlindungan anti-crash & cache offline.

* 🌐 **Aplikasi Web Live:** [https://app.wmvaa.id/ai-grader](https://app.wmvaa.id/ai-grader)
* 🏫 **Portal Resmi WMVAA:** [https://wmvaa.id](https://wmvaa.id) · [https://app.wmvaa.id](https://app.wmvaa.id)
* 🖥️ **Repository Backend Web:** [AiWerek-Tech/ai-grader-pro](https://github.com/AiWerek-Tech/ai-grader-pro)

---

## Fitur Utama Mobile

### 1. Koneksi Cloud & Server Lokal Fleksibel (`ServerSetupActivity.kt`, `LanServerDiscovery.kt`, `PrefsManager.kt`)
* **Hosting Cloud Resmi 1-Klik:** Secara default langsung terhubung ke `https://app.wmvaa.id/ai-grader` (memaksa skema `HTTPS` aman untuk domain publik dan mempertahankan `HTTP` hanya untuk IP LAN privat).
* **Scan QR Akses HP Guru:** Ketuk ikon QR pada Dashboard Web, lalu pindai menggunakan kamera aplikasi Android (`CaptureActivity` ZXing) untuk langsung mengonfigurasi alamat server.
* **Auto-Discovery Wi-Fi (LAN):** Memindai subnet jaringan Wi-Fi sekolah pada port `3000` (mendukung endpoint `/ai-grader/api/system/network` maupun `/api/system/network`).
* **Tes Koneksi & Riwayat Server:** Validasi koneksi real-time sebelum menyimpan, daftar hingga 5 server terakhir dengan penanda server aktif, tombol bersihkan riwayat, dan tombol *Kembali* sticky di header.

### 2. Kamera LJK, Multi-Upload Galeri & Live Scanner (`MainActivity.kt`)
* **Kamera HP Cepat (Resolusi Penuh):** Mendukung input kamera web via `MediaStore.ACTION_IMAGE_CAPTURE` + `FileProvider` (`${applicationId}.fileprovider`).
* **Multi-Upload Galeri:** Mendukung pemilihan banyak foto LJK sekaligus (`MODE_OPEN_MULTIPLE`) untuk proses pemindaian batch.
* **Live WebRTC Viewfinder:** Penanganan izin kamera runtime (`PERMISSION_GRANTED` / `PermissionRequest.RESOURCE_VIDEO_CAPTURE`) serta dialog konfirmasi sertifikat SSL khusus untuk IP jaringan lokal (RFC1918) pada mode HTTPS LAN.

### 3. Ergonomi Scroll & Smart Pull-to-Refresh (`MainActivity.kt`, `NativeWebBridge.kt`)
* Sinkronisasi state scroll & modal melalui JavaScript Bridge (`WMVAANative.setSwipeRefreshEnabled`) yang bekerja sama dengan `PwaOfflineBridge.tsx`.
* Ambang tarikan pull-to-refresh tinggi (`320px`) serta pengecekan `webView.scrollY > 0` dan `canScrollVertically(-1)` agar scroll naik-turun di HP terasa mulus dan tidak me-refresh halaman secara tidak sengaja.

### 4. Cetak LJK & Simpan Ekspor Dokumen (`NativeWebBridge.kt`)
* **Cetak / Simpan PDF:** Memetakan `window.print()` ke layanan `PrintManager` Android dengan format kertas standar **ISO A4**.
* **Ekspor Excel / CSV / Kartu Login:** Menangkap URL `blob:` dari browser dan menyimpannya ke folder publik `Downloads/WMVAA-Grader/` menggunakan `MediaStore` (Android 10+) maupun `Environment.DIRECTORY_DOWNLOADS`.

### 5. Stabilitas WebView, Pemulihan Koneksi & Kenyamanan Scan (`NetworkMonitor.kt`, `MainActivity.kt`)
* **Layar Tetap Menyala (`FLAG_KEEP_SCREEN_ON`):** Toggle di Pengaturan Server agar layar HP tidak mati saat memindai puluhan lembar LJK.
* **Pembersihan Cache & Service Worker Otomatis saat Upgrade:** Menghapus direktori usang `app_webview/Service Worker` dan `Cache` pada upgrade `versionCode` tanpa menghapus draft `Local Storage` maupun cookie sesi login.
* **Pemantauan Jaringan & Pemulihan Error:** Banner status offline real-time, auto-reconnect saat koneksi kembali tersedia, layar pemulihan native (*Coba Sambungkan Lagi*, *Buka Cache Offline*, *Ganti Server / Scan QR*), serta proteksi anti-crash-loop pada `onRenderProcessGone`.

---

## Spesifikasi Teknis & Struktur

* **Package / Application ID:** `com.wmvaa.aigrader`
* **Minimum SDK:** API 24 (Android 7.0) · **Target & Compile SDK:** API 34 (Android 14)
* **Komponen Utama:**
  * `MainActivity.kt` — Container `WebView` + `SwipeRefreshLayout`, kamera/galeri chooser, SSL LAN handler, pemulihan error & render process guard.
  * `ServerSetupActivity.kt` — Pengaturan koneksi cloud/LAN, QR scanner, tes koneksi, dan opsi *Keep Screen On*.
  * `NativeWebBridge.kt` — `@JavascriptInterface` (`WMVAANative`) untuk print A4, simpan file Base64/blob, buka pengaturan server, dan kontrol swipe-refresh.
  * `LanServerDiscovery.kt` — Penemuan otomatis dan verifikasi HTTP endpoint server WMVAA.
  * `PrefsManager.kt` — Normalisasi URL hosting subfolder (`/ai-grader`), riwayat server, dan pelacakan versi cache upgrade.
  * `NetworkMonitor.kt` — Pemantau konektivitas jaringan berbasis `ConnectivityManager.NetworkCallback`.

---

## Cara Membuka & Build APK

1. Buka folder `wmvaa-ai-grader-pro-mobile` menggunakan **Android Studio** (pastikan Android SDK API 34 & Java 17 terpasang).
2. Tunggu Gradle Sync selesai.
3. Build APK melalui menu **Build > Build Bundle(s) / APK(s) > Build APK(s)** atau jalankan lewat terminal:
   ```bash
   ./gradlew assembleDebug
   ```
   *(Di Windows PowerShell: `.\gradlew.bat assembleDebug`)*
4. Output APK tersedia di `app/build/outputs/apk/debug/app-debug.apk`.
