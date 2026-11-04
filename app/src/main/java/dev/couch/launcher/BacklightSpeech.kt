package dev.couch.launcher

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.util.Locale

internal class BacklightSpeech(
    private val context: Context,
    private val name: String,
    private val onCompleted: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Boolean? = null
    private var utterance: String? = null
    private var sequence = 0

    init {
        engine = TextToSpeech(context) { status -> main.post { initialise(status) } }
    }

    private fun initialise(status: Int) {
        val tts = engine ?: return
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        failed = !ready
        if (ready) {
            val current = tts.voice
            val offline = tts.voices?.firstOrNull {
                it.locale == Locale.US && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }
            if (current?.isNetworkConnectionRequired != false && offline != null) tts.voice = offline
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(utteranceId, success = true)
                override fun onError(utteranceId: String?) = finish(utteranceId, success = false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, success = null)
            })
        }
        if (failed && pending != null) {
            pending = null
            unavailable()
        } else {
            startPending()
        }
    }

    fun speak(on: Boolean) {
        if (engine == null || pending != null) return
        if (failed) {
            unavailable()
            return
        }
        pending = on
        startPending()
    }

    private fun startPending() {
        val tts = engine ?: return
        val on = pending ?: return
        if (!ready) return
        val id = "couch-backlight-${++sequence}"
        utterance = id
        if (tts.speak(backlightCommand(name, on), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finish(id, success = false)
        }
    }

    private fun finish(id: String?, success: Boolean?) {
        main.post {
            if (id == null || id != utterance) return@post
            val on = pending ?: return@post
            pending = null
            utterance = null
            if (success == true) {
                onCompleted(on)
                Log.i("CouchOS", "Backlight speech completed: ${if (on) "on" else "off"}")
            } else if (success == false) {
                unavailable()
            }
        }
    }

    private fun unavailable() {
        Toast.makeText(context, "Backlight speech is unavailable", Toast.LENGTH_SHORT).show()
    }

    fun close() {
        pending = null
        utterance = null
        val tts = engine
        engine = null
        tts?.stop()
        tts?.shutdown()
    }
}

internal fun backlightCommand(name: String, on: Boolean) =
    "Hey, Google, Turn ${if (on) "on" else "off"} the $name"
