package dev.couch.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test fun homeGridAndBackWorkWithOnlyRemoteKeys() {
        rule.onNodeWithText("All apps").assertIsFocused()
        remote.pressDPadCenter(); idle()
        rule.onNodeWithText("Back").assertIsFocused()
        remote.pressBack(); idle()
        rule.onNodeWithText("All apps").assertIsFocused()
        remote.pressDPadUp(); idle()
        rule.onNodeWithText("Settings").assertIsFocused()
        remote.pressDPadRight(); idle()
        rule.onNodeWithText("Wi-Fi").assertIsFocused()
        remote.pressDPadRight(); idle()
        rule.onNodeWithText("Inputs").assertIsFocused()
    }

    @Test fun remoteThemeChoicePersistsAndHomeIntentClosesSettings() {
        remote.pressDPadUp(); remote.pressDPadCenter(); idle()
        rule.onNodeWithText("Make yourself at home").assertIsDisplayed()
        // Assets are sorted by filename; Broadcast is the first selectable theme.
        remote.pressDPadCenter(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activityRule.scenario.recreate(); idle()
        assertEquals("broadcast", rule.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null))
        rule.activity.startActivity(Intent(rule.activity, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        idle()
        rule.onNodeWithText("All apps").assertIsFocused()
    }
}
