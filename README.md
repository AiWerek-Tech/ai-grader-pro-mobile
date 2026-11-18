# WMVAA AI Grader Pro — Android Mobile App (Kotlin)

Aplikasi Android native berbasis Kotlin untuk **WMVAA AI Grader Pro** yang menghubungkan guru dan admin langsung ke server cloud sekolah maupun server LAN lokal dengan dukungan kamera resolusi penuh, pemindai QR berlampu senter (*flash*), cetak A4, ekspor & bagikan Excel/CSV/PDF, Deep Links, serta perlindungan anti-crash & cache offline.

* 🌐 **Aplikasi Web Live:** [https://app.wmvaa.id/ai-grader](https://app.wmvaa.id/ai-grader)
* 🏫 **Portal Resmi WMVAA:** [https://wmvaa.id](https://wmvaa.id) · [https://app.wmvaa.id](https://app.wmvaa.id)
* 🖥️ **Repository Backend Web:** [AiWerek-Tech/ai-grader-pro](https://github.com/AiWerek-Tech/ai-grader-pro)

---

## Fitur Utama Mobile (`v2.0.9`)

### 1. Koneksi Cloud, Deep Link & Server Lokal Fleksibel (`ServerSetupActivity.kt`, `LanServerDiscovery.kt`, `PrefsManager.kt`, `QrCaptureActivity.kt`)
* **Hosting Cloud Resmi 1-Klik:** Secara default langsung terhubung ke `https://app.wmvaa.id/ai-grader` (wajib skema `HTTPS` murni untuk domain resmi dan mendukung `HTTP`/sertifikat lokal untuk IP LAN privat RFC1918).
* **Scan QR Akses HP Guru + Tombol Senter (Flash):** Ketuk ikon QR pada Dashboard Web, lalu pindai menggunakan kamera aplikasi Android (`QrCaptureActivity`) lengkap dengan tombol lampu senter (*torch*) untuk ruangan redup.
* **Auto-Discovery Wi-Fi (LAN) & Fallback Subfolder:** Memindai subnet jaringan Wi-Fi sekolah pada port `3000` dengan fallback otomatis `/ai-grader/api/system/network` maupun `/api/system/network` serta dukungan sertifikat HTTPS LAN self-signed.
* **Deep Link & App Links:** Mendukung pembukaan langsung dari tautan `https://app.wmvaa.id/ai-grader/...` maupun `wmvaa://grader/...`.

### 2. Kamera LJK, Multi-Upload Galeri/Dokumen & Live Scanner (`MainActivity.kt`)
* **Kamera HP Cepat (Resolusi Penuh) & Auto-Cleanup:** Mendukung input kamera web via `MediaStore.ACTION_IMAGE_CAPTURE` + `FileProvider` (`${applicationId}.fileprovider`) dengan pembersihan otomatis file tangkapan sementara (>24 jam).
* **Multi-Upload Galeri & Dokumen:** Mendukung pemilihan banyak foto LJK sekaligus (`MODE_OPEN_MULTIPLE`) maupun file dokumen (`.xlsx`, `.csv`, `.pdf`, `.docx`) sesuai `accept` pada input web.
* **Live WebRTC Viewfinder & Pinning Sertifikat LAN:** Penanganan izin kamera runtime serta penyimpanan fingerprint SHA-256 sertifikat SSL untuk IP jaringan lokal (RFC1918).

### 3. Ergonomi Scroll & Smart Pull-to-Refresh (`MainActivity.kt`, `NativeWebBridge.kt`)
* Sinkronisasi state scroll & modal melalui JavaScript Bridge (`WMVAANative.setSwipeRefreshEnabled`) yang bekerja sama dengan `PwaOfflineBridge.tsx`.
* Ambang tarikan pull-to-refresh tinggi (`320px`) serta pengecekan `webView.scrollY > 0` dan `canScrollVertically(-1)` agar scroll naik-turun di HP terasa mulus.

### 4. Cetak LJK, Simpan & Bagikan Ekspor Dokumen (`NativeWebBridge.kt`)
* **Cetak / Simpan PDF:** Memetakan `window.print()` ke layanan `PrintManager` Android dengan format kertas standar **ISO A4**.
* **Ekspor Excel / CSV / Kartu Login + Dialog Buka & Bagikan:** Menangkap URL `blob:` dari browser, menyimpannya ke folder publik `Downloads/WMVAA-Grader/` (mendukung `MediaStore` Android 10+ maupun izin penyimpanan runtime Android 7–9), lalu menampilkan dialog cepat **Buka File** atau **Bagikan (WhatsApp/Lainnya)**.

### 5. Stabilitas WebView, Pemulihan Koneksi & In-App Update (`NetworkMonitor.kt`, `UpdateChecker.kt`, `MainActivity.kt`)
* **In-App Update Checker:** Memeriksa rilis terbaru di GitHub Releases secara asinkron dan menawarkan unduhan langsung jika tersedia versi baru.
* **Deteksi Wi-Fi LAN Offline & Pemulihan Error:** Mendukung jaringan Wi-Fi lokal sekolah tanpa internet publik, menyediakan layar pemulihan native (*Coba Sambungkan Lagi*, *Pengaturan Wi-Fi / Internet*, *Ganti Server / Scan QR*, *Buka Draft / Cache Offline*), serta proteksi anti-crash-loop lintas rekreasi Activity pada `onRenderProcessGone`.

---

## Spesifikasi Teknis & Struktur

* **Package / Application ID:** `com.wmvaa.aigrader`
* **Minimum SDK:** API 24 (Android 7.0) · **Target & Compile SDK:** API 34 (Android 14)
* **Optimasi Build Release:** APK Signature Scheme v1 + v2 + v3, R8 Minification & Resource Shrinking aktif, aset grafis terkompresi WebP.

---

## Cara Test & Build APK

1. Jalankan unit test lokal:
   ```bash
   ./gradlew testDebugUnitTest
   ```
2. Build Release APK (menggunakan `keystore.properties` lokal atau environment variable):
   ```bash
   ./gradlew assembleRelease
   ```
   *(Di Windows PowerShell: `.\gradlew.bat testDebugUnitTest assembleRelease`)*
3. Output APK tersedia di `app/build/outputs/apk/release/app-release.apk`.
