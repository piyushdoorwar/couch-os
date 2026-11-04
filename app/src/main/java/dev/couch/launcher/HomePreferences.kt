package dev.couch.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal const val DEFAULT_EVENING_HOUR = 20
internal const val MORNING_HOUR = 6

internal data class HomePreferences(
    val favourites: List<String>? = null,
    val hiddenApps: Set<String> = emptySet(),
    val pinnedShows: List<PlayedItem> = emptyList(),
    val hiddenShows: Set<String> = emptySet(),
    val watchedShows: Map<String, Long> = emptyMap(),
    val inputsPinned: Boolean = false,
    val ambientPinned: Boolean = false,
    val evening: Boolean = false,
    val eveningHour: Int = DEFAULT_EVENING_HOUR,
    val onlineArtwork: Boolean = false,
) {
    fun isEvening(hour: Int) = evening && (hour >= eveningHour || hour < MORNING_HOUR)

    fun addPin(item: PlayedItem): HomePreferences = copy(
        pinnedShows = (listOf(item) + pinnedShows.filter { it.key != item.key }).take(6),
        hiddenShows = hiddenShows - item.key,
        watchedShows = watchedShows - item.key,
    )

    fun removePin(key: String) = copy(pinnedShows = pinnedShows.filter { it.key != key })

    fun hideShow(key: String) = copy(hiddenShows = hiddenShows + key)

    fun markWatched(key: String, now: Long) = copy(watchedShows = watchedShows + (key to now))

    fun visibleShow(key: String, playedAt: Long): Boolean =
        key !in hiddenShows && (watchedShows[key]?.let { playedAt > it } ?: true)

    fun toJson(): JSONObject = JSONObject().apply {
        favourites?.let { put("favourites", JSONArray(it)) }
        put("hiddenApps", JSONArray(hiddenApps))
        // Pins keep the title and service. Live playback and artwork are resolved from current sources.
        put("pinnedShows", JSONArray(MediaHistory.serialise(pinnedShows)))
        put("hiddenShows", JSONArray(hiddenShows))
        put("watchedShows", JSONObject(watchedShows))
        put("inputsPinned", inputsPinned)
        put("ambientPinned", ambientPinned)
        put("evening", evening)
        put("eveningHour", eveningHour)
        put("onlineArtwork", onlineArtwork)
    }

    companion object {
        fun parse(json: JSONObject): HomePreferences {
            fun strings(key: String): List<String>? = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.distinct()
            }
            val watched = json.optJSONObject("watchedShows")
            return HomePreferences(
                favourites = strings("favourites"),
                hiddenApps = strings("hiddenApps").orEmpty().toSet(),
                pinnedShows = MediaHistory.parse(json.optJSONArray("pinnedShows")?.toString() ?: "[]", 6),
                hiddenShows = strings("hiddenShows").orEmpty().toSet(),
                watchedShows = watched?.keys()?.asSequence()?.associateWith { watched.optLong(it) }.orEmpty(),
                inputsPinned = json.optBoolean("inputsPinned"),
                ambientPinned = json.optBoolean("ambientPinned"),
                evening = json.optBoolean("evening"),
                eveningHour = json.optInt("eveningHour", DEFAULT_EVENING_HOUR).coerceIn(17, 23),
                onlineArtwork = json.optBoolean("onlineArtwork"),
            )
        }
    }
}

internal class HomePreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(): HomePreferences = runCatching {
        HomePreferences.parse(JSONObject(preferences.getString("layout", "{}") ?: "{}"))
    }.getOrDefault(HomePreferences())

    fun save(layout: HomePreferences) {
        preferences.edit().putString("layout", layout.toJson().toString()).apply()
    }
}
