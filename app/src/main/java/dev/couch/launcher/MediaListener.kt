package dev.couch.launcher

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors

/** Android exposes other apps' sessions through opt-in notification-listener access. We read only sessions. */
class MediaListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val callbacks = mutableMapOf<MediaSession.Token, SessionWatch>()
    private val reported = mutableSetOf<String>()
    private var sessions: MediaSessionManager? = null
    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    private var retries = 0
    private val connect = Runnable { connectSessions() }

    override fun onListenerConnected() {
        sessions = getSystemService(MediaSessionManager::class.java)
        retries = 2
        main.removeCallbacks(connect)
        main.post(connect)
    }

    private fun connectSessions() {
        val manager = sessions ?: return
        val listener = MediaHistory.listener(this)
        try {
            manager.addOnActiveSessionsChangedListener(onSessionsChanged, listener, main)
            watch(manager.getActiveSessions(listener))
            MediaHistory.invalidate()
        } catch (_: SecurityException) {
            // The service can bind while the notification permission update is still being applied.
            if (retries-- > 0) main.postDelayed(connect, 2_000)
            else Log.i(TAG, "Media session access denied")
        }
    }

    override fun onListenerDisconnected() {
        stopWatching()
        MediaHistory.invalidate()
    }

    override fun onDestroy() {
        stopWatching()
        io.shutdown()
        super.onDestroy()
    }

    private fun stopWatching() {
        main.removeCallbacks(connect)
        sessions?.removeOnActiveSessionsChangedListener(onSessionsChanged)
        sessions = null
        callbacks.values.forEach { it.close() }
        callbacks.clear()
    }

    private fun watch(active: List<MediaController>) {
        if (sessions == null) return
        val tokens = active.map { it.sessionToken }.toSet()
        (callbacks.keys - tokens).forEach { callbacks.remove(it)?.close() }
        active.filter { it.sessionToken !in callbacks && it.packageName != packageName }.forEach { controller ->
            val callback = SessionWatch(controller)
            callbacks[controller.sessionToken] = callback
            controller.registerCallback(callback, main)
            callback.update(captureArtwork = true)
        }
    }

    private inner class SessionWatch(private val controller: MediaController) : MediaController.Callback() {
        private var item: PlayedItem? = null
        private var wasPlaying = false
        private var wasSessionPlaying = false
        private var lastUntitledAt = 0L
        private var closed = false

        override fun onPlaybackStateChanged(state: PlaybackState?) = update(playbackChanged = true)
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(captureArtwork = true)
        override fun onSessionDestroyed() {
            close()
            callbacks.remove(controller.sessionToken)
        }

        fun close() {
            if (closed) return
            closed = true
            controller.unregisterCallback(this)
        }

        fun update(captureArtwork: Boolean = false, playbackChanged: Boolean = false) {
            if (closed) return
            val state = controller.playbackState
            val playing = state?.state == PlaybackState.STATE_PLAYING
            val started = playing && !wasSessionPlaying
            wasSessionPlaying = playing
            val now = System.currentTimeMillis()
            val metadata = controller.metadata
            val described = MediaHistory.describe(controller.packageName, metadata, now)
            if (described == null) {
                if (playing) {
                    if (reported.add(controller.packageName)) {
                        Log.i(TAG, "Media playing without a title: ${controller.packageName} fields=${metadata?.keySet()?.size ?: 0}")
                    }
                    val at = MediaHistory.playbackTime(state.lastPositionUpdateTime, SystemClock.elapsedRealtime(), now)
                        ?: now.takeIf { playbackChanged && started }
                    if (at != null && at > lastUntitledAt) {
                        lastUntitledAt = at
                        io.execute { MediaHistory.recordUntitledPlayback(this@MediaListener, controller.packageName, at) }
                    }
                }
                wasPlaying = false
                return
            }
            val previous = item
            val same = previous != null && MediaHistory.matchesPlayback(previous, described)
            if (!playing && (!same || !wasPlaying)) return

            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
            val position = state?.position ?: -1
            val progress = WatchNextSource.progress(position, duration)?.let { (it * 100).toInt() / 100f }
            val display = metadata?.description
            val artUri = display?.iconUri?.toString()
                ?.takeIf { it.startsWith("content://") || it.startsWith("android.resource://") || it.startsWith("https://") }
            val artwork = display?.iconBitmap
            val current = described.copy(at = if (same && wasPlaying) previous!!.at else described.at,
                progress = progress ?: previous?.progress?.takeIf { same }, art = artUri)
            val captureArt = !same || captureArtwork && artwork !== lastArtwork
            if (current != previous || captureArt || playing != wasPlaying) {
                item = current
                if (captureArt) lastArtwork = artwork
                io.execute { MediaHistory.record(this@MediaListener, current, artwork.takeIf { captureArt }) }
            }
            wasPlaying = playing
        }

        private var lastArtwork: Bitmap? = null
    }

    private companion object {
        const val TAG = "CouchOS"
    }
}
