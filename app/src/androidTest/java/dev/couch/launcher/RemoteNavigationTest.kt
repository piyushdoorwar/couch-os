package dev.couch.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
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

    /** Top icon buttons, left to right. */
    private fun openSettings() {
        remote.pressDPadUp(); idle()
        button("Inputs").assertIsFocused()
        repeat(3) { remote.pressDPadRight() }
        idle()
        button("Settings").assertIsFocused()
        remote.pressDPadCenter(); idle()
    }

    @Test fun homeGridAndBackWorkWithOnlyRemoteKeys() {
        button("All apps").assertIsFocused()
        remote.pressDPadCenter(); idle()
        // The grid has no on-screen Back once apps are listed; the remote's Back key returns home.
        button("All apps").assertDoesNotExist()
        remote.pressBack(); idle()
        button("All apps").assertIsFocused()
        remote.pressDPadUp(); idle()
        button("Inputs").assertIsFocused()
        remote.pressDPadRight(); idle()
        button("Wi-Fi").assertIsFocused()
        remote.pressDPadRight(); idle()
        button("Google account").assertIsFocused()
        remote.pressDPadRight(); idle()
        button("Settings").assertIsFocused()
    }

    @Test fun remoteThemeChoicePersistsAndHomeIntentClosesSettings() {
        openSettings()
        rule.onNodeWithText("THEME").assertIsDisplayed()
        // Assets are sorted by filename; Broadcast is the first selectable theme.
        remote.pressDPadCenter(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activityRule.scenario.recreate(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activity.startActivity(Intent(rule.activity, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        idle()
        button("All apps").assertIsFocused()
    }
}
