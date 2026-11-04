package dev.couch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import java.io.InputStream

/** One "continue watching" entry an app published to the TV's shared Watch Next list. */
@Immutable
data class WatchNextItem(
    val packageName: String,
    val title: String,
    val episode: String?,
    val poster: String?,
    val intentUri: String?,
    val progress: Float?,
    val lastEngaged: Long,
    val thumbnail: String? = null,
)

/**
 * Reads Android TV's Watch Next table: whatever apps chose to expose to the TV, nothing more.
 * READ_TV_LISTINGS permits access to searchable rows. The stock launcher can also see rows
 * that require the system-only EPG permission. Partner feeds can be separate from this table.
 * Only the row count is logged, never titles.
 */
class WatchNextSource(private val context: Context) {
    fun load(limit: Int = 20): List<WatchNextItem> {
        val projection = arrayOf(
            WatchNextPrograms.COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE, WatchNextPrograms.COLUMN_EPISODE_TITLE,
            WatchNextPrograms.COLUMN_POSTER_ART_URI, WatchNextPrograms.COLUMN_INTENT_URI,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
        )
        val items = runCatching {
            val order = WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC"
            val resolver = context.contentResolver
            val cursor = try {
                resolver.query(WatchNextPrograms.CONTENT_URI, projection + WatchNextPrograms.COLUMN_THUMBNAIL_URI, null, null, order)
            } catch (_: IllegalArgumentException) {
                resolver.query(WatchNextPrograms.CONTENT_URI, projection, null, null, order)
            } catch (_: SQLiteException) {
                resolver.query(WatchNextPrograms.CONTENT_URI, projection, null, null, order)
            }
            cursor?.use { c ->
                val thumbnailColumn = c.getColumnIndex(WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val type = c.getInt(8)
                        if (type != WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && type != WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue
                        val pkg = c.getString(0) ?: continue
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val thumbnail = if (thumbnailColumn >= 0) c.getString(thumbnailColumn) else null
                        add(WatchNextItem(pkg, title, c.getString(2), c.getString(3), c.getString(4),
                            progress(c.getLong(5), c.getLong(6)), c.getLong(7), thumbnail))
                    }
                }
            }
        }.onFailure { Log.i(TAG, "Watch Next unavailable: ${it.javaClass.simpleName}") }.getOrNull().orEmpty()
        Log.i(TAG, "Watch Next rows visible: ${items.size}")
        if (items.isNotEmpty()) {
            val local = items.count { item -> listOf(item.thumbnail, item.poster).any { isLocalArt(it) } }
            val online = items.count { item -> listOf(item.thumbnail, item.poster).any { it?.startsWith("https://") == true } }
            Log.i(TAG, "Watch Next artwork candidates: local=$local https=$online")
        }
        return items
    }

    /** Try accessible on-device artwork first, even when online images are enabled. */
    fun poster(candidates: List<String>, onlineEnabled: Boolean): Bitmap? {
        candidates.filter(::isLocalArt).firstNotNullOfOrNull(::localPoster)?.let { return it }
        if (!onlineEnabled) return null
        return candidates.filter { it.startsWith("https://") }.firstNotNullOfOrNull(::onlinePoster)
    }

    private fun localPoster(uri: String): Bitmap? = cache.get(uri) ?: runCatching {
        decodePoster { context.contentResolver.openInputStream(Uri.parse(uri)) }
    }.getOrNull()?.also { cache.put(uri, it) }

    private fun onlinePoster(uri: String): Bitmap? = cache.get(uri) ?: runCatching {
        OnlineArtwork.read(uri)?.let { bytes -> decodePoster { bytes.inputStream() } }
    }.getOrNull()?.also { cache.put(uri, it) }

    private fun decodePoster(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
        return open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    companion object {
        private const val TAG = "CouchOS"
        private val cache = LruCache<String, Bitmap>(12)

        private fun isLocalArt(uri: String?): Boolean =
            uri?.startsWith("content://") == true || uri?.startsWith("android.resource://") == true

        fun progress(position: Long, duration: Long): Float? =
            if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null

        /**
         * The publishing app's deep link, confined to that app: no other package, no URI permission grants.
         * Null means "just open the app".
         */
        fun deepLink(item: WatchNextItem): Intent? {
            val raw = item.intentUri ?: return null
            val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
            intent.selector = null
            intent.component?.let { if (it.packageName != item.packageName) intent.component = null as ComponentName? }
            intent.setPackage(item.packageName)
            intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
