package com.wmvaa.aigrader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class DiscoveredServer(
    val url: String,
    val hostname: String,
    val port: String
)

object LanServerDiscovery {

    private val lanTrustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    private val lanHostnameVerifier = HostnameVerifier { hostname, session ->
        PrefsManager.isPrivateLanIp(hostname) ||
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
    }

    /**
     * Menguji apakah URL target merespons endpoint /api/system/network milik WMVAA AI Grader Pro.
     * Otomatis mencoba fallback subfolder /ai-grader jika root mengembalikan 404.
     */
    suspend fun testServer(baseUrl: String, timeoutMs: Int = 6000): Result<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            val normalized = PrefsManager.normalizeBaseUrl(baseUrl)
            val primaryAttempt = probeEndpoint(normalized, timeoutMs)
            if (primaryAttempt.isSuccess) {
                return@withContext primaryAttempt
            }

            // Fallback otomatis: jika belum berakhiran /ai-grader, coba tambahkan /ai-grader
            if (!normalized.endsWith("/ai-grader", ignoreCase = true)) {
                val fallbackBase = "$normalized/ai-grader"
                val fallbackAttempt = probeEndpoint(fallbackBase, timeoutMs)
                if (fallbackAttempt.isSuccess) {
                    return@withContext fallbackAttempt
                }
            }

            primaryAttempt
        }

    private fun probeEndpoint(candidateBaseUrl: String, timeoutMs: Int): Result<DiscoveredServer> {
        var conn: HttpURLConnection? = null
        return try {
            val target = URL("$candidateBaseUrl/api/system/network")
            conn = (target.openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection && PrefsManager.isPrivateLanIp(target.host ?: "")) {
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, lanTrustAllCerts, SecureRandom())
                    sslSocketFactory = sslContext.socketFactory
                    hostnameVerifier = lanHostnameVerifier
                }
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WMVAA-Android-Wrapper/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                val hostname = data?.optString("hostname", "Server WMVAA") ?: "Server WMVAA"
                val port = data?.optString("port", "443") ?: "443"
                Result.success(DiscoveredServer(candidateBaseUrl, hostname, port))
            } else {
                Result.failure(IllegalStateException("HTTP $code dari $candidateBaseUrl"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Memindai seluruh subnet Wi-Fi /24 saat ini secara paralel untuk menemukan PC Server WMVAA (port 3000).
     */
    suspend fun discoverLanServers(
        knownUrls: List<String>,
        port: Int = 3000
    ): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf<String>()
        knownUrls.forEach { candidates.add(PrefsManager.normalizeBaseUrl(it)) }

        val deviceIps = getLocalWifiIpv4Addresses()
        for (ip in deviceIps) {
            val parts = ip.split(".")
            if (parts.size == 4) {
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                val selfLast = parts[3].toIntOrNull()
                for (host in 1..254) {
                    if (host != selfLast) {
                        candidates.add("http://$prefix.$host:$port")
                    }
                }
            }
        }

        val semaphore = Semaphore(48)
        val discovered = coroutineScope {
            candidates.map { candidateUrl ->
                async {
                    semaphore.withPermit {
                        testServer(candidateUrl, timeoutMs = 450).getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        discovered.distinctBy { it.url }
    }

    private fun getLocalWifiIpv4Addresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        if (PrefsManager.isPrivateLanIp(hostAddr)) {
                            ips.add(hostAddr)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ips
    }
}
