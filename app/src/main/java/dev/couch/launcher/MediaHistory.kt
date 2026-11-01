package dev.couch.launcher

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One show the owner played, as the playing app described it in its media session. */
@Immutable
data class PlayedItem(val packageName: String, val title: String, val subtitle: String?, val at: Long, val art: String?) {
    val key get() = "$packageName\n$title"
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

    /** Session launch intents, live only while the playing app keeps its session; used to resume directly. */
    private val liveSessions = ConcurrentHashMap<String, PendingIntent>()

    fun listener(context: Context) = ComponentName(context, MediaListener::class.java)

    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener(context))

    @Synchronized
    fun load(context: Context): List<PlayedItem> {
        if (!enabled(context)) return emptyList()
        return runCatching { parse(File(context.filesDir, FILE).readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(context: Context, item: PlayedItem, artwork: Bitmap?, session: PendingIntent?) {
        session?.let { liveSessions[item.key] = it }
        val current = runCatching { parse(File(context.filesDir, FILE).readText()) }.getOrDefault(emptyList())
        val art = artwork?.let { saveArt(context, item, it) } ?: item.art ?: current.find { it.key == item.key }?.art
        val updated = merge(current, item.copy(art = art))
        writeAtomically(File(context.filesDir, FILE), serialise(updated))
        // Drop artwork no entry refers to any more.
        val keep = updated.mapNotNull { it.art }.toSet()
        File(context.filesDir, ART_DIR).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    @Synchronized
    fun clear(context: Context) {
        liveSessions.clear()
        File(context.filesDir, FILE).delete()
        File(context.filesDir, ART_DIR).deleteRecursively()
    }

    fun liveSession(item: PlayedItem): PendingIntent? = liveSessions[item.key]

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
        return PlayedItem(packageName, title.take(120), subtitle?.take(120)?.takeIf { it != title }, now, null)
    }

    fun parse(json: String): List<PlayedItem> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val pkg = entry.optString("pkg").takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }
            val title = entry.optString("title").takeIf { it.isNotBlank() }
            if (pkg == null || title == null) null
            else PlayedItem(pkg, title, entry.optString("subtitle").takeIf { it.isNotBlank() }, entry.optLong("at"),
                entry.optString("art").takeIf { it.isNotBlank() })
        }.take(LIMIT)
    }

    fun serialise(items: List<PlayedItem>): String = JSONArray(items.map {
        JSONObject().put("pkg", it.packageName).put("title", it.title).put("subtitle", it.subtitle ?: "")
            .put("at", it.at).put("art", it.art ?: "")
    }).toString()

    private fun saveArt(context: Context, item: PlayedItem, bitmap: Bitmap): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(item.key.toByteArray())
        val name = digest.take(8).joinToString("") { "%02x".format(it) } + ".jpg"
        val dir = File(context.filesDir, ART_DIR).apply { mkdirs() }
        val scaled = if (bitmap.width > 720) Bitmap.createScaledBitmap(bitmap, 720, (bitmap.height * 720f / bitmap.width).toInt().coerceAtLeast(1), true)
            else bitmap
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        temp.renameTo(target)
        name
    }.getOrNull()

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        temp.renameTo(file)
    }
}
