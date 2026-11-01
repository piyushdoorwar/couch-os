package dev.couch.launcher

import android.net.TrafficStats
import java.util.Locale
import kotlin.math.roundToInt

object LiveSpeed {
    const val SAMPLE_MS = 2_000L
    fun mbps(bytes: Long, nanos: Long): Double? =
        if (bytes < 0 || nanos <= 0) null else bytes * 8.0 / (nanos / 1e9) / 1e6
    fun format(mbps: Double?): String? = mbps?.takeIf { it.isFinite() && it >= 0 && it < 100_000 }?.let {
        if (it < 10) "%.1f Mbps".format(Locale.ENGLISH, it) else "${it.roundToInt()} Mbps"
    }
}

/** Device-wide download rate from kernel counters. No network traffic of its own, no permission. */
class SpeedRepository {
    private var lastBytes = -1L
    private var lastAt = 0L
    fun reset() { lastBytes = -1L }
    /** Null on the first sample or when the firmware does not expose counters. */
    fun sample(): String? {
        val bytes = TrafficStats.getTotalRxBytes()
        val now = System.nanoTime()
        if (bytes == TrafficStats.UNSUPPORTED.toLong()) return null
        val result = if (lastBytes < 0) null else LiveSpeed.format(LiveSpeed.mbps(bytes - lastBytes, now - lastAt))
        lastBytes = bytes
        lastAt = now
        return result
    }
}
