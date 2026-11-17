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

data class DiscoveredServer(
    val url: String,
    val hostname: String,
    val port: String
)

object LanServerDiscovery {

    /**
     * Menguji apakah URL target merespons endpoint /api/system/network milik WMVAA AI Grader Pro.
     */
    suspend fun testServer(baseUrl: String, timeoutMs: Int = 6000): Result<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            try {
                val normalized = PrefsManager.normalizeBaseUrl(baseUrl)
                val target = URL("$normalized/api/system/network")
                val conn = (target.openConnection() as HttpURLConnection).apply {
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
                    conn.disconnect()
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    val hostname = data?.optString("hostname", "Server WMVAA") ?: "Server WMVAA"
                    val port = data?.optString("port", "443") ?: "443"
                    Result.success(DiscoveredServer(normalized, hostname, port))
                } else {
                    conn.disconnect()
                    Result.failure(IllegalStateException("HTTP $code dari $normalized"))
                }
            } catch (e: Exception) {
                Result.failure(e)
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
                        candidates.add("http://$prefix.$host:$port/ai-grader")
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
                        if (hostAddr.startsWith("192.168.") ||
                            hostAddr.startsWith("10.") ||
                            hostAddr.startsWith("172.")
                        ) {
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
