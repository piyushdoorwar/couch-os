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
        model.refresh()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only opt in after logcat/physical checks. No RECORD_AUDIO permission or hotword loop.
        if (model.config.value.voiceHelper &&
            (event.keyCode == KeyEvent.KEYCODE_ASSIST || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) {
            if (event.action == KeyEvent.ACTION_UP) SystemActions(this).assist()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
