package dev.couch.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LauncherTest {
    @Test fun allThemeAssetsMeetContrast() {
        val assets = File("src/main/assets/themes").listFiles()!!.filter { it.extension == "json" }
        assertEquals(3, assets.size)
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
    @Test fun speedFormatsMegabitsAndRejectsNonsense() {
        assertEquals(80.0, SpeedTest.mbps(10_000_000, 1_000_000_000)!!, 0.001)
        assertEquals("80 Mbps", SpeedTest.format(80.2))
        assertEquals("8.4 Mbps", SpeedTest.format(8.4))
        assertNull(SpeedTest.mbps(0, 1))
        assertNull(SpeedTest.format(Double.NaN))
        assertNull(SpeedTest.format(0.0))
    }
    @Test fun configHasNoImplicitLocationOrPersonalApps() {
        assertEquals(LocalConfig(), LocalConfig.parse(JSONObject("{}")))
        val parsed = LocalConfig.parse(JSONObject("""{"favourites":["example.one","example.one","bad name"],"latitude":91,"longitude":0}"""))
        assertEquals(listOf("example.one"), parsed.favourites)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
    }
}
