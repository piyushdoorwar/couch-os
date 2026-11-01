package dev.couch.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File

/**
 * Receives the owner's private settings from configure.sh via `adb shell content write`, which streams
 * stdin: values never appear in a command line, an APK or shared storage. The manifest guards this
 * provider with android.permission.DUMP, which only the shell and system hold, so other apps cannot
 * write it. Works in release builds, unlike run-as.
 */
class ConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        require(uri.lastPathSegment == LocalConfig.FILE && mode.startsWith("w")) { "write-only: ${LocalConfig.FILE}" }
        val context = requireNotNull(context)
        val staging = File(context.filesDir, LocalConfig.FILE + ".incoming").apply { delete() }
        val flags = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        return ParcelFileDescriptor.open(staging, flags, Handler(Looper.getMainLooper())) { error ->
            // Accept only a complete, parseable, small config; a failed or partial write leaves the old one.
            val ok = error == null && staging.length() in 2..MAX_BYTES &&
                runCatching { LocalConfig.parse(JSONObject(staging.readText())) }.isSuccess
            if (ok) staging.renameTo(File(context.filesDir, LocalConfig.FILE)) else staging.delete()
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "dev.couch.launcher.config"
        private const val MAX_BYTES = 16 * 1024L
    }
}
