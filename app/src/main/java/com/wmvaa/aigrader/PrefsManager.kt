package com.wmvaa.aigrader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasConfiguredServer(): Boolean {
        return !prefs.getString(KEY_SERVER_URL, null).isNullOrBlank()
    }

    fun getServerUrl(): String {
        val raw = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        return normalizeBaseUrl(raw)
    }

    fun needsCacheFlushOnUpgrade(currentVersionCode: Int): Boolean {
        val lastCode = prefs.getInt(KEY_LAST_APP_VERSION, -1)
        if (lastCode != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_APP_VERSION, currentVersionCode).apply()
            return true
        }
        return false
    }

    fun getStartPath(): String {
        return prefs.getString(KEY_START_PATH, "/dashboard/analisis") ?: "/dashboard/analisis"
    }

    fun setServerUrl(rawInput: String, startPath: String? = null) {
        val normalized = normalizeBaseUrl(rawInput)
        prefs.edit().apply {
            putString(KEY_SERVER_URL, normalized)
            if (!startPath.isNullOrBlank()) {
                putString(KEY_START_PATH, startPath)
            }
            apply()
        }
        addToHistory(normalized)
    }

    fun isKeepScreenOn(): Boolean {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun getDismissedUpdateVersion(): String {
        return prefs.getString(KEY_DISMISSED_UPDATE_VERSION, "") ?: ""
    }

    fun setDismissedUpdateVersion(version: String) {
        prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, version).apply()
    }

    fun isTrustedLanCert(host: String, sha256Fingerprint: String): Boolean {
        if (host.isBlank() || sha256Fingerprint.isBlank()) return false
        val raw = prefs.getString(KEY_TRUSTED_LAN_CERTS, "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            obj.optString(host.lowercase(), "").equals(sha256Fingerprint, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun addTrustedLanCert(host: String, sha256Fingerprint: String) {
        if (host.isBlank() || sha256Fingerprint.isBlank()) return
        val raw = prefs.getString(KEY_TRUSTED_LAN_CERTS, "{}") ?: "{}"
        try {
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject()
            }
            obj.put(host.lowercase(), sha256Fingerprint.uppercase())
            prefs.edit().putString(KEY_TRUSTED_LAN_CERTS, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun getHistory(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearHistory() {
        val current = getServerUrl()
        val arr = JSONArray()
        if (current.isNotBlank()) arr.put(current)
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun addToHistory(url: String) {
        val current = getHistory().toMutableList()
        current.removeAll { it.equals(url, ignoreCase = true) }
        current.add(0, url)
        val trimmed = current.take(5)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "wmvaa_grader_mobile_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_START_PATH = "start_path"
        private const val KEY_HISTORY = "server_history"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LAST_APP_VERSION = "last_app_version_code"
        private const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
        private const val KEY_TRUSTED_LAN_CERTS = "trusted_lan_certs"
        const val DEFAULT_URL = "https://app.wmvaa.id/ai-grader"

        private val APP_ROUTE_MARKERS = listOf("/dashboard", "/login", "/print", "/api", "/offline")

        private fun ensureScheme(raw: String): String {
            var cleaned = raw.trim()
            if (cleaned.isEmpty()) return DEFAULT_URL
            if (cleaned.startsWith("wmvaa://", ignoreCase = true)) {
                val remainder = cleaned.substringAfter("://").removePrefix("grader").trimStart('/')
                return if (remainder.isEmpty()) DEFAULT_URL else "$DEFAULT_URL/$remainder"
            }
            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true)
            ) {
                val hostPart = cleaned.substringBefore('/').substringBefore(':')
                cleaned = if (isPrivateLanIp(hostPart)) {
                    "http://$cleaned"
                } else {
                    "https://$cleaned"
                }
            }
            return cleaned
        }

        private fun splitBaseAndAppPath(rawPathInput: String?): Pair<String, String?> {
            val rawPath = rawPathInput?.trimEnd('/') ?: ""
            if (rawPath.isEmpty() || rawPath == "/") {
                return "" to null
            }

            for (marker in APP_ROUTE_MARKERS) {
                val idx = rawPath.indexOf(marker)
                if (idx >= 0) {
                    val base = rawPath.substring(0, idx).trimEnd('/')
                    val appRoute = rawPath.substring(idx)
                    return base to appRoute
                }
            }

            // Jika path tidak mengandung /dashboard, /login, dll -> anggap sebagai subfolder basePath (misal /ai-grader)
            return rawPath to null
        }

        fun normalizeBaseUrl(raw: String): String {
            val cleaned = ensureScheme(raw)
            val parsed = try {
                URI(cleaned)
            } catch (_: Exception) {
                return cleaned.trimEnd('/')
            }
            val host = parsed.host?.lowercase() ?: return cleaned.trimEnd('/')
            var scheme = parsed.scheme?.lowercase() ?: "https"
            if (!isPrivateLanIp(host)) {
                scheme = "https"
            }

            val explicitPort = parsed.port
            val port = when {
                explicitPort == 80 && scheme == "http" -> -1
                explicitPort == 443 && scheme == "https" -> -1
                explicitPort != -1 -> explicitPort
                scheme == "http" && isPrivateLanIp(host) -> 3000
                else -> -1
            }

            val (basePath, _) = splitBaseAndAppPath(parsed.rawPath)
            val origin = if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
            return "$origin$basePath"
        }

        fun extractPathOrNull(raw: String): String? {
            val cleaned = ensureScheme(raw)
            val parsed = try {
                URI(cleaned)
            } catch (_: Exception) {
                return null
            }
            val (_, appRoute) = splitBaseAndAppPath(parsed.rawPath)
            if (appRoute == null) return null
            val query = parsed.rawQuery
            return if (!query.isNullOrBlank()) "$appRoute?$query" else appRoute
        }

        fun isPrivateLanIp(hostInput: String): Boolean {
            val host = hostInput.trim().lowercase()
            if (host.isEmpty()) return false
            if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")) {
                return true
            }
            val parts = host.split(".")
            if (parts.size == 4) {
                val octets = parts.map { it.toIntOrNull() ?: return false }
                if (octets.any { it !in 0..255 }) return false
                val o1 = octets[0]
                val o2 = octets[1]
                return o1 == 10 ||
                    o1 == 127 ||
                    (o1 == 192 && o2 == 168) ||
                    (o1 == 172 && o2 in 16..31)
            }
            return false
        }
    }
}
