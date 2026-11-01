package dev.couch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import java.net.HttpURLConnection
import java.net.URL

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
)

/**
 * Reads Android TV's Watch Next table: whatever apps chose to expose to the TV, nothing more.
 * Android usually limits a non-system home screen to its own rows, and many apps now report
 * Continue watching to Google's servers instead, so an empty result is expected on some TVs.
 * Only the row count is logged, never titles.
 */
class WatchNextSource(private val context: Context) {
    fun load(limit: Int = 5): List<WatchNextItem> {
        val projection = arrayOf(
            WatchNextPrograms.COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE, WatchNextPrograms.COLUMN_EPISODE_TITLE,
            WatchNextPrograms.COLUMN_POSTER_ART_URI, WatchNextPrograms.COLUMN_INTENT_URI,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        val items = runCatching {
            context.contentResolver.query(WatchNextPrograms.CONTENT_URI, projection, null, null,
                WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val pkg = c.getString(0) ?: continue
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        add(WatchNextItem(pkg, title, c.getString(2), c.getString(3), c.getString(4),
                            progress(c.getLong(5), c.getLong(6)), c.getLong(7)))
                    }
                }
            }
        }.onFailure { Log.i(TAG, "Watch Next unavailable: ${it.javaClass.simpleName}") }.getOrNull().orEmpty()
        Log.i(TAG, "Watch Next rows visible: ${items.size}")
        return items
    }

    /** Off the main thread. Posters are content:// or https URLs supplied by the publishing app. */
    fun poster(item: WatchNextItem): Bitmap? {
        val uri = item.poster ?: return null
        cache.get(uri)?.let { return it }
        return runCatching {
            val parsed = Uri.parse(uri)
            val bytes = when (parsed.scheme) {
                "content", "android.resource" -> context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                "https" -> (URL(uri).openConnection() as HttpURLConnection).run {
                    try {
                        connectTimeout = 6_000
                        readTimeout = 6_000
                        if (responseCode == 200) inputStream.use { it.readBytes() } else null
                    } finally { disconnect() }
                }
                else -> null // No cleartext or file access on behalf of another app.
            } ?: return@runCatching null
            decodeScaled(bytes, 720)
        }.getOrNull()?.also { cache.put(uri, it) }
    }

    companion object {
        private const val TAG = "CouchOS"
        private val cache = LruCache<String, Bitmap>(12)

        fun progress(position: Long, duration: Long): Float? =
            if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null

        private fun decodeScaled(bytes: ByteArray, targetWidth: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

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
