package dev.couch.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

enum class Link { WIFI, ETHERNET, OFFLINE }

/** What the top-right status shows. [name] is the network name, or null when Android withholds it. */
data class NetworkLabel(val link: Link, val name: String?)

class NetworkStatus(private val context: Context) {
    /**
     * Android only reveals the Wi-Fi name to apps holding location permission (granted over adb by
     * configure.sh when the owner opts in). Without it this falls back to a plain Wi-Fi icon.
     */
    @Suppress("DEPRECATION")
    fun current(): NetworkLabel {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return NetworkLabel(Link.OFFLINE, null)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkLabel(Link.ETHERNET, "Ethernet")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo?.ssid }.getOrNull()
                NetworkLabel(Link.WIFI, ssidName(ssid))
            }
            else -> NetworkLabel(Link.OFFLINE, null)
        }
    }

    companion object {
        fun ssidName(raw: String?): String? = raw?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.removeSurrounding("\"") && it != "<unknown ssid>" }
    }
}
