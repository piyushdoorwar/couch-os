package dev.couch.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Recently used TV apps for the "Jump back in" carousel. Stays on the TV; nothing is uploaded. */
class RecentApps(private val context: Context) {
    // Google TV's own Continue watching is private to Google services; third-party homes cannot read it.
    private val launches = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun recordLaunch(packageName: String) {
        launches.edit().putLong(packageName, System.currentTimeMillis()).apply()
    }

    /** Usage access is opt-in: configure.sh grants it over adb when recent.usage_access=true. */
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Package to last-used time, newest first, limited to [installed] TV apps. Call off the main thread. */
    fun recent(installed: Set<String>, now: Long = System.currentTimeMillis(), limit: Int = 5): List<Pair<String, Long>> {
        val times = mutableMapOf<String, Long>()
        launches.all.forEach { (pkg, at) -> if (at is Long) times[pkg] = at }
        if (hasUsageAccess()) {
            val usage = context.getSystemService(UsageStatsManager::class.java)
            runCatching { usage.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WINDOW_MS, now) }.getOrNull().orEmpty()
                .filter { it.totalTimeInForeground > 0 && it.lastTimeUsed > 0 }
                .forEach { times.merge(it.packageName, it.lastTimeUsed, ::maxOf) }
        }
        return newestFirst(times, installed - context.packageName, now, limit)
    }

    companion object {
        const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        fun newestFirst(times: Map<String, Long>, installed: Set<String>, now: Long, limit: Int): List<Pair<String, Long>> =
            times.filter { (pkg, at) -> pkg in installed && at in (now - WINDOW_MS)..now }
                .toList().sortedByDescending { it.second }.take(limit)

        fun ago(at: Long, now: Long): String {
            val minutes = ((now - at) / 60_000).coerceAtLeast(0)
            return when {
                minutes < 2 -> "Opened just now"
                minutes < 60 -> "Opened $minutes min ago"
                minutes < 24 * 60 -> "Opened ${minutes / 60} h ago"
                minutes < 48 * 60 -> "Opened yesterday"
                else -> "Opened ${minutes / (24 * 60)} days ago"
            }
        }
    }
}
