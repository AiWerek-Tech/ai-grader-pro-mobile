package com.wmvaa.aigrader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
import androidx.lifecycle.lifecycleScope
import com.wmvaa.aigrader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
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
    private var pendingStorageAction: (() -> Unit)? = null
    private var pendingInstallApkFile: File? = null

    private var pendingChooserAllowMultiple = true
    private var pendingChooserForceCamera = false
    private var pendingChooserAcceptTypes: Array<String> = emptyArray()

    private var hasMainFrameError = false
    private var isRetryingFromOffline = false
    private var isDeviceOnline = true
    private var offlineBannerDismissed = false
    private var isWebPullToRefreshAllowed = true
    private var lastTargetUrl = ""
    private var lastBackPressedMs = 0L
    private val trustedLanHostsSession = mutableSetOf<String>()
    private val splashTimeoutHandler = Handler(Looper.getMainLooper())

    private val setupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                applyKeepScreenOnPreference()
                applyDynamicMixedContentPolicy()
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
                launchChooserWithCamera(
                    allowMultiple = pendingChooserAllowMultiple,
                    forceCameraOnly = pendingChooserForceCamera,
                    acceptTypes = pendingChooserAcceptTypes
                )
            } else if (pendingChooserForceCamera) {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
                Toast.makeText(
                    this,
                    "Izin kamera diperlukan untuk mengambil foto LJK.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                launchGalleryOnlyChooser(
                    allowMultiple = pendingChooserAllowMultiple,
                    acceptTypes = pendingChooserAcceptTypes
                )
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

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "Izin penyimpanan diperlukan pada Android 7–9 untuk menyimpan file ke Downloads.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstallApkFile
            pendingInstallApkFile = null
            if (apk != null && apk.exists()) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                    UpdateChecker.installApkFile(this, apk) { requestUnknownSourcesAndInstall(it) }
                } else {
                    Toast.makeText(
                        this,
                        "Izin 'Instal aplikasi yang tidak dikenal' belum diaktifkan.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        if (!prefs.hasConfiguredServer()) {
            prefs.setServerUrl(PrefsManager.DEFAULT_URL)
        }

        handleDeepLinkIntent(intent, loadImmediately = false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvSplashVersion.text = "ENTERPRISE EDITION v${BuildConfig.VERSION_NAME}"

        applyKeepScreenOnPreference()
        setupWebView()
        setupUiActions()
        setupBackHandler()
        setupNetworkMonitor()
        cleanupOldCameraCaptures()
        checkAppUpdateInBackground()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            hideSplashLoader()
            if (!isDeviceOnline) {
                showOfflineErrorScreen("ERR_INTERNET_DISCONNECTED")
            }
        } else {
            loadConfiguredServer(forceNetwork = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent, loadImmediately = true)
    }

    private fun handleDeepLinkIntent(incomingIntent: Intent?, loadImmediately: Boolean) {
        val dataUri = incomingIntent?.data ?: return
        val raw = dataUri.toString()
        if (raw.isBlank()) return
        val baseUrl = PrefsManager.normalizeBaseUrl(raw)
        val path = PrefsManager.extractPathOrNull(raw)
        prefs.setServerUrl(baseUrl, path)
        if (loadImmediately && ::binding.isInitialized) {
            applyDynamicMixedContentPolicy()
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

    private fun applyDynamicMixedContentPolicy() {
        val host = Uri.parse(prefs.getServerUrl()).host.orEmpty()
        binding.webView.settings.mixedContentMode =
            if (PrefsManager.isPrivateLanIp(host)) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
    }

    private fun ensureLegacyStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            pendingStorageAction = onGranted
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
                binding.layoutOfflineError.visibility == View.VISIBLE ||
                binding.webView.scrollY > 0 ||
                binding.webView.canScrollVertically(-1)
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (!isDeviceOnline) {
                binding.swipeRefresh.isRefreshing = false
                showOfflineErrorScreen("ERR_INTERNET_DISCONNECTED")
                return@setOnRefreshListener
            }
            hasMainFrameError = false
            isRetryingFromOffline = false
            binding.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            val targetUrl = lastTargetUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (targetUrl != null && (binding.webView.url.isNullOrBlank() || binding.webView.url?.startsWith("chrome-error://") == true)) {
                binding.webView.loadUrl(targetUrl)
            } else {
                binding.webView.reload()
            }
        }

        binding.btnSplashChangeServer.setOnClickListener { openServerSetup() }
        binding.btnBannerRetry.setOnClickListener { retryCurrentOrHome() }
        binding.btnBannerServer.setOnClickListener { openServerSetup() }
        binding.btnBannerDismiss.setOnClickListener {
            offlineBannerDismissed = true
            binding.bannerOffline.visibility = View.GONE
        }
        binding.btnErrorRetry.setOnClickListener { retryCurrentOrHome() }
        binding.btnErrorWifiSettings.setOnClickListener { openNetworkSettings() }
        binding.btnErrorChangeServer.setOnClickListener { openServerSetup() }
        binding.btnErrorOpenOfflineCache.setOnClickListener { openCachedOfflinePage() }
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { connected, _ ->
            isDeviceOnline = connected
            if (connected) {
                offlineBannerDismissed = false
            }

            binding.bannerOffline.visibility =
                if (!connected &&
                    !offlineBannerDismissed &&
                    binding.layoutOfflineError.visibility != View.VISIBLE &&
                    binding.layoutSplashLoader.visibility != View.VISIBLE
                ) View.VISIBLE else View.GONE

            if (connected && hasMainFrameError) {
                retryCurrentOrHome()
            }
        }
        isDeviceOnline = networkMonitor.currentState().first
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
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString WMVAA-Android-Wrapper/${BuildConfig.VERSION_NAME}"
        }
        applyDynamicMixedContentPolicy()

        binding.webView.addJavascriptInterface(
            NativeWebBridge(
                activity = this,
                webView = binding.webView,
                prefs = prefs,
                onOpenServerSettings = { openServerSetup() },
                onSetSwipeRefreshEnabled = { allowed ->
                    isWebPullToRefreshAllowed = allowed
                    binding.swipeRefresh.isEnabled = allowed &&
                        binding.layoutOfflineError.visibility != View.VISIBLE &&
                        binding.layoutSplashLoader.visibility != View.VISIBLE
                },
                onEnsureStoragePermission = { action ->
                    ensureLegacyStoragePermission(action)
                }
            ),
            "WMVAANative"
        )

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Blob ditangani oleh PwaOfflineBridge -> WMVAANative.saveBase64File
                return@setDownloadListener
            }
            ensureLegacyStoragePermission {
                enqueueDownload(url, userAgent, contentDisposition, mimetype)
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WMVAA_WEB",
                        "CONSOLE [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                    )
                }
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
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?: emptyArray()

                pendingChooserAllowMultiple = allowMultiple
                pendingChooserForceCamera = forceCameraOnly
                pendingChooserAcceptTypes = acceptTypes

                val wantsImageOrCamera = forceCameraOnly || acceptsImages(acceptTypes)

                if (!wantsImageOrCamera) {
                    launchGalleryOnlyChooser(allowMultiple, acceptTypes)
                    return true
                }

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchChooserWithCamera(allowMultiple, forceCameraOnly, acceptTypes)
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
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Tidak ada browser ditemukan untuk membuka tautan eksternal.",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
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
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("WMVAA_WEB", "onPageStarted url=$url hasMainFrameError=$hasMainFrameError")
                }

                if (url.isNullOrBlank() || url.startsWith("chrome-error://") || url == "about:blank") {
                    if (hasMainFrameError || url?.startsWith("chrome-error://") == true) {
                        binding.webView.visibility = View.INVISIBLE
                        binding.layoutOfflineError.visibility = View.VISIBLE
                    }
                    return
                }

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    lastTargetUrl = url
                }

                if (!isRetryingFromOffline) {
                    hasMainFrameError = false
                    binding.layoutOfflineError.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WMVAA_WEB",
                        "onPageFinished url=$url title=${view?.title} crashCount=$renderCrashCount hasMainFrameError=$hasMainFrameError"
                    )
                }
                CookieManager.getInstance().flush()
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                resetRetryButtonState()

                if (url != null && url.startsWith("chrome-error://")) {
                    isRetryingFromOffline = false
                    showOfflineErrorScreen("ERR_INTERNET_DISCONNECTED")
                    return
                }

                if (!hasMainFrameError && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    isRetryingFromOffline = false
                    renderCrashCount = 0
                    hideSplashLoader()
                    binding.layoutOfflineError.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                    binding.bannerOffline.visibility =
                        if (!isDeviceOnline && !offlineBannerDismissed) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isEnabled = isWebPullToRefreshAllowed
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(
                        "WMVAA_WEB",
                        "onRenderProcessGone didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}"
                    )
                }
                val now = System.currentTimeMillis()
                renderCrashCount = if (now - lastRenderCrashMs < 15000L) renderCrashCount + 1 else 1
                lastRenderCrashMs = now

                if (renderCrashCount <= 1) {
                    recreate()
                } else {
                    showOfflineErrorScreen("RENDERER_CRASHED")
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(
                        "WMVAA_WEB",
                        "onReceivedError main=${request?.isForMainFrame} url=${request?.url} code=${error?.errorCode} desc=${error?.description}"
                    )
                }
                if (request?.isForMainFrame == true) {
                    isRetryingFromOffline = false
                    view?.stopLoading()
                    val reqUrl = request.url?.toString().orEmpty()
                    if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                        lastTargetUrl = reqUrl
                    }
                    val desc = error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "ERR_INTERNET_DISCONNECTED"
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
                    isRetryingFromOffline = false
                    view?.stopLoading()
                    showOfflineErrorScreen("HTTP_${errorResponse?.statusCode}")
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
                val certFingerprint = extractSslCertificateSha256(error?.certificate)
                if ((certFingerprint.isNotBlank() && prefs.isTrustedLanCert(host, certFingerprint)) ||
                    trustedLanHostsSession.contains(host)
                ) {
                    handler?.proceed()
                    return
                }
                val shortFp = if (certFingerprint.length >= 16) {
                    certFingerprint.take(16).chunked(2).joinToString(":") + "..."
                } else {
                    "Self-Signed LAN"
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sertifikat HTTPS Lokal Sekolah")
                    .setMessage(
                        "Server lokal ($host) menggunakan sertifikat HTTPS lokal ($shortFp) untuk mengaktifkan Live Kamera Viewfinder.\n\nLanjutkan koneksi ke server sekolah ini?"
                    )
                    .setPositiveButton("Izinkan Server Ini") { _, _ ->
                        trustedLanHostsSession.add(host)
                        if (certFingerprint.isNotBlank()) {
                            prefs.addTrustedLanCert(host, certFingerprint)
                        }
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

    private fun extractSslCertificateSha256(sslCert: SslCertificate?): String {
        if (sslCert == null) return ""
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val x509 = sslCert.x509Certificate
                if (x509 != null) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(x509.encoded)
                    return digest.joinToString("") { "%02X".format(it) }
                }
            }
            val bundle = SslCertificate.saveState(sslCert)
            val bytes = bundle?.getByteArray("x509-certificate") ?: return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimetype: String
    ) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                setDescription("Mengunduh file dari WMVAA AI Grader Pro")
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Mengunduh $filename ke folder Downloads...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun acceptsImages(acceptTypes: Array<String>): Boolean {
        if (acceptTypes.isEmpty()) return true
        return acceptTypes.any { raw ->
            val t = raw.trim().lowercase()
            t.isEmpty() ||
                t == "*/*" ||
                t.startsWith("image/") ||
                t.endsWith(".jpg") ||
                t.endsWith(".jpeg") ||
                t.endsWith(".png") ||
                t.endsWith(".webp")
        }
    }

    private fun resolveMimeFilter(acceptTypes: Array<String>): Pair<String, Array<String>?> {
        val cleaned = acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return "image/*" to null

        val mimeList = cleaned.mapNotNull { token ->
            when {
                token.contains("/") -> token
                token.equals(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                token.equals(".xls", true) -> "application/vnd.ms-excel"
                token.equals(".csv", true) -> "text/csv"
                token.equals(".pdf", true) -> "application/pdf"
                token.equals(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                token.equals(".jpg", true) || token.equals(".jpeg", true) -> "image/jpeg"
                token.equals(".png", true) -> "image/png"
                else -> null
            }
        }.distinct()

        return when {
            mimeList.isEmpty() -> "*/*" to null
            mimeList.size == 1 -> mimeList.first() to null
            else -> "*/*" to mimeList.toTypedArray()
        }
    }

    private fun launchChooserWithCamera(
        allowMultiple: Boolean,
        forceCameraOnly: Boolean,
        acceptTypes: Array<String>
    ) {
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

            val (primaryType, extraMimes) = resolveMimeFilter(acceptTypes)
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = primaryType
                if (extraMimes != null) {
                    putExtra(Intent.EXTRA_MIME_TYPES, extraMimes)
                }
                if (allowMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            val chooser = Intent.createChooser(galleryIntent, "Pilih File atau Ambil Foto Kamera").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
            filePickerLauncher.launch(chooser)
        } catch (_: Exception) {
            pendingCameraImageUri = null
            launchGalleryOnlyChooser(allowMultiple, acceptTypes)
        }
    }

    private fun launchGalleryOnlyChooser(allowMultiple: Boolean, acceptTypes: Array<String>) {
        try {
            val (primaryType, extraMimes) = resolveMimeFilter(acceptTypes)
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = primaryType
                if (extraMimes != null) {
                    putExtra(Intent.EXTRA_MIME_TYPES, extraMimes)
                }
                if (allowMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            filePickerLauncher.launch(Intent.createChooser(galleryIntent, "Pilih File"))
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

    private fun cleanupOldCameraCaptures() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                UpdateChecker.cleanupOldUpdateApks(this@MainActivity)
                val dir = File(cacheDir, "ljk_captures")
                if (!dir.exists()) return@launch
                val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun requestUnknownSourcesAndInstall(apkFile: File) {
        pendingInstallApkFile = apkFile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                unknownSourcesLauncher.launch(intent)
            } catch (_: Exception) {
                unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
    }

    private fun checkAppUpdateInBackground() {
        lifecycleScope.launch {
            if (!networkMonitor.currentState().first) return@launch
            val update = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
            if (prefs.getDismissedUpdateVersion() == update.versionName) return@launch
            if (isFinishing || isDestroyed) return@launch

            UpdateChecker.showUpdateDialog(
                activity = this@MainActivity,
                prefs = prefs,
                update = update,
                isManualCheck = false,
                onNeedUnknownSourcesPermission = { apkFile ->
                    requestUnknownSourcesAndInstall(apkFile)
                }
            )
        }
    }

    private fun loadConfiguredServer(forceNetwork: Boolean) {
        val baseUrl = prefs.getServerUrl()
        val startPath = prefs.getStartPath()
        val fullUrl = "$baseUrl$startPath"
        lastTargetUrl = fullUrl

        isDeviceOnline = networkMonitor.currentState().first

        if (!isDeviceOnline) {
            showOfflineErrorScreen("ERR_INTERNET_DISCONNECTED")
            return
        }

        hasMainFrameError = false
        isRetryingFromOffline = false
        binding.layoutOfflineError.visibility = View.GONE
        binding.bannerOffline.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
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

    private fun openCachedOfflinePage() {
        val offlineUrl = "${prefs.getServerUrl()}/offline"
        hasMainFrameError = false
        isRetryingFromOffline = false
        binding.layoutOfflineError.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        binding.webView.loadUrl(offlineUrl)
    }

    private fun retryCurrentOrHome() {
        isDeviceOnline = networkMonitor.currentState().first
        val fallbackUrl = "${prefs.getServerUrl()}${prefs.getStartPath()}"
        val currentWebUrl = binding.webView.url
        val targetUrl = when {
            lastTargetUrl.startsWith("http://") || lastTargetUrl.startsWith("https://") -> lastTargetUrl
            currentWebUrl != null && (currentWebUrl.startsWith("http://") || currentWebUrl.startsWith("https://")) -> currentWebUrl
            else -> fallbackUrl
        }
        lastTargetUrl = targetUrl

        if (!isDeviceOnline) {
            showOfflineErrorScreen("ERR_INTERNET_DISCONNECTED")
            Toast.makeText(
                this,
                "Perangkat masih offline. Aktifkan Wi-Fi atau Data Seluler terlebih dahulu.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        hasMainFrameError = false
        isRetryingFromOffline = true
        binding.btnErrorRetry.isEnabled = false
        binding.btnErrorRetry.text = "Menyambungkan ulang..."
        binding.webView.visibility = View.INVISIBLE
        binding.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        binding.webView.loadUrl(targetUrl)
    }

    private fun resetRetryButtonState() {
        binding.btnErrorRetry.isEnabled = true
        binding.btnErrorRetry.text = getString(R.string.btn_retry)
    }

    private fun showOfflineErrorScreen(detailMsg: String) {
        isDeviceOnline = networkMonitor.currentState().first
        hasMainFrameError = true
        isRetryingFromOffline = false
        hideSplashLoader()
        resetRetryButtonState()
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.isEnabled = false
        binding.progressBar.visibility = View.GONE
        binding.bannerOffline.visibility = View.GONE
        binding.webView.visibility = View.INVISIBLE
        binding.layoutOfflineError.visibility = View.VISIBLE

        if (!isDeviceOnline) {
            binding.tvOfflineStatusBadge.text = "●  TIDAK ADA KONEKSI INTERNET"
            binding.tvErrorTitle.text = getString(R.string.offline_title)
            binding.tvErrorMessage.text = getString(R.string.offline_subtitle)
        } else {
            binding.tvOfflineStatusBadge.text = "●  SERVER TIDAK TERJANGKAU"
            binding.tvErrorTitle.text = getString(R.string.server_unreachable_title)
            binding.tvErrorMessage.text =
                "Jaringan terhubung, namun server WMVAA sedang tidak merespons atau alamat server berubah."
        }

        binding.tvErrorServerDetail.text = "Target: ${prefs.getServerUrl()}  •  Kode: $detailMsg"
    }

    private fun openNetworkSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Buka Pengaturan Wi-Fi / Data Seluler di HP Anda.", Toast.LENGTH_SHORT).show()
            }
        }
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
                if (binding.layoutOfflineError.visibility != View.VISIBLE && binding.webView.canGoBack()) {
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
        if (::binding.isInitialized) {
            binding.webView.onResume()
        }
        applyKeepScreenOnPreference()
        if (::networkMonitor.isInitialized) {
            val connected = networkMonitor.currentState().first
            isDeviceOnline = connected
            if (connected && hasMainFrameError) {
                retryCurrentOrHome()
            } else if (!connected &&
                !offlineBannerDismissed &&
                binding.layoutOfflineError.visibility != View.VISIBLE &&
                binding.layoutSplashLoader.visibility != View.VISIBLE
            ) {
                binding.bannerOffline.visibility = View.VISIBLE
            }
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            binding.webView.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        splashTimeoutHandler.removeCallbacksAndMessages(null)
        if (::networkMonitor.isInitialized) {
            networkMonitor.stop()
        }
        if (::binding.isInitialized) {
            try {
                binding.swipeRefresh.removeView(binding.webView)
                binding.webView.stopLoading()
                binding.webView.webChromeClient = null
                binding.webView.destroy()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        private var lastRenderCrashMs = 0L
        private var renderCrashCount = 0
    }
}
