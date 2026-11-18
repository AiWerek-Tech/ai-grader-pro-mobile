package com.wmvaa.aigrader

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class NativeWebBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val prefs: PrefsManager,
    private val onOpenServerSettings: () -> Unit,
    private val onSetSwipeRefreshEnabled: (Boolean) -> Unit = {},
    private val onEnsureStoragePermission: (() -> Unit) -> Unit = { it() }
) {

    private fun isTrustedOrigin(currentUrl: String?): Boolean {
        if (currentUrl.isNullOrBlank()) return false
        val host = Uri.parse(currentUrl).host?.lowercase() ?: return false
        val configuredHost = Uri.parse(prefs.getServerUrl()).host?.lowercase()
        return host == configuredHost ||
            host == "app.wmvaa.id" ||
            host == "wmvaa.id" ||
            host.endsWith(".wmvaa.id") ||
            PrefsManager.isPrivateLanIp(host)
    }

    @JavascriptInterface
    fun setSwipeRefreshEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            onSetSwipeRefreshEnabled(enabled)
        }
    }

    @JavascriptInterface
    fun printCurrentPage() {
        activity.runOnUiThread {
            if (!isTrustedOrigin(webView.url)) return@runOnUiThread
            try {
                val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager == null) {
                    Toast.makeText(activity, "Layanan cetak tidak tersedia di perangkat ini.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val jobName = "WMVAA_LJK_${System.currentTimeMillis()}"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .build()
                printManager.print(jobName, printAdapter, attrs)
            } catch (e: Exception) {
                Toast.makeText(activity, "Gagal mencetak: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun openServerSettings() {
        activity.runOnUiThread {
            if (!isTrustedOrigin(webView.url)) return@runOnUiThread
            onOpenServerSettings()
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        activity.runOnUiThread {
            if (!isTrustedOrigin(webView.url)) {
                Toast.makeText(activity, "Akses penyimpanan ditolak untuk sumber tidak dikenal.", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            onEnsureStoragePermission {
                Thread {
                    performSaveBase64File(base64Data, fileName, mimeType)
                }.start()
            }
        }
    }

    private fun performSaveBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substringAfter(",")
            } else {
                base64Data
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "wmvaa-export-${System.currentTimeMillis()}.xlsx" }
            val resolvedMime = mimeType.ifBlank { "application/octet-stream" }

            val savedUri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/WMVAA-Grader"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Gagal membuat file di folder Downloads.")
                try {
                    val outStream = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Gagal membuka stream file di Downloads.")
                    outStream.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    val completeValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                    savedUri = uri
                } catch (writeErr: Exception) {
                    resolver.delete(uri, null, null)
                    throw writeErr
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(downloadsDir, "WMVAA-Grader")
                if (!appDir.exists()) appDir.mkdirs()
                val outFile = File(appDir, safeName)
                FileOutputStream(outFile).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                savedUri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outFile
                )
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                showFileSavedDialog(safeName, savedUri, resolvedMime)
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "Gagal menyimpan file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showFileSavedDialog(fileName: String, fileUri: Uri, mimeType: String) {
        Toast.makeText(
            activity,
            "Disimpan ke Downloads/WMVAA-Grader/$fileName",
            Toast.LENGTH_SHORT
        ).show()

        AlertDialog.Builder(activity)
            .setTitle("File Berhasil Disimpan")
            .setMessage("Lokasi: Downloads/WMVAA-Grader/$fileName")
            .setPositiveButton("Buka File") { _, _ ->
                try {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(Intent.createChooser(viewIntent, "Buka dengan"))
                } catch (_: Exception) {
                    Toast.makeText(
                        activity,
                        "Tidak ada aplikasi pembuka untuk tipe file ini. Cek folder Downloads/WMVAA-Grader.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton("Bagikan") { _, _ ->
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "Bagikan $fileName"))
                } catch (_: Exception) {
                    Toast.makeText(activity, "Gagal membuka menu bagikan.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }
}
