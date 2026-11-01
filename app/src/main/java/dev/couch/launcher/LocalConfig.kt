package dev.couch.launcher

import android.content.Context
import org.json.JSONObject

/** Provisioned into app-private files by scripts/configure.sh (via ConfigProvider); never compiled into an APK. */
data class LocalConfig(
    val favourites: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val inputsComponent: String = "",
    val inputsAction: String = "",
    val voiceHelper: Boolean = false,
    /** One letter for the avatar circle; apps cannot read the Google account picture. */
    val profileInitial: String? = null,
) {
    companion object {
        const val FILE = "local-config.json"
        fun parse(json: JSONObject): LocalConfig {
            val entries = json.optJSONArray("favourites")
            val packages = (0 until (entries?.length() ?: 0)).map { entries!!.getString(it) }
                .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }.distinct()
            val lat = json.optDouble("latitude").takeIf { it.isFinite() && it in -90.0..90.0 }
            val lon = json.optDouble("longitude").takeIf { it.isFinite() && it in -180.0..180.0 }
            return LocalConfig(packages, if (lon != null) lat else null, if (lat != null) lon else null,
                json.optString("inputsComponent"), json.optString("inputsAction"), json.optBoolean("voiceHelper"),
                json.optString("profileInitial").take(1).uppercase().takeIf { it.isNotEmpty() && it[0].isLetterOrDigit() })
        }
        fun read(context: Context): LocalConfig = runCatching {
            parse(JSONObject(context.filesDir.resolve(FILE).readText()))
        }.getOrDefault(LocalConfig())
    }
}
