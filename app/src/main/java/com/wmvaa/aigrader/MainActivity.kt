package com.wmvaa.aigrader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wmvaa.aigrader.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var networkMonitor: NetworkMonitor

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraImageUri: Uri? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private var hasMainFrameError = false
    private var isDeviceOnline = true
    private var isWebPullToRefreshAllowed = true
    private var lastBackPressedMs = 0L
    private var lastRenderCrashMs = 0L
    private var renderCrashCount = 0
    private val trustedLanHosts = mutableSetOf<String>()
    private val splashTimeoutHandler = Handler(Looper.getMainLooper())

    private val setupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                applyKeepScreenOnPreference()
                loadConfiguredServer(forceNetwork = true)
            }
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK) {
                pendingCameraImageUri = null
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val uris = mutableListOf<Uri>()
            val data = result.data
            if (data != null) {
                val clipData = data.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                    }
                } else if (data.data != null) {
                    data.data?.let { uris.add(it) }
                }
            }

            if (uris.isEmpty() && pendingCameraImageUri != null) {
                pendingCameraImageUri?.let { uris.add(it) }
            }
            pendingCameraImageUri = null

            callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        }

    private val cameraForFileChooserPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchChooserWithCamera(allowMultiple = true, forceCameraOnly = false)
            } else {
                // Tetap buka galeri jika izin kamera ditolak
                launchGalleryOnlyChooser(allowMultiple = true)
            }
        }

    private val cameraForWebRtcPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (req != null) {
                if (granted) req.grant(req.resources) else req.deny()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        // Jika belum ada server tersimpan, otomatis gunakan Hosting Cloud Resmi (https://app.wmvaa.id/ai-grader)
        if (!prefs.hasConfiguredServer()) {
            prefs.setServerUrl(PrefsManager.DEFAULT_URL)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyKeepScreenOnPreference()
        setupWebView()
        setupUiActions()
        setupBackHandler()
        setupNetworkMonitor()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            hideSplashLoader()
        } else {
            loadConfiguredServer(forceNetwork = true)
        }
    }

    private fun applyKeepScreenOnPreference() {
        if (prefs.isKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupUiActions() {
        binding.swipeRefresh.setColorSchemeColors(
            getColor(R.color.accent_cyan),
            getColor(R.color.accent_violet),
            getColor(R.color.accent_emerald)
        )
        binding.swipeRefresh.setDistanceToTriggerSync(320)
        binding.swipeRefresh.setSlingshotDistance(320)
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            !isWebPullToRefreshAllowed ||
                binding.webView.scrollY > 0 ||
                binding.webView.canScrollVertically(-1)
        }
        binding.swipeRefresh.setOnRefreshListener {
            hasMainFrameError = false
            binding.webView.settings.cacheMode =
                if (isDeviceOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            binding.webView.reload()
        }

        binding.btnSplashChangeServer.setOnClickListener { openServerSetup() }
        binding.btnBannerRetry.setOnClickListener { retryCurrentOrHome() }
        binding.btnBannerServer.setOnClickListener { openServerSetup() }
        binding.btnErrorRetry.setOnClickListener { retryCurrentOrHome() }
        binding.btnErrorChangeServer.setOnClickListener { openServerSetup() }

        binding.btnErrorOpenCache.setOnClickListener {
            hasMainFrameError = false
            binding.layoutOfflineError.visibility = View.GONE
            binding.webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            if (binding.webView.url.isNullOrBlank()) {
                binding.webView.loadUrl("${prefs.getServerUrl()}${prefs.getStartPath()}")
            } else {
                binding.webView.reload()
            }
            Toast.makeText(this, "Membuka data & halaman tersimpan (Cache Offline)...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { connected, _ ->
            val wasOffline = !isDeviceOnline
            isDeviceOnline = connected

            binding.bannerOffline.visibility = if (connected) View.GONE else View.VISIBLE

            // Auto-reconnect otomatis ketika Wi-Fi sekolah tersambung kembali
            if (connected && wasOffline && hasMainFrameError) {
                retryCurrentOrHome()
            }
        }
        networkMonitor.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        if (prefs.needsCacheFlushOnUpgrade(BuildConfig.VERSION_CODE)) {
            binding.webView.clearCache(true)
            clearStaleWebViewServiceWorkerData()
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString WMVAA-Android-Wrapper/${BuildConfig.VERSION_NAME}"
        }

        binding.webView.addJavascriptInterface(
            NativeWebBridge(
                activity = this,
                webView = binding.webView,
                onOpenServerSettings = { openServerSetup() },
                onSetSwipeRefreshEnabled = { allowed ->
                    isWebPullToRefreshAllowed = allowed
                    binding.swipeRefresh.isEnabled = allowed &&
                        binding.layoutOfflineError.visibility != View.VISIBLE &&
                        binding.layoutSplashLoader.visibility != View.VISIBLE
                }
            ),
            "WMVAANative"
        )

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Blob ditangani oleh PwaOfflineBridge -> WMVAANative.saveBase64File
                return@setDownloadListener
            }
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Mengunduh file dari WMVAA AI Grader Pro")
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Mengunduh file ke folder Downloads...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                android.util.Log.d(
                    "WMVAA_WEB",
                    "CONSOLE [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                )
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 1..99) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
                if (newProgress >= 80 && !hasMainFrameError) {
                    hideSplashLoader()
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources
                if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(resources)
                    } else {
                        pendingWebPermissionRequest = request
                        cameraForWebRtcPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    request.grant(resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val forceCameraOnly = fileChooserParams?.isCaptureEnabled == true

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchChooserWithCamera(allowMultiple, forceCameraOnly)
                } else if (forceCameraOnly) {
                    cameraForFileChooserPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    cameraForFileChooserPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") {
                    val configuredHost = Uri.parse(prefs.getServerUrl()).host
                    val targetHost = url.host
                    if (targetHost == configuredHost || (targetHost != null && PrefsManager.isPrivateLanIp(targetHost))) {
                        return false
                    }
                    // Link eksternal (misal Drive/WhatsApp) buka di browser sistem
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                android.util.Log.d("WMVAA_WEB", "onPageStarted url=$url")
                hasMainFrameError = false
                binding.layoutOfflineError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("WMVAA_WEB", "onPageFinished url=$url title=${view?.title} crashCount=$renderCrashCount")
                CookieManager.getInstance().flush()
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                if (url != null && url.startsWith("chrome-error://")) {
                    showOfflineErrorScreen("Halaman gagal dimuat oleh WebView.")
                    return
                }
                if (!hasMainFrameError) {
                    renderCrashCount = 0
                    hideSplashLoader()
                    binding.layoutOfflineError.visibility = View.GONE
                    binding.swipeRefresh.isEnabled = isWebPullToRefreshAllowed
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                android.util.Log.e("WMVAA_WEB", "onRenderProcessGone didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}")
                val now = System.currentTimeMillis()
                renderCrashCount = if (now - lastRenderCrashMs < 10000L) renderCrashCount + 1 else 1
                lastRenderCrashMs = now

                if (renderCrashCount <= 1) {
                    // Pulihkan instance WebView secara otomatis pada crash pertama
                    recreate()
                } else {
                    // Cegah crash loop berulang: tampilkan layar pemulihan ramah pengguna
                    showOfflineErrorScreen("Renderer WebView berhenti merespons. Silakan Muat Ulang atau bersihkan cache aplikasi.")
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.e("WMVAA_WEB", "onReceivedError main=${request?.isForMainFrame} url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString() ?: "Server lokal tidak dapat dijangkau."
                    showOfflineErrorScreen(desc)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 502) {
                    showOfflineErrorScreen("Server mengembalikan status HTTP ${errorResponse?.statusCode}")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                val host = error?.url?.let { Uri.parse(it).host }.orEmpty()
                if (!PrefsManager.isPrivateLanIp(host)) {
                    handler?.cancel()
                    return
                }
                if (trustedLanHosts.contains(host)) {
                    handler?.proceed()
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sertifikat HTTPS Lokal Sekolah")
                    .setMessage("Server lokal ($host) menggunakan sertifikat HTTPS lokal untuk mengaktifkan Live Kamera Viewfinder. Lanjutkan koneksi ke server sekolah?")
                    .setPositiveButton("Izinkan Server Ini") { _, _ ->
                        trustedLanHosts.add(host)
                        handler?.proceed()
                    }
                    .setNegativeButton("Batal") { _, _ ->
                        handler?.cancel()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun launchChooserWithCamera(allowMultiple: Boolean, forceCameraOnly: Boolean) {
        try {
            val captureUri = createTempCameraImageUri()
            pendingCameraImageUri = captureUri

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (forceCameraOnly) {
                filePickerLauncher.launch(cameraIntent)
                return
            }

            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                if (allowMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            val chooser = Intent.createChooser(galleryIntent, "Pilih Foto LJK atau Ambil Kamera").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
            filePickerLauncher.launch(chooser)
        } catch (e: Exception) {
            pendingCameraImageUri = null
            launchGalleryOnlyChooser(allowMultiple)
        }
    }

    private fun launchGalleryOnlyChooser(allowMultiple: Boolean) {
        try {
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                if (allowMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            filePickerLauncher.launch(Intent.createChooser(galleryIntent, "Pilih Gambar LJK"))
        } catch (_: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun createTempCameraImageUri(): Uri {
        val dir = File(cacheDir, "ljk_captures").apply {
            if (!exists()) mkdirs()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFile = File.createTempFile("LJK_${stamp}_", ".jpg", dir)
        return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", imageFile)
    }

    private fun loadConfiguredServer(forceNetwork: Boolean) {
        val baseUrl = prefs.getServerUrl()
        val startPath = prefs.getStartPath()
        val fullUrl = "$baseUrl$startPath"

        hasMainFrameError = false
        binding.layoutOfflineError.visibility = View.GONE
        binding.layoutSplashLoader.visibility = View.VISIBLE
        binding.tvSplashServerUrl.text = baseUrl
        binding.tvSplashStatus.text = getString(R.string.connecting_to_server)

        binding.webView.settings.cacheMode =
            if (forceNetwork && isDeviceOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

        splashTimeoutHandler.removeCallbacksAndMessages(null)
        splashTimeoutHandler.postDelayed({
            if (binding.layoutSplashLoader.visibility == View.VISIBLE) {
                binding.tvSplashStatus.text =
                    "Masih menunggu respons server... Anda dapat mengganti IP jika alamat server berubah."
            }
        }, 7000L)

        binding.webView.loadUrl(fullUrl)
    }

    private fun retryCurrentOrHome() {
        hasMainFrameError = false
        binding.layoutOfflineError.visibility = View.GONE
        binding.webView.settings.cacheMode =
            if (isDeviceOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        val current = binding.webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            loadConfiguredServer(forceNetwork = true)
        } else {
            binding.webView.reload()
        }
    }

    private fun showOfflineErrorScreen(detailMsg: String) {
        hasMainFrameError = true
        hideSplashLoader()
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        binding.layoutOfflineError.visibility = View.VISIBLE
        binding.tvErrorServerDetail.text = "Target: ${prefs.getServerUrl()}"
        binding.tvErrorMessage.text =
            "Server tidak merespons ($detailMsg).\nPastikan internet HP aktif atau buka mode cache offline."
    }

    private fun clearStaleWebViewServiceWorkerData() {
        try {
            val webViewDir = File(applicationInfo.dataDir, "app_webview")
            listOf(
                File(webViewDir, "Service Worker"),
                File(webViewDir, "Default/Service Worker"),
                File(webViewDir, "Cache"),
                File(webViewDir, "Default/Cache"),
                File(webViewDir, "Code Cache")
            ).forEach { dir ->
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun hideSplashLoader() {
        splashTimeoutHandler.removeCallbacksAndMessages(null)
        binding.layoutSplashLoader.visibility = View.GONE
    }

    private fun openServerSetup() {
        setupLauncher.launch(
            Intent(this, ServerSetupActivity::class.java).apply {
                putExtra(ServerSetupActivity.EXTRA_INITIAL_SETUP, false)
            }
        )
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutOfflineError.visibility == View.VISIBLE) {
                    openServerSetup()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedMs < 2000L) {
                    finish()
                } else {
                    lastBackPressedMs = now
                    Toast.makeText(
                        this@MainActivity,
                        "Tekan sekali lagi untuk keluar dari aplikasi",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnPreference()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        splashTimeoutHandler.removeCallbacksAndMessages(null)
        if (::networkMonitor.isInitialized) {
            networkMonitor.stop()
        }
        super.onDestroy()
    }
}
