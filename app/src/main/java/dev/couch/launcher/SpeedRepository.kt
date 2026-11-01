package dev.couch.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object SpeedTest {
    const val REFRESH_MS = 30 * 60 * 1000L
    const val BYTES = 10_000_000L
    /** The first megabyte covers TCP ramp-up and is excluded from timing. */
    const val WARMUP_BYTES = 1_000_000L
    const val MAX_MS = 10_000L
    fun mbps(bytes: Long, nanos: Long): Double? =
        if (bytes <= 0 || nanos <= 0) null else bytes * 8.0 / (nanos / 1e9) / 1e6
    fun format(mbps: Double?): String? = mbps?.takeIf { it.isFinite() && it > 0 && it < 100_000 }?.let {
        if (it < 10) "%.1f Mbps".format(java.util.Locale.ENGLISH, it) else "${it.roundToInt()} Mbps"
    }
}

/** Opt-in download test. Runs only while HOME is visible, never on metered networks. */
class SpeedRepository(private val context: Context) {
    private val cache = context.getSharedPreferences("speed", Context.MODE_PRIVATE)
    fun cached(): String? = cache.getString("result", null)
    fun due(now: Long): Boolean {
        val at = cache.getLong("at", 0L)
        return cached() == null || now < at || now - at >= SpeedTest.REFRESH_MS
    }
    fun unmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
    suspend fun measure(): String? = withContext(Dispatchers.IO) {
        if (!unmetered()) return@withContext null
        runCatching {
            val connection = URL("https://speed.cloudflare.com/__down?bytes=${SpeedTest.BYTES}").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                if (connection.responseCode != 200) return@runCatching null
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var timedBytes = 0L
                var start = 0L
                val deadline = System.nanoTime() + SpeedTest.MAX_MS * 1_000_000
                connection.inputStream.use { input ->
                    while (System.nanoTime() < deadline) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (start == 0L && total >= SpeedTest.WARMUP_BYTES) start = System.nanoTime()
                        else if (start != 0L) timedBytes += read
                    }
                }
                val result = SpeedTest.format(SpeedTest.mbps(timedBytes, System.nanoTime() - start).takeIf { start != 0L })
                if (result != null) cache.edit().putString("result", result).putLong("at", System.currentTimeMillis()).apply()
                result
            } finally { connection.disconnect() }
        }.getOrNull()
    }
}
