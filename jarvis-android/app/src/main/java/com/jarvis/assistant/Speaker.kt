package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Jarvis's voice. Every speak() call reports back when it has finished talking. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private val callbacks = HashMap<String, () -> Unit>()
    private val queued = ArrayList<Pair<String, (() -> Unit)?>>()
    private var ready = false
    private var failed = false
    private var counter = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            queued.forEach { it.second?.let { cb -> main.post(cb) } }
            queued.clear()
            return
        }
        val result = tts.setLanguage(Locale.UK)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        tts.setSpeechRate(1.0f)
        tts.setPitch(0.92f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
        })
        ready = true
        val pending = ArrayList(queued)
        queued.clear()
        pending.forEach { speak(it.first, it.second) }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (failed) {
            onDone?.let { main.post(it) }
            return
        }
        if (!ready) {
            queued.add(text to onDone)
            return
        }
        val id = "jarvis_${counter++}"
        if (onDone != null) synchronized(callbacks) { callbacks[id] = onDone }
        // Safety net in case the TTS engine never reports back.
        main.postDelayed({ finish(id) }, 4000L + text.length * 110L)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    private fun finish(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) } ?: return
        main.post(cb)
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        tts.stop()
        tts.shutdown()
    }
}
