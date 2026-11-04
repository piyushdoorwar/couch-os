package dev.couch.launcher

import androidx.compose.runtime.Immutable
import java.util.Locale

internal fun showKey(packageName: String, title: String) = "$packageName\n${title.trim().lowercase(Locale.ROOT)}"

@Immutable
internal data class WatchCard(
    val app: TvApp,
    val title: String,
    val played: PlayedItem? = null,
    val show: WatchNextItem? = null,
    val pin: PlayedItem? = null,
) {
    val key get() = showKey(app.packageName, title)
    private val latestFromProvider get() = show != null && show.lastEngaged >= (played?.at ?: 0)
    private val sameEpisode get() = !show?.episode.isNullOrBlank() && show?.episode == played?.subtitle
    val episode get() = if (latestFromProvider) show?.episode else played?.subtitle
    val progress get() = if (latestFromProvider) show?.progress ?: played?.progress?.takeIf { sameEpisode }
        else played?.progress ?: show?.progress?.takeIf { sameEpisode }
    val engagedAt get() = maxOf(show?.lastEngaged ?: 0, played?.at ?: 0)

    // A stale link could open a previous episode. Prefer a current session or the app in that case.
    val linkedShow get() = show?.takeIf { latestFromProvider || sameEpisode }
    val livePlayback get() = played?.takeIf { !latestFromProvider || sameEpisode }
}

internal fun watchCards(
    apps: List<TvApp>,
    played: List<PlayedItem>,
    shows: List<WatchNextItem>,
    layout: HomePreferences,
    untitledPlayback: Map<String, Long> = emptyMap(),
): List<WatchCard> {
    val byPackage = apps.filterNot { it.packageName in layout.hiddenApps }.associateBy { it.packageName }
    val history = played.sortedByDescending { it.at }.distinctBy { it.key }.associateBy { it.key }
    val provider = shows.sortedByDescending { it.lastEngaged }
        .distinctBy { showKey(it.packageName, it.title) }.associateBy { showKey(it.packageName, it.title) }
    val pins = layout.pinnedShows.associateBy { it.key }
    // An untitled session identifies the app only. Move at most its newest published entry,
    // and only when the app has no newer title-specific evidence.
    val inferredTimes = provider.values.distinctBy { it.packageName }.mapNotNull { row ->
        val at = untitledPlayback[row.packageName] ?: return@mapNotNull null
        val latestTitle = history.values.filter { it.packageName == row.packageName }.maxOfOrNull { it.at } ?: 0
        if (at > maxOf(latestTitle, row.lastEngaged)) showKey(row.packageName, row.title) to at else null
    }.toMap()
    return (pins.keys + history.keys + provider.keys).mapNotNull { key ->
        val play = history[key]
        val row = provider[key]
        val pin = pins[key]
        val app = byPackage[play?.packageName ?: row?.packageName ?: pin?.packageName] ?: return@mapNotNull null
        val title = row?.title ?: play?.title ?: pin?.title ?: return@mapNotNull null
        if (pin == null && title.equals(app.label, ignoreCase = true)) return@mapNotNull null
        WatchCard(app, title, play, row, pin).takeIf { layout.visibleShow(key, it.engagedAt) }
    }.sortedWith(compareByDescending<WatchCard> { maxOf(it.engagedAt, inferredTimes[it.key] ?: 0) }.thenByDescending { it.pin?.at ?: 0 })
        .let { cards ->
            // Keep space for explicit pins while still putting the most recently played title first.
            val selected = (cards.take(1) + cards.filter { it.pin != null } + cards)
                .distinctBy { it.key }.take(6).toSet()
            cards.filter { it in selected }
        }
}
