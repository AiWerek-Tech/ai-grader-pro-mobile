package com.wmvaa.aigrader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

class NetworkMonitor(
    context: Context,
    private val onStatusChanged: (isConnected: Boolean, isWifi: Boolean) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyCurrentState()
        }

        override fun onLost(network: Network) {
            mainHandler.postDelayed({ notifyCurrentState() }, 250)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            notifyCurrentState()
        }
    }

    fun start() {
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            registered = true
        } catch (_: Exception) {
        }
        notifyCurrentState()
    }

    fun stop() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        registered = false
    }

    /**
     * Mengembalikan Pair(hasAnyConnection, hasWifiOrEthernet).
     * Memeriksa activeNetwork sekaligus fallback ke allNetworks agar jaringan Wi-Fi LAN sekolah
     * yang tidak memiliki akses internet publik tetap terdeteksi aktif di Android 10–14.
     */
    fun currentState(): Pair<Boolean, Boolean> {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps != null) {
            val hasTransport =
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isWifi = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (hasTransport) {
                return true to isWifi
            }
        }

        // Fallback untuk router Wi-Fi lokal sekolah tanpa internet (offline LAN server)
        return try {
            @Suppress("DEPRECATION")
            val networks = connectivityManager.allNetworks
            var anyTransport = false
            var anyWifi = false
            for (net in networks) {
                val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    anyTransport = true
                    anyWifi = true
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    anyTransport = true
                }
            }
            anyTransport to anyWifi
        } catch (_: Exception) {
            false to false
        }
    }

    private fun notifyCurrentState() {
        val (connected, wifi) = currentState()
        mainHandler.post {
            onStatusChanged(connected, wifi)
        }
    }
}
