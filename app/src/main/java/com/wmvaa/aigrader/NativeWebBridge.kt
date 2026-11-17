package com.wmvaa.aigrader

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class NativeWebBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val onOpenServerSettings: () -> Unit,
    private val onSetSwipeRefreshEnabled: (Boolean) -> Unit = {}
) {

    @JavascriptInterface
    fun setSwipeRefreshEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            onSetSwipeRefreshEnabled(enabled)
        }
    }

    @JavascriptInterface
    fun printCurrentPage() {
        activity.runOnUiThread {
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
            onOpenServerSettings()
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "wmvaa-export-${System.currentTimeMillis()}.xlsx" }
            val resolvedMime = mimeType.ifBlank { "application/octet-stream" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/WMVAA-Grader"
                    )
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Gagal membuat file di folder Downloads.")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(downloadsDir, "WMVAA-Grader")
                if (!appDir.exists()) appDir.mkdirs()
                val outFile = File(appDir, safeName)
                FileOutputStream(outFile).use { out ->
                    out.write(bytes)
                }
            }

            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "Berhasil disimpan ke Downloads/WMVAA-Grader/$safeName",
                    Toast.LENGTH_LONG
                ).show()
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
}
