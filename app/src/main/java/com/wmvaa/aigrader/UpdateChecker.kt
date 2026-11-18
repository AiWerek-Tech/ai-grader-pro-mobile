package com.wmvaa.aigrader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class AppReleaseUpdate(
    val versionName: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String
)

object UpdateChecker {

    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/AiWerek-Tech/ai-grader-pro-mobile/releases/latest"

    suspend fun checkForUpdate(currentVersionName: String): AppReleaseUpdate? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_LATEST_RELEASE_API)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "WMVAA-Android-Wrapper/$currentVersionName")
                }

                if (conn.responseCode !in 200..299) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                if (tagName.isEmpty() || !isNewerVersion(tagName, currentVersionName)) {
                    return@withContext null
                }

                val htmlUrl = json.optString(
                    "html_url",
                    "https://github.com/AiWerek-Tech/ai-grader-pro-mobile/releases/latest"
                )
                val notes = json.optString("body", "").trim()
                var apkDownloadUrl = htmlUrl

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val browserUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && browserUrl.isNotBlank()) {
                            apkDownloadUrl = browserUrl
                            break
                        }
                    }
                }

                AppReleaseUpdate(
                    versionName = tagName,
                    downloadUrl = apkDownloadUrl,
                    releasePageUrl = htmlUrl,
                    releaseNotes = notes
                )
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun downloadUpdateApk(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (percent: Int, downloadedMb: String, totalMb: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val updatesDir = File(context.cacheDir, "apk_updates").apply {
                if (!exists()) mkdirs()
            }
            val targetFile = File(updatesDir, "wmvaa-ai-grader-pro-v$versionName-android.apk")
            val tempFile = File(updatesDir, "wmvaa-ai-grader-pro-v$versionName-android.apk.part")
            if (tempFile.exists()) tempFile.delete()

            var currentUrl = downloadUrl
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "WMVAA-Android-Wrapper/${BuildConfig.VERSION_NAME}")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) break
                    currentUrl = location
                    redirects++
                } else {
                    break
                }
            }

            val finalConn = conn ?: throw IllegalStateException("Koneksi unduhan gagal dibuka.")
            if (finalConn.responseCode !in 200..299) {
                throw IllegalStateException("Server mengembalikan HTTP ${finalConn.responseCode}")
            }

            val totalBytes = finalConn.contentLengthLong
            val totalMbStr = if (totalBytes > 0) String.format("%.2f MB", totalBytes / 1_000_000.0) else "..."

            finalConn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloadedBytes = 0L
                    var lastReportedPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            val dlMbStr = String.format("%.2f MB", downloadedBytes / 1_000_000.0)
                            withContext(Dispatchers.Main) {
                                onProgress(percent, dlMbStr, totalMbStr)
                            }
                        }
                    }
                    output.flush()
                }
            }

            // Validasi header ZIP/APK ('PK')
            if (tempFile.length() < 100_000L) {
                tempFile.delete()
                throw IllegalStateException("File APK hasil unduhan tidak lengkap.")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun showUpdateDialog(
        activity: AppCompatActivity,
        prefs: PrefsManager,
        update: AppReleaseUpdate,
        isManualCheck: Boolean,
        onNeedUnknownSourcesPermission: (File) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val summary = update.releaseNotes
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(5)
            .joinToString("\n")
            .ifBlank { "Versi terbaru dengan peningkatan stabilitas dan fitur baru telah tersedia." }

        val builder = AlertDialog.Builder(activity)
            .setTitle("Update Tersedia: v${update.versionName}")
            .setMessage(
                "Versi terbaru WMVAA AI Grader Pro (v${update.versionName}) siap dipasang langsung dari aplikasi.\n\n$summary"
            )
            .setPositiveButton("Update Sekarang") { _, _ ->
                startInAppDownloadAndInstall(activity, update, onNeedUnknownSourcesPermission)
            }
            .setNegativeButton("Tutup", null)

        if (!isManualCheck) {
            builder.setNeutralButton("Lewati Versi Ini") { _, _ ->
                prefs.setDismissedUpdateVersion(update.versionName)
            }
        }

        builder.show()
    }

    private fun startInAppDownloadAndInstall(
        activity: AppCompatActivity,
        update: AppReleaseUpdate,
        onNeedUnknownSourcesPermission: (File) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (18 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        val statusText = TextView(activity).apply {
            text = "Menyiapkan unduhan v${update.versionName}..."
            textSize = 13.5f
            setTextColor(activity.getColor(R.color.text_primary))
        }

        val progressIndicator = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            max = 100
            setIndicatorColor(activity.getColor(R.color.accent_cyan))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        val detailText = TextView(activity).apply {
            text = "Mohon tunggu, installer akan terbuka otomatis..."
            textSize = 11.5f
            gravity = Gravity.END
            setTextColor(activity.getColor(R.color.text_secondary))
        }

        container.addView(statusText)
        container.addView(progressIndicator)
        container.addView(detailText)

        var downloadJob: Job? = null
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Mengunduh Pembaruan")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Batal") { dialog, _ ->
                downloadJob?.cancel()
                dialog.dismiss()
            }
            .create()

        progressDialog.show()

        downloadJob = activity.lifecycleScope.launch {
            val result = downloadUpdateApk(
                context = activity,
                downloadUrl = update.downloadUrl,
                versionName = update.versionName
            ) { percent, downloadedMb, totalMb ->
                if (percent in 0..100) {
                    progressIndicator.isIndeterminate = false
                    progressIndicator.progress = percent
                    statusText.text = "Mengunduh APK v${update.versionName} ($percent%)"
                    detailText.text = "$downloadedMb / $totalMb"
                } else {
                    progressIndicator.isIndeterminate = true
                    statusText.text = "Mengunduh APK v${update.versionName}..."
                    detailText.text = downloadedMb
                }
            }

            if (progressDialog.isShowing) {
                progressDialog.dismiss()
            }

            result.onSuccess { apkFile ->
                installApkFile(activity, apkFile, onNeedUnknownSourcesPermission)
            }.onFailure { err ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(
                        activity,
                        "Gagal mengunduh update: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun installApkFile(
        activity: AppCompatActivity,
        apkFile: File,
        onNeedUnknownSourcesPermission: (File) -> Unit
    ) {
        if (!apkFile.exists()) {
            Toast.makeText(activity, "File pembaruan tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Izinkan Pembaruan Aplikasi")
                .setMessage(
                    "Untuk memasang pembaruan secara otomatis, aktifkan opsi \"Izinkan dari sumber ini\" untuk WMVAA AI Grader Pro pada layar berikutnya."
                )
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    onNeedUnknownSourcesPermission(apkFile)
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            @Suppress("DEPRECATION")
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = apkUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            activity.startActivity(installIntent)
        } catch (_: Exception) {
            try {
                val apkUri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    apkFile
                )
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Gagal membuka installer: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun cleanupOldUpdateApks(context: Context) {
        try {
            val updatesDir = File(context.cacheDir, "apk_updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseVersionParts(remoteVersion)
        val currentParts = parseVersionParts(currentVersion)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int> {
        return version.trim()
            .removePrefix("v")
            .substringBefore("-")
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
    }
}
