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
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    fun currentState(): Pair<Boolean, Boolean> {
        val activeNetwork = connectivityManager.activeNetwork ?: return false to false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false to false
        val hasTransport =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return hasTransport to isWifi
    }

    private fun notifyCurrentState() {
        val (connected, wifi) = currentState()
        mainHandler.post {
            onStatusChanged(connected, wifi)
        }
    }
}
