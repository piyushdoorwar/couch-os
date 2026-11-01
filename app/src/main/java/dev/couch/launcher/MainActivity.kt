package dev.couch.launcher

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val model: LauncherModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { LauncherScreen(model, SystemActions(this)) }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) model.showHome()
    }
    override fun onResume() {
        super.onResume()
        // Refresh when returning from an app or system settings. No package inventory telemetry.
        model.refresh()
    }
    private fun handlesVoiceKey(keyCode: Int) = model.config.value.voiceHelper &&
        (keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handlesVoiceKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Opt in only after physical checks. No RECORD_AUDIO permission or hotword loop.
        if (handlesVoiceKey(keyCode)) {
            SystemActions(this).assist()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
