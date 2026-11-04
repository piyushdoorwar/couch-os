package dev.couch.launcher

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.media.MediaMetadata
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Emulator only. Plays synthetic media in a separate package and returns through the HOME intent. */
class ContinueWatchingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = UiDevice.getInstance(instrumentation)
    private val samplePackage = instrumentation.context.packageName
    private val player = "$samplePackage/dev.couch.launcher.fixture.SamplePlayerActivity"
    private val home = "dev.couch.launcher/.MainActivity"
    private val listener = "dev.couch.launcher/.MediaListener"

    @Test fun playbackCreatesTitleArtworkAndProgressAndCanResume() {
        assertEquals("1", shell("getprop ro.kernel.qemu").trim())
        val hadAccess = MediaHistory.enabled(rule.activity)
        MediaHistory.clear(rule.activity)
        try {
            shell("cmd notification allow_listener $listener")
            shell("am force-stop $samplePackage")
            val result = shell("am start -W -n $player --es title Evening_Horizon")
            assertTrue(result, remote.wait(Until.hasObject(By.text("Playing Evening Horizon")), 10_000))
            rule.waitUntil(10_000) { MediaHistory.load(instrumentation.targetContext).any { it.title == "Evening Horizon" } }
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $home")
            val label = "Continue watching: Evening Horizon"
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Sample Player  •  Season 1, Episode 2").assertExists()
            rule.onNode(hasContentDescription("Artwork for Evening Horizon"), useUnmergedTree = true).assertExists()
            rule.waitUntil(10_000) { MediaHistory.load(instrumentation.targetContext).firstOrNull()?.progress == 0.5f }
            rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)),
                useUnmergedTree = true).assertExists()

            rule.waitUntil(5_000) { rule.onAllNodesWithText("Resume in Sample Player").fetchSemanticsNodes().isNotEmpty() }
            remote.pressDPadUp()
            rule.onNodeWithContentDescription(label).assertIsFocused()
            remote.pressDPadCenter()
            assertTrue(remote.wait(Until.hasObject(By.text("Playing Evening Horizon")), 5_000))
        } finally {
            shell("am force-stop $samplePackage")
            if (!hadAccess) shell("cmd notification disallow_listener $listener")
            MediaHistory.clear(instrumentation.targetContext)
        }
    }

    @Test fun titlelessPlaybackRecordsAppTimeWithoutInventingATitle() {
        assertEquals("1", shell("getprop ro.kernel.qemu").trim())
        val context = instrumentation.targetContext
        val hadAccess = MediaHistory.enabled(context)
        MediaHistory.clear(context)
        try {
            shell("cmd notification allow_listener $listener")
            shell("am force-stop $samplePackage")
            shell("am start -W -n $player")
            rule.waitUntil(10_000) { MediaHistory.untitledPlayback(context).containsKey(samplePackage) }
            assertFalse(MediaHistory.load(context).any { it.packageName == samplePackage })
        } finally {
            shell("am force-stop $samplePackage")
            if (!hadAccess) shell("cmd notification disallow_listener $listener")
            MediaHistory.clear(context)
        }
    }

    @Test fun titlelessSessionsStayOutOfWatchHistory() {
        val genericDisplay = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "SamplePlayer")
            .build()
        assertNotNull(genericDisplay.description.title)
        assertNull(MediaHistory.describe("sample.stream", genericDisplay, 5))
        assertNull(MediaHistory.describe("sample.stream", null, 5))
    }

    @Test fun titleCardsMergeSourcesAcrossAppsAndRespectPinsAndHiddenTitles() {
        fun app(packageName: String, label: String) = TvApp(packageName, label, ComponentName(packageName, "Main"), ActivityInfo())
        val apps = listOf(app("sample.stream", "Sample Stream"), app("other.stream", "Other Stream"))
        val older = PlayedItem("other.stream", "First Light", "Episode 1", 10, null)
        val newer = PlayedItem("sample.stream", "Evening Horizon", "Episode 2", 40, null, 0.5f)
        val titleless = PlayedItem("other.stream", "Other Stream", null, 100, null)
        val duplicate = WatchNextItem("sample.stream", "Evening horizon", "Episode 1", "content://poster",
            "content://old", 0.9f, 30)
        val pin = PlayedItem("sample.stream", "Saved for later", null, 90, null)
        val layout = HomePreferences().addPin(pin).markWatched(older.key, 20)
        val result = watchCards(apps, listOf(older, newer, titleless), listOf(duplicate), layout)
        assertEquals(listOf("Evening horizon", "Saved for later"), result.map { it.title })
        assertEquals("Episode 2", result.first().episode)
        assertEquals(0.5f, result.first().progress!!, 0.001f)
        assertNull(result.first().linkedShow)
        assertEquals(pin, result.last().pin)
        val newerProvider = duplicate.copy(lastEngaged = 45, progress = null)
        val providerCard = watchCards(apps, listOf(newer), listOf(newerProvider), HomePreferences()).single()
        assertEquals("Episode 1", providerCard.episode)
        assertNull(providerCard.progress)
        assertNull(providerCard.livePlayback)
        assertEquals(newerProvider, providerCard.linkedShow)
        assertEquals(listOf("First Light", "Saved for later"),
            watchCards(apps, listOf(older.copy(at = 50), newer), listOf(duplicate), layout.hideShow(newer.key))
                .map { it.title })
    }

    @Test fun untitledPlaybackPromotesOnlyTheLatestPublishedCardFromThatApp() {
        fun app(pkg: String) = TvApp(pkg, pkg, ComponentName(pkg, "Main"), ActivityInfo())
        val apps = listOf(app("sample.stream"), app("other.stream"))
        val stale = WatchNextItem("sample.stream", "Evening Horizon", null, null, "content://current", null, 10)
        val older = stale.copy(title = "First Light", lastEngaged = 5)
        val recent = stale.copy(packageName = "other.stream", title = "Late Night", lastEngaged = 20)
        val playback = mapOf("sample.stream" to 30L)
        val shows = listOf(recent, stale, older)
        val cards = watchCards(apps, emptyList(), shows, HomePreferences(), playback)
        assertEquals(listOf("Evening Horizon", "Late Night", "First Light"), cards.map { it.title })
        assertEquals(stale, cards.first().linkedShow)
        val newerTitle = PlayedItem("sample.stream", "A Real Title", null, 40, null)
        assertEquals(listOf("A Real Title", "Late Night", "Evening Horizon", "First Light"),
            watchCards(apps, listOf(newerTitle), shows, HomePreferences(), playback).map { it.title })
        val watched = HomePreferences().markWatched(showKey(stale.packageName, stale.title), 15)
        assertEquals(listOf("Late Night", "First Light"),
            watchCards(apps, emptyList(), shows, watched, playback).map { it.title })
    }

    @Test fun remoteCanPinAppAndManualShowAndHideItsCard() {
        assertEquals("1", shell("getprop ro.kernel.qemu").trim())
        val context = instrumentation.targetContext
        val store = HomePreferenceStore(context)
        val before = store.load()
        val layout = HomePreferences(favourites = emptyList())
        try {
            store.save(layout)
            rule.activityRule.scenario.recreate()
            val app = AppCatalog(context).load().first()
            val tile = rule.onNodeWithContentDescription(app.label)
            tile.assertIsFocused()
            holdOk()
            rule.onNodeWithText("Pin to favourites").assertIsFocused()
            remote.pressDPadCenter()
            rule.waitForIdle()
            assertEquals(listOf(app.packageName), store.load().favourites)

            tile.assertIsFocused()
            holdOk()
            rule.onNodeWithText("Pin a show…").performClick()
            rule.onNodeWithContentDescription("Show title").performTextInput("Saved Horizon")
            rule.onNodeWithText("Save to carousel").performClick()
            rule.waitForIdle()
            assertEquals("Saved Horizon", store.load().pinnedShows.single().title)
            val card = rule.onNodeWithContentDescription("Continue watching: Saved Horizon")
            card.assertExists()

            tile.assertIsFocused()
            // This keyboard can still be closing. The app tile above already tests the remote hold.
            card.performSemanticsAction(SemanticsActions.OnLongClick)
            rule.onNodeWithText("Hide from carousel").performClick()
            card.assertDoesNotExist()
            assertTrue(store.load().hiddenShows.contains(showKey(app.packageName, "Saved Horizon")))
        } finally {
            store.save(before)
        }
    }

    private fun holdOk() {
        val down = SystemClock.uptimeMillis()
        val automation = instrumentation.uiAutomation
        fun send(action: Int) {
            assertTrue(automation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action,
                KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, 0, 0, 0, InputDevice.SOURCE_DPAD), true))
        }
        send(KeyEvent.ACTION_DOWN)
        SystemClock.sleep(750)
        send(KeyEvent.ACTION_UP)
        rule.waitForIdle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
