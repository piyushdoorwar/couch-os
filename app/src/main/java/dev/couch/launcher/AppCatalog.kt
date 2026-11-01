package dev.couch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** A launch component per package. Banners/icons stay in memory and are never exported. */
data class TvApp(val packageName: String, val label: String, val component: ComponentName, val artwork: Drawable?)

class AppCatalog(private val context: Context) {
    @Suppress("DEPRECATION")
    fun load(): List<TvApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                TvApp(info.activityInfo.packageName, info.loadLabel(pm).toString(),
                    ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    runCatching { info.activityInfo.loadBanner(pm) ?: info.loadIcon(pm) }.getOrNull())
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}

fun favouriteApps(apps: List<TvApp>, order: List<String>): List<TvApp> {
    val byPackage = apps.associateBy { it.packageName }
    return order.distinct().mapNotNull { byPackage[it] }
}
