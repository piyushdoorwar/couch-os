package dev.couch.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

enum class Link { WIFI, ETHERNET, OFFLINE }

/** What the top-right status shows. [name] is the network name, or null when Android withholds it. */
data class NetworkLabel(val link: Link, val name: String?, val strength: Float? = null)

class NetworkStatus(private val context: Context) {
    /**
     * Android only reveals the Wi-Fi name to apps holding location permission (granted over adb by
     * configure.sh when the owner opts in). Signal strength uses the existing Wi-Fi state access.
     */
    @Suppress("DEPRECATION")
    fun current(): NetworkLabel {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return NetworkLabel(Link.OFFLINE, null)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkLabel(Link.ETHERNET, "Ethernet")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val info = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
                NetworkLabel(Link.WIFI, ssidName(info?.ssid), signalStrength(info?.rssi))
            }
            else -> NetworkLabel(Link.OFFLINE, null)
        }
    }

    companion object {
        // Use the Android legacy signal scale continuously, so the icon can fill between bars.
        fun signalStrength(rssi: Int?): Float? = rssi?.takeIf { it in -126..-1 }
            ?.let { ((it + 100) / 45f).coerceIn(0f, 1f) }

        fun ssidName(raw: String?): String? = raw?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.removeSurrounding("\"") && it != "<unknown ssid>" }
    }
}
