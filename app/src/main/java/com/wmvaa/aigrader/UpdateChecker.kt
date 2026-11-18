package com.wmvaa.aigrader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                    connectTimeout = 5000
                    readTimeout = 5000
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
