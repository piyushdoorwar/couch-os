package dev.couch.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LauncherTest {
    @Test fun allThemeAssetsMeetContrast() {
        val assets = File("src/main/assets/themes").listFiles()!!.filter { it.extension == "json" }
        assertEquals(4, assets.size)
        assets.forEach { ThemeAssets.validate(JSONObject(it.readText())) }
    }
    @Test fun rejectsLowContrastTheme() {
        val json = JSONObject(File("src/main/assets/themes/midnight-mint.json").readText())
        json.put("focus", json.getString("background"))
        assertThrows(IllegalArgumentException::class.java) { ThemeAssets.validate(json) }
    }
    @Test fun rejectsAccentBadgeWithUnreadableText() {
        val json = JSONObject(File("src/main/assets/themes/broadcast.json").readText())
        json.put("accent", "#3EE6D2")
        assertThrows(IllegalArgumentException::class.java) { ThemeAssets.validate(json) }
    }
    @Test fun weatherCacheExpiresAndDoesNotSurviveClockRollback() {
        assertTrue(WeatherCache.fresh(100L, 101L))
        assertFalse(WeatherCache.fresh(100L, 99L))
        assertFalse(WeatherCache.fresh(100L, 100L + WeatherCache.REFRESH_MS))
        assertEquals("26°C", WeatherCache.format(25.6))
        assertNull(WeatherCache.format(Double.NaN))
        assertNull(WeatherCache.format(999.0))
    }
    @Test fun liveSpeedFormatsMegabitsAndRejectsNonsense() {
        assertEquals(80.0, LiveSpeed.mbps(10_000_000, 1_000_000_000)!!, 0.001)
        assertEquals("80 Mbps", LiveSpeed.format(80.2))
        assertEquals("8.4 Mbps", LiveSpeed.format(8.4))
        assertEquals("0.0 Mbps", LiveSpeed.format(0.0))
        assertNull(LiveSpeed.mbps(-1, 1))
        assertNull(LiveSpeed.mbps(10, 0))
        assertNull(LiveSpeed.format(Double.NaN))
    }
    @Test fun jumpBackInKeepsNewestInstalledAppsWithinTwoWeeks() {
        val now = 100L * 24 * 60 * 60 * 1000
        val times = mapOf("a.one" to now - 1_000, "b.two" to now - 60_000, "gone.app" to now, "old.app" to now - RecentApps.WINDOW_MS - 1,
            "future.app" to now + 5_000)
        val installed = setOf("a.one", "b.two", "old.app", "future.app")
        assertEquals(listOf("a.one", "b.two"), RecentApps.newestFirst(times, installed, now, 5).map { it.first })
        assertEquals(listOf("a.one"), RecentApps.newestFirst(times, installed, now, 1).map { it.first })
        assertEquals("Opened just now", RecentApps.ago(now - 30_000, now))
        assertEquals("Opened 5 min ago", RecentApps.ago(now - 5 * 60_000, now))
        assertEquals("Opened 3 h ago", RecentApps.ago(now - 3 * 3_600_000, now))
        assertEquals("Opened yesterday", RecentApps.ago(now - 30 * 3_600_000L, now))
        assertEquals("Opened 4 days ago", RecentApps.ago(now - 4 * 86_400_000L, now))
    }
    @Test fun configHasNoImplicitLocationOrPersonalApps() {
        assertEquals(LocalConfig(), LocalConfig.parse(JSONObject("{}")))
        val parsed = LocalConfig.parse(JSONObject("""{"favourites":["example.one","example.one","bad name"],"latitude":91,"longitude":0}"""))
        assertEquals(listOf("example.one"), parsed.favourites)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
    }
}
