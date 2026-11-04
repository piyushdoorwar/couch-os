package dev.couch.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run on a clean TV emulator; drives the same D-pad events as a physical remote. */
class RemoteNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val remote get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var previousTheme: String? = null

    @Before fun saveTheme() {
        previousTheme = rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null)
        rule.waitForIdle()
    }
    @After fun restoreTheme() {
        val editor = rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
        if (previousTheme == null) editor.remove("theme") else editor.putString("theme", previousTheme)
        editor.commit()
    }
    private fun idle() = rule.waitForIdle()

    private fun button(label: String) = rule.onNodeWithContentDescription(label)

    private fun focused(label: String) = runCatching { button(label).assertIsFocused() }.isSuccess

    /**
     * Top bar, left to right: profile, (Home label), Screensaver, Quick settings. Up from the grid reaches it
     * directly, or via the "Continue watching" card, which hands focus to the nearest bar button.
     */
    private fun openSettings() {
        repeat(3) { if (!focused("Google account") && !focused("Quick settings")) { remote.pressDPadUp(); idle() } }
        if (focused("Google account")) {
            remote.pressDPadRight(); idle()
            button("Screensaver").assertIsFocused()
            remote.pressDPadRight(); idle()
        }
        button("Quick settings").assertIsFocused()
        remote.pressDPadCenter(); idle()
    }

    @Test fun topBarAndBackWorkWithOnlyRemoteKeys() {
        rule.onNode(isFocused()).assertExists()
        openSettings()
        rule.onNodeWithText("Inputs").assertIsFocused()
        remote.pressBack(); idle()
        button("Quick settings").assertExists()
        rule.onNode(isFocused()).assertExists()
    }

    @Test fun remoteThemeChoicePersistsAndHomeIntentClosesSettings() {
        openSettings()
        // Inputs, Network, Google account, All TV settings, App artwork, Continue watching, then themes by file name.
        repeat(6) { remote.pressDPadDown() }
        idle()
        rule.onNodeWithText("Broadcast").assertIsFocused()
        remote.pressDPadCenter(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activityRule.scenario.recreate(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activity.startActivity(Intent(rule.activity, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        idle()
        button("Quick settings").assertExists()
    }
    @Test fun shortcutsAndEveningScheduleCanBeConfiguredAndPersist() {
        val store = HomePreferenceStore(rule.activity)
        val before = store.load()
        try {
            store.save(HomePreferences())
            rule.activityRule.scenario.recreate(); idle()
            openSettings()
            fun choose(label: String) {
                rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                rule.onNodeWithText(label).performClick()
                idle()
            }
            choose("Download show artwork")
            choose("Pin Inputs to Home")
            choose("Pin Google Photos Ambient to Home")
            choose("Quiet evening layout")
            choose("Evening starts (OK to change)")
            rule.activityRule.scenario.recreate(); idle()
            val layout = store.load()
            assertEquals(true, layout.onlineArtwork)
            assertEquals(true, layout.inputsPinned)
            assertEquals(true, layout.ambientPinned)
            assertEquals(true, layout.evening)
            assertEquals(21, layout.eveningHour)

            remote.pressBack(); idle()
            val grid = rule.onNode(hasScrollAction())
            grid.performScrollToNode(hasContentDescription("Inputs"))
            button("Inputs").assertExists()
            grid.performScrollToNode(hasContentDescription("Google Photos Ambient"))
            button("Google Photos Ambient").assertExists()
        } finally {
            store.save(before)
        }
    }

}
