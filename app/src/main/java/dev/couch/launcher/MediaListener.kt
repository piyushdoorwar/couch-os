package dev.couch.launcher

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors

/**
 * Notification-listener access is how Android lets a home screen see other apps' media sessions. This
 * reads no notifications: it only watches media sessions and records what plays into [MediaHistory].
 * Granted over adb by configure.sh when history.media_sessions=true; revoked otherwise.
 */
class MediaListener : NotificationListenerService() {
    private val io = Executors.newSingleThreadExecutor()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var sessions: MediaSessionManager? = null
    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        sessions = manager
        runCatching {
            manager.addOnActiveSessionsChangedListener(onSessionsChanged, MediaHistory.listener(this))
            watch(manager.getActiveSessions(MediaHistory.listener(this)))
        }.onFailure { Log.i(TAG, "Media sessions unavailable: ${it.javaClass.simpleName}") }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(onSessionsChanged)
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun watch(active: List<MediaController>) {
        callbacks.keys.filter { it !in active }.forEach { callbacks.remove(it)?.let(it::unregisterCallback) }
        active.filter { it !in callbacks && it.packageName != packageName }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = consider(controller)
                override fun onMetadataChanged(metadata: MediaMetadata?) = consider(controller)
            }
            controller.registerCallback(callback)
            callbacks[controller] = callback
            consider(controller)
        }
    }

    /** Record only while actually playing, so browsing a catalogue never lands in history. */
    private fun consider(controller: MediaController) {
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val metadata = controller.metadata
        val item = MediaHistory.describe(controller.packageName, metadata, System.currentTimeMillis()) ?: return
        val art = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }?.copy(Bitmap.Config.ARGB_8888, false)
        val artUri = metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        }?.takeIf { it.startsWith("https://") || it.startsWith("content://") }
        val session = controller.sessionActivity
        io.execute {
            MediaHistory.record(this, item.copy(art = artUri), art, session)
            // Package and what was available only; never the title.
            Log.i(TAG, "Media history: ${controller.packageName} title=yes art=${art != null || artUri != null} resume=${session != null}")
        }
    }

    private companion object {
        const val TAG = "CouchOS"
    }
}
