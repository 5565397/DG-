package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Voxa's voice. Every speak() call reports back when it has finished talking. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private val ENGLISH = Locale.UK
        private val HINDI = Locale("hi", "IN")
        private val URDU = Locale("ur", "PK")

        /** Whether this phone has a Hindi / Urdu text-to-speech voice installed. */
        @Volatile var hindiVoice = false
            private set
        @Volatile var urduVoice = false
            private set
        /** False until a speech engine has told us which voices exist. */
        @Volatile var voicesChecked = false
            private set
    }

    private var currentLocale: Locale? = null
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
        hindiVoice = tts.isLanguageAvailable(HINDI) >= TextToSpeech.LANG_AVAILABLE
        urduVoice = tts.isLanguageAvailable(URDU) >= TextToSpeech.LANG_AVAILABLE
        voicesChecked = true
        useLocale(ENGLISH)
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
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
        useLocale(localeFor(text))
        val id = "jarvis_${counter++}"
        if (onDone != null) synchronized(callbacks) { callbacks[id] = onDone }
        // Safety net in case the TTS engine never reports back.
        main.postDelayed({ finish(id) }, 4000L + text.length * 110L)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    /** Picks the voice from the script the text is written in. */
    private fun localeFor(text: String): Locale = when {
        Hindustani.isUrduScript(text) && urduVoice -> URDU
        Hindustani.isDevanagari(text) && hindiVoice -> HINDI
        Hindustani.isUrduScript(text) && hindiVoice -> HINDI
        else -> ENGLISH
    }

    private fun useLocale(locale: Locale) {
        if (locale == currentLocale) return
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        currentLocale = locale
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
