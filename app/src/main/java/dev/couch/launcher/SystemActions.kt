package dev.couch.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

class SystemActions(private val activity: Activity) {
    // Try starting instead of resolveActivity: OEM settings are not always package-visible.
    fun open(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Continue to the next documented fallback.
            } catch (_: SecurityException) {
                // A vendor may expose a non-exported activity; keep HOME usable.
            }
        }
        Toast.makeText(activity, "This action is unavailable on this TV", Toast.LENGTH_LONG).show()
        return false
    }
    fun settings() = open(Intent(Settings.ACTION_SETTINGS))
    fun wifi() = open(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun inputs(config: LocalConfig) {
        val intents = mutableListOf<Intent>()
        ComponentName.unflattenFromString(config.inputsComponent)?.let { intents.add(Intent().setComponent(it)) }
        if (config.inputsAction.isNotBlank()) intents.add(Intent(config.inputsAction))
        // VIEW_INPUTS opens the input picker on the 55C350MP (verified on device); SELECT_INPUT is a generic fallback.
        intents.add(Intent("com.android.tv.action.VIEW_INPUTS"))
        intents.add(Intent("com.android.tv.action.SELECT_INPUT"))
        // Setup-inputs is NOT an input picker. Fall back to settings and the remote's Input key.
        if (!open(*intents.toTypedArray())) {
            Toast.makeText(activity, "Use the remote Input key; configure a tested picker in local config", Toast.LENGTH_LONG).show()
        }
    }
    fun launch(app: TvApp) = open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    // Google TV "Accounts & profiles"; older builds only expose account sync.
    fun account() = open(Intent("android.settings.USER_SETTINGS"), Intent(Settings.ACTION_SYNC_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun assist() = open(Intent(Intent.ACTION_VOICE_COMMAND), Intent(Intent.ACTION_ASSIST))
}
