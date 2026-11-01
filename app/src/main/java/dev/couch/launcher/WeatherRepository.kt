package dev.couch.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object WeatherCache {
    const val REFRESH_MS = 30 * 60 * 1000L
    fun fresh(timestamp: Long, now: Long) = now >= timestamp && now - timestamp < REFRESH_MS
    fun format(value: Double): String? = value.takeIf { it.isFinite() && it in -100.0..70.0 }?.let { "${it.roundToInt()}°C" }
}

class WeatherRepository(private val context: Context) {
    private val cache = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }
    suspend fun temperature(config: LocalConfig): String? = withContext(Dispatchers.IO) {
        if (config.latitude == null || config.longitude == null || !online()) return@withContext null
        val coordinateKey = "${config.latitude},${config.longitude}"
        val now = System.currentTimeMillis()
        if (cache.getString("coordinates", "") == coordinateKey && WeatherCache.fresh(cache.getLong("at", 0L), now)) {
            return@withContext cache.getString("temperature", null)
        }
        // No URL/coordinates in logs. HTTPS is the only network endpoint in the app.
        val uri = Uri.parse("https://api.open-meteo.com/v1/forecast").buildUpon()
            .appendQueryParameter("latitude", config.latitude.toString())
            .appendQueryParameter("longitude", config.longitude.toString())
            .appendQueryParameter("current", "temperature_2m")
            .appendQueryParameter("temperature_unit", "celsius").build()
        runCatching {
            val connection = URL(uri.toString()).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                if (connection.responseCode != 200) return@runCatching null
                val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val temp = WeatherCache.format(json.getJSONObject("current").getDouble("temperature_2m"))
                if (temp != null) cache.edit().putString("coordinates", coordinateKey).putLong("at", now)
                    .putString("temperature", temp).apply()
                temp
            } finally { connection.disconnect() }
        }.getOrNull()
    }
}
