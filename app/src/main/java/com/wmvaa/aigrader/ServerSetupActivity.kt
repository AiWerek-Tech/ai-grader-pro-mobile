package com.wmvaa.aigrader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wmvaa.aigrader.databinding.ActivityServerSetupBinding
import kotlinx.coroutines.launch

class ServerSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerSetupBinding
    private lateinit var prefs: PrefsManager
    private var isInitialSetup = false

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            val baseUrl = PrefsManager.normalizeBaseUrl(contents)
            val startPath = PrefsManager.extractPathOrNull(contents)
            binding.etServerUrl.setText(baseUrl)
            verifyAndSave(baseUrl, startPath)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            } else {
                Toast.makeText(
                    this,
                    "Izin kamera diperlukan untuk memindai QR Akses HP Guru.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvSetupVersion.text = "WMVAA AI Grader Pro v${BuildConfig.VERSION_NAME}"

        prefs = PrefsManager(this)
        isInitialSetup = intent.getBooleanExtra(EXTRA_INITIAL_SETUP, false)

        binding.btnCloseSetup.visibility = if (isInitialSetup) View.GONE else View.VISIBLE
        binding.btnCloseSetup.setOnClickListener { finish() }

        val activeUrl = prefs.getServerUrl()
        binding.tvActiveServerUrl.text = activeUrl
        binding.etServerUrl.setText(activeUrl)
        binding.etServerUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.etServerUrl.text?.toString().orEmpty()
                verifyAndSave(input, null)
                true
            } else {
                false
            }
        }

        binding.switchKeepScreenOn.isChecked = prefs.isKeepScreenOn()
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.setKeepScreenOn(checked)
        }

        binding.btnClearHistory.setOnClickListener {
            prefs.clearHistory()
            renderHistory()
            Toast.makeText(this, "Riwayat server dibersihkan.", Toast.LENGTH_SHORT).show()
        }

        binding.btnScanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnUseCloudServer.setOnClickListener {
            binding.etServerUrl.setText(PrefsManager.DEFAULT_URL)
            verifyAndSave(PrefsManager.DEFAULT_URL, "/dashboard/analisis")
        }

        binding.btnAutoDiscover.setOnClickListener {
            runAutoDiscovery()
        }

        binding.btnTestConnection.setOnClickListener {
            val input = binding.etServerUrl.text?.toString().orEmpty()
            testConnectionOnly(input)
        }

        binding.btnSaveAndConnect.setOnClickListener {
            val input = binding.etServerUrl.text?.toString().orEmpty()
            verifyAndSave(input, null)
        }

        renderHistory()
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Arahkan kamera ke 'QR Akses HP Guru' di Dashboard PC")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
            captureActivity = QrCaptureActivity::class.java
        }
        qrLauncher.launch(options)
    }

    private fun runAutoDiscovery() {
        binding.progressDiscovery.visibility = View.VISIBLE
        binding.btnAutoDiscover.isEnabled = false
        binding.tvDiscoveryStatus.text = "Memindai Wi-Fi lokal pada port 3000..."
        binding.layoutDiscoveredServers.visibility = View.GONE
        binding.layoutDiscoveredServers.removeAllViews()

        lifecycleScope.launch {
            val candidates = (listOf(prefs.getServerUrl(), PrefsManager.DEFAULT_URL) + prefs.getHistory()).distinct()
            val found = LanServerDiscovery.discoverLanServers(candidates)

            binding.progressDiscovery.visibility = View.GONE
            binding.btnAutoDiscover.isEnabled = true

            if (found.isEmpty()) {
                binding.tvDiscoveryStatus.text =
                    "Tidak ada server ditemukan otomatis. Pastikan START_SERVER_SEKOLAH.bat aktif & Wi-Fi sama."
                return@launch
            }

            binding.tvDiscoveryStatus.text = "Ditemukan ${found.size} server aktif! Ketuk untuk menghubungkan:"
            binding.layoutDiscoveredServers.visibility = View.VISIBLE

            for (srv in found) {
                val btn = MaterialButton(this@ServerSetupActivity).apply {
                    text = "✅  ${srv.hostname} (${srv.url})"
                    isAllCaps = false
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 12 }
                    setOnClickListener {
                        binding.etServerUrl.setText(srv.url)
                        saveAndProceed(srv.url, null)
                    }
                }
                binding.layoutDiscoveredServers.addView(btn)
            }
        }
    }

    private fun testConnectionOnly(rawUrl: String) {
        val normalized = PrefsManager.normalizeBaseUrl(rawUrl)
        binding.etServerUrl.setText(normalized)
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.setTextColor(getColor(R.color.text_secondary))
        binding.tvTestResult.text = "Menguji koneksi ke $normalized ..."

        lifecycleScope.launch {
            val res = LanServerDiscovery.testServer(normalized)
            res.onSuccess { srv ->
                binding.tvTestResult.setTextColor(getColor(R.color.accent_emerald))
                binding.tvTestResult.text = "✓ Terhubung ke ${srv.hostname} (${srv.url})"
            }.onFailure { err ->
                binding.tvTestResult.setTextColor(getColor(R.color.accent_rose))
                binding.tvTestResult.text = "✕ Gagal terhubung: ${err.message ?: "Server tidak merespons"}"
            }
        }
    }

    private fun verifyAndSave(rawUrl: String, startPath: String?) {
        val normalized = PrefsManager.normalizeBaseUrl(rawUrl)
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.setTextColor(getColor(R.color.text_secondary))
        binding.tvTestResult.text = "Memeriksa $normalized ..."
        binding.btnSaveAndConnect.isEnabled = false

        lifecycleScope.launch {
            val res = LanServerDiscovery.testServer(normalized, timeoutMs = 6000)
            binding.btnSaveAndConnect.isEnabled = true

            res.onSuccess { srv ->
                saveAndProceed(srv.url, startPath)
            }.onFailure {
                // Tetap simpan jika guru ingin menyimpan IP meski server baru dinyalakan / offline mode
                saveAndProceed(normalized, startPath)
                Toast.makeText(
                    this@ServerSetupActivity,
                    "Alamat disimpan. Membuka $normalized ...",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveAndProceed(baseUrl: String, startPath: String?) {
        prefs.setServerUrl(baseUrl, startPath)
        if (isInitialSetup) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        } else {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun renderHistory() {
        val history = prefs.getHistory()
        val activeUrl = prefs.getServerUrl()
        if (history.isEmpty()) {
            binding.cardHistory.visibility = View.GONE
            return
        }
        binding.cardHistory.visibility = View.VISIBLE
        binding.btnClearHistory.visibility = if (history.size > 1) View.VISIBLE else View.GONE
        binding.layoutHistoryList.removeAllViews()

        for (url in history) {
            val isCurrent = url.equals(activeUrl, ignoreCase = true)
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (isCurrent) "● Aktif: $url" else url
                isAllCaps = false
                textSize = 12f
                setTextColor(getColor(if (isCurrent) R.color.accent_cyan else R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                setOnClickListener {
                    binding.etServerUrl.setText(url)
                    saveAndProceed(url, null)
                }
            }
            binding.layoutHistoryList.addView(btn)
        }
    }

    companion object {
        const val EXTRA_INITIAL_SETUP = "extra_initial_setup"
    }
}
