package dev.couch.launcher

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One show the owner played, as the playing app described it in its media session. */
@Immutable
data class PlayedItem(val packageName: String, val title: String, val subtitle: String?, val at: Long, val art: String?,
    val progress: Float? = null, val mediaId: String? = null) {
    val key get() = showKey(packageName, title)
}

/**
 * The last few shows played on this TV, captured from media sessions by [MediaListener]. Stored only in
 * app-private files (backups are disabled); never uploaded or logged. Opt-in: nothing is recorded or shown
 * unless the owner granted listener access with history.media_sessions=true.
 */
object MediaHistory {
    const val LIMIT = 5
    private const val FILE = "media-history.json"
    private const val ART_DIR = "media-art"
    private const val UNTITLED_PLAYBACK = "untitled_playback"
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()

    fun invalidate() { revision.update { it + 1 } }

    fun listener(context: Context) = ComponentName(context, MediaListener::class.java)

    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener(context))

    @Synchronized
    fun load(context: Context): List<PlayedItem> {
        if (!enabled(context)) return emptyList()
        return runCatching { parse(File(context.filesDir, FILE).readText()) }.getOrDefault(emptyList())
    }

    fun untitledPlayback(context: Context, now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (!enabled(context)) return emptyMap()
        return context.getSharedPreferences(UNTITLED_PLAYBACK, Context.MODE_PRIVATE).all.mapNotNull { (pkg, value) ->
            (value as? Long)?.takeIf { it in (now - RecentApps.WINDOW_MS)..now }?.let { pkg to it }
        }.toMap()
    }

    @Synchronized
    fun recordUntitledPlayback(context: Context, packageName: String, at: Long) {
        if (!enabled(context)) return
        val preferences = context.getSharedPreferences(UNTITLED_PLAYBACK, Context.MODE_PRIVATE)
        if (at <= preferences.getLong(packageName, 0L)) return
        preferences.edit().putLong(packageName, at).apply()
        invalidate()
    }

    fun playbackTime(updateElapsed: Long, nowElapsed: Long, now: Long): Long? {
        if (updateElapsed !in 1..nowElapsed) return null
        val age = nowElapsed - updateElapsed
        return (now - age).takeIf { age <= RecentApps.WINDOW_MS }
    }

    @Synchronized
    fun record(context: Context, item: PlayedItem, artwork: Bitmap?) {
        if (!enabled(context)) return
        val current = runCatching { parse(File(context.filesDir, FILE).readText()) }.getOrDefault(emptyList())
        val previous = current.find { it.key == item.key }
        if (previous != null && previous.at > item.at) return
        val art = artwork?.let { saveArt(context, item, it) } ?: item.art ?: previous?.art
        val updated = merge(current, item.copy(art = art))
        if (!writeAtomically(File(context.filesDir, FILE), serialise(updated))) return
        // Drop artwork no entry refers to any more.
        val keep = updated.mapNotNull { it.art }.toSet()
        File(context.filesDir, ART_DIR).listFiles()?.forEach { if (it.name !in keep) it.delete() }
        invalidate()
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, ART_DIR).deleteRecursively()
        context.getSharedPreferences(UNTITLED_PLAYBACK, Context.MODE_PRIVATE).edit().clear().apply()
        invalidate()
    }

    /** Check live metadata at click time: a session can have moved on to a different episode. */
    fun activeSession(context: Context, item: PlayedItem): MediaController? = runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener(context))
            .firstOrNull { controller ->
                controller.packageName == item.packageName && controller.sessionActivity != null &&
                    describe(item.packageName, controller.metadata, item.at)?.let { matchesPlayback(item, it) } == true
            }
    }.getOrNull()

    fun canResume(controller: MediaController): Boolean {
        val state = controller.playbackState ?: return false
        return state.state == PlaybackState.STATE_PAUSED && state.actions and PlaybackState.ACTION_PLAY != 0L
    }

    fun matchesPlayback(recorded: PlayedItem, live: PlayedItem): Boolean =
        recorded.key == live.key && when {
            recorded.mediaId != null || live.mediaId != null -> recorded.mediaId != null && recorded.mediaId == live.mediaId
            else -> recorded.subtitle == live.subtitle
        }

    fun artFile(context: Context, item: PlayedItem): File? =
        item.art?.takeIf { it.matches(Regex("[0-9a-f]{16}\\.jpg")) }?.let { File(File(context.filesDir, ART_DIR), it) }?.takeIf { it.exists() }

    /** Newest first, one entry per show, at most [LIMIT]. */
    fun merge(current: List<PlayedItem>, played: PlayedItem): List<PlayedItem> =
        (listOf(played) + current.filter { it.key != played.key }).sortedByDescending { it.at }.take(LIMIT)

    /** Prefer the app's display fields, which TV apps fill for the "now playing" card. */
    fun describe(packageName: String, metadata: MediaMetadata?, now: Long): PlayedItem? {
        metadata ?: return null
        fun text(key: String) = metadata.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: text(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val subtitle = text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: text(MediaMetadata.METADATA_KEY_ALBUM)
        return PlayedItem(packageName, title.take(120), subtitle?.take(120)?.takeIf { it != title }, now, null,
            mediaId = text(MediaMetadata.METADATA_KEY_MEDIA_ID)?.take(512))
    }

    fun parse(json: String, limit: Int = LIMIT): List<PlayedItem> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val pkg = entry.optString("pkg").takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }
            val title = entry.optString("title").takeIf { it.isNotBlank() }
            if (pkg == null || title == null) null
            else PlayedItem(pkg, title, entry.optString("subtitle").takeIf { it.isNotBlank() }, entry.optLong("at"),
                entry.optString("art").takeIf { it.isNotBlank() },
                entry.optDouble("progress").takeIf { it in 0.0..1.0 }?.toFloat(),
                entry.optString("mediaId").takeIf { it.isNotBlank() })
        }.take(limit)
    }

    fun serialise(items: List<PlayedItem>): String = JSONArray(items.map {
        JSONObject().put("pkg", it.packageName).put("title", it.title).put("subtitle", it.subtitle ?: "")
            .put("at", it.at).put("art", it.art ?: "").put("progress", it.progress?.toDouble()).put("mediaId", it.mediaId)
    }).toString()

    private fun saveArt(context: Context, item: PlayedItem, bitmap: Bitmap): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(item.key.toByteArray())
        val name = digest.take(8).joinToString("") { "%02x".format(it) } + ".jpg"
        val dir = File(context.filesDir, ART_DIR).apply { mkdirs() }
        val scale = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name.takeIf { temp.renameTo(target) }
    }.getOrNull()

    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        temp.renameTo(file)
    }.getOrDefault(false)
}
