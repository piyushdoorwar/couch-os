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
    @Test fun wifiStrengthFillsContinuouslyAndHandlesMissingRssi() {
        assertNull(NetworkStatus.signalStrength(null))
        assertNull(NetworkStatus.signalStrength(-127))
        assertNull(NetworkStatus.signalStrength(0))
        assertEquals(0f, NetworkStatus.signalStrength(-110)!!, 0.001f)
        assertEquals(0.5f, NetworkStatus.signalStrength(-77)!!, 0.012f)
        assertEquals(1f, NetworkStatus.signalStrength(-55)!!, 0.001f)
        assertEquals(1f, NetworkStatus.signalStrength(-42)!!, 0.001f)
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
    @Test fun watchNextProgressIsAFractionOnlyWhenKnown() {
        assertEquals(0.25f, WatchNextSource.progress(15 * 60_000L, 60 * 60_000L)!!, 0.0001f)
        assertEquals(1f, WatchNextSource.progress(70L, 60L)!!, 0.0001f)
        assertNull(WatchNextSource.progress(0L, 60L))
        assertNull(WatchNextSource.progress(10L, 0L))
    }
    @Test fun mediaHistoryKeepsFiveNewestDistinctShows() {
        var list = emptyList<PlayedItem>()
        (1..7).forEach { i -> list = MediaHistory.merge(list, PlayedItem("app.one", "Show $i", null, i * 1000L, null)) }
        assertEquals(listOf("Show 7", "Show 6", "Show 5", "Show 4", "Show 3"), list.map { it.title })
        list = MediaHistory.merge(list, PlayedItem("app.one", "Show 4", "Episode 2", 9000L, null))
        assertEquals(listOf("Show 4", "Show 7", "Show 6", "Show 5", "Show 3"), list.map { it.title })
        assertEquals("Episode 2", list.first().subtitle)
        val roundTrip = MediaHistory.parse(MediaHistory.serialise(list))
        assertEquals(list, roundTrip)
        assertTrue(MediaHistory.parse("""[{"pkg":"bad name","title":"x"},{"pkg":"a.b","title":""}]""").isEmpty())
    }
    @Test fun homeLayoutPreservesAnExplicitEmptyFavouritesListAndPersonalSettings() {
        val item = PlayedItem("app.one", "Evening Horizon", null, 10, null)
        val layout = HomePreferences(favourites = emptyList(), hiddenApps = setOf("app.hidden"),
            inputsPinned = true, ambientPinned = true, evening = true, eveningHour = 21, onlineArtwork = true)
            .hideShow(showKey("app.one", "Hidden"))
            .markWatched(showKey("app.one", "Finished"), 5)
            .addPin(item)
        assertEquals(layout, HomePreferences.parse(JSONObject(layout.toJson().toString())))
        assertNull(HomePreferences.parse(JSONObject()).favourites)
        assertFalse(HomePreferences.parse(JSONObject()).onlineArtwork)
        assertFalse(layout.isEvening(20))
        assertTrue(layout.isEvening(21))
        assertTrue(layout.isEvening(0))
        assertTrue(layout.isEvening(5))
        assertFalse(layout.isEvening(6))
        assertFalse(layout.visibleShow(showKey("app.one", "HIDDEN"), 999))
        assertFalse(layout.visibleShow(showKey("app.one", "Finished"), 5))
        assertTrue(layout.visibleShow(showKey("app.one", "Finished"), 6))
    }
    @Test fun playbackEventUsesElapsedTimeWithoutTreatingAnOldSessionAsNew() {
        assertEquals(90_000L, MediaHistory.playbackTime(10_000, 20_000, 100_000))
        assertNull(MediaHistory.playbackTime(0, 20_000, 100_000))
        assertNull(MediaHistory.playbackTime(20_001, 20_000, 100_000))
        assertNull(MediaHistory.playbackTime(1, RecentApps.WINDOW_MS + 2, RecentApps.WINDOW_MS + 100))
    }

    @Test fun episodeIdentityAndProgressAreBackwardsCompatible() {
        val old = MediaHistory.parse("""[{"pkg":"app.one","title":"Evening Horizon","subtitle":"Episode 1","at":5}]""").single()
        assertNull(old.progress)
        assertNull(old.mediaId)
        assertTrue(MediaHistory.matchesPlayback(old, old.copy(title = "evening horizon")))
        assertFalse(MediaHistory.matchesPlayback(old, old.copy(subtitle = "Episode 2")))
        val newer = old.copy(progress = 0.5f, mediaId = "episode:1")
        assertEquals(listOf(newer), MediaHistory.parse(MediaHistory.serialise(listOf(newer))))
        assertTrue(MediaHistory.matchesPlayback(newer, newer.copy(subtitle = "New display name")))
        assertFalse(MediaHistory.matchesPlayback(newer, newer.copy(mediaId = "episode:2")))
    }
    @Test fun spokenLightCommandsUseTheConfiguredDeviceName() {
        val config = LocalConfig.parse(JSONObject("""{"spokenBacklight":"Hall Lamp"}"""))
        assertEquals("Hey, Google, Turn on the Hall Lamp", backlightCommand(config.spokenBacklight!!, on = true))
        assertEquals("Hey, Google, Turn off the Hall Lamp", backlightCommand(config.spokenBacklight, on = false))
        assertNull(LocalConfig.parse(JSONObject()).spokenBacklight)
    }

    @Test fun configHasNoImplicitLocationOrPersonalApps() {
        assertEquals(LocalConfig(), LocalConfig.parse(JSONObject("{}")))
        val parsed = LocalConfig.parse(JSONObject("""{"favourites":["example.one","example.one","bad name"],"latitude":91,"longitude":0}"""))
        assertEquals(listOf("example.one"), parsed.favourites)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
    }
}
