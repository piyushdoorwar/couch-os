package dev.couch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * A launch component per package. Artwork loads lazily per tile and stays in memory only.
 * Never mutated after the scan, so tiles may skip recomposition even though ActivityInfo is a platform type.
 */
@Immutable
data class TvApp(val packageName: String, val label: String, val component: ComponentName, val info: ActivityInfo)

class AppCatalog(private val context: Context) {
    /** Labels only: fast enough to show the row immediately even on a slow TV chip. */
    @Suppress("DEPRECATION")
    fun load(): List<TvApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                TvApp(info.activityInfo.packageName, info.loadLabel(pm).toString(),
                    ComponentName(info.activityInfo.packageName, info.activityInfo.name), info.activityInfo)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}

fun favouriteApps(apps: List<TvApp>, order: List<String>): List<TvApp> {
    val byPackage = apps.associateBy { it.packageName }
    return order.distinct().mapNotNull { byPackage[it] }
}

/** Decoded banner (or icon), whether it is a wide banner, and whether it is light enough to invert in monochrome. */
class Artwork(val image: ImageBitmap, val wide: Boolean, val light: Boolean)

object ArtworkCache {
    // An app update installs to a new path, so a stale banner is never reused.
    private fun key(app: TvApp) = app.packageName + "@" + app.info.applicationInfo.sourceDir
    // Holds every TV app's banner (about 0.4 MB each at 512 px) so the whole grid stays decoded.
    private val cache = LruCache<String, Artwork>(64)

    fun peek(app: TvApp): Artwork? = cache.get(key(app))

    /** Call off the main thread. */
    fun load(context: Context, app: TvApp): Artwork? {
        peek(app)?.let { return it }
        val pm = context.packageManager
        val banner = runCatching { app.info.loadBanner(pm) }.getOrNull()
        val drawable = banner ?: runCatching { app.info.loadIcon(pm) }.getOrNull() ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 320
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
        val factor = minOf(1f, 512f / maxOf(width, height))
        val raw = drawable.toBitmap((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
        return Artwork(raw.asImageBitmap(), banner != null, meanLuminance(raw) > 0.55f).also { cache.put(key(app), it) }
    }

    /** Mean relative luminance of opaque pixels, sampled on a tiny copy. */
    private fun meanLuminance(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 9, true)
        var sum = 0f
        var count = 0
        for (x in 0 until small.width) for (y in 0 until small.height) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            sum += (0.2126f * android.graphics.Color.red(c) + 0.7152f * android.graphics.Color.green(c) +
                0.0722f * android.graphics.Color.blue(c)) / 255f
            count++
        }
        return if (count == 0) 0f else sum / count
    }
}
