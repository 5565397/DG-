package com.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Voxa's voice. Every speak() call reports back when it has finished talking.
 * Uses ElevenLabs' human-like voice when it's set up, otherwise the best voice the phone has
 * (Google's natural "network" voices first), and falls back to the phone if the internet fails.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private val ENGLISH = Locale.UK
        /** Reads Roman Urdu/Hindi (Hinglish) far more naturally than a British or American voice. */
        private val INDIAN_ENGLISH = Locale("en", "IN")
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

        /** Short lines ("Ji, sir?") are kept so the human voice answers instantly next time. */
        private const val CACHE_MAX_CHARS = 80
    }

    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private var currentLocale: Locale? = null
    private var appliedVoicePref = ""
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(app, this)
    private val callbacks = HashMap<String, () -> Unit>()
    private val queued = ArrayList<Pair<String, (() -> Unit)?>>()
    private var ready = false
    private var failed = false
    private var counter = 0
    private var player: MediaPlayer? = null
    /** Bumped by stopSilently(), so a human-voice clip still downloading is never played afterwards. */
    @Volatile private var generation = 0

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
        if (prefs.humanVoice) {
            speakHuman(text, onDone)
            return
        }
        speakPhone(text, onDone)
    }

    // ---------------------------------------------------------------- phone voice

    private fun speakPhone(text: String, onDone: (() -> Unit)?, id: String = "jarvis_${counter++}") {
        if (failed) {
            onDone?.let { main.post(it) }
            return
        }
        if (!ready) {
            queued.add(text to onDone)
            return
        }
        useLocale(localeFor(text))
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
        prefs.replyHinglish -> INDIAN_ENGLISH
        else -> ENGLISH
    }

    private fun useLocale(locale: Locale) {
        // Re-pick when the language changes or the user chose another voice in Settings.
        if (locale == currentLocale && prefs.phoneVoice == appliedVoicePref) return
        appliedVoicePref = prefs.phoneVoice
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        bestVoice(locale)?.let { tts.voice = it }
        currentLocale = locale
    }

    /** The voice the user picked for this language, or the most natural one installed. */
    private fun bestVoice(locale: Locale): Voice? {
        val all = try {
            tts.voices.orEmpty()
        } catch (e: Exception) {
            return null
        }
        val sameLanguage = all.filter {
            it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        sameLanguage.firstOrNull { it.name == prefs.phoneVoice }?.let { return it }
        return sameLanguage.sortedWith(
            compareByDescending<Voice> { it.locale.country == locale.country }
                .thenByDescending { it.quality }
                // Google's "network" voices are its natural-sounding neural ones.
                .thenByDescending { it.isNetworkConnectionRequired }
                .thenBy { it.latency }
        ).firstOrNull()
    }

    /** Voices for the language Voxa answers in, best first, for the voice picker in Settings. */
    fun voiceChoices(): List<Voice> {
        if (!ready) return emptyList()
        val locale = if (prefs.replyHinglish) INDIAN_ENGLISH else when (Lang.mode(prefs)) {
            "ur" -> if (urduVoice) URDU else HINDI
            "hi" -> HINDI
            else -> ENGLISH
        }
        return try {
            tts.voices.orEmpty()
                .filter { it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- human voice (ElevenLabs)

    private fun speakHuman(text: String, onDone: (() -> Unit)?) {
        val id = "jarvis_${counter++}"
        if (onDone != null) synchronized(callbacks) { callbacks[id] = onDone }
        val myGeneration = generation
        // Safety net: a slow network must never leave Voxa stuck.
        main.postDelayed({ finish(id) }, 15_000L + text.length * 120L)
        val key = prefs.elevenKey
        val voiceId = prefs.elevenVoiceId
        Thread {
            val file = try {
                clip(key, voiceId, text)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (myGeneration != generation) return@post
                if (file == null) {
                    // No internet or a key problem: say it with the phone's voice instead.
                    speakPhone(text, synchronized(callbacks) { callbacks.remove(id) }, id)
                    return@post
                }
                play(file, id)
            }
        }.start()
    }

    private fun clip(key: String, voiceId: String, text: String): File {
        val cacheable = text.length <= CACHE_MAX_CHARS
        val dir = File(app.cacheDir, "voice").apply { mkdirs() }
        val cached = File(dir, "${voiceId}_${text.hashCode().toUInt()}.mp3")
        if (cacheable && cached.exists() && cached.length() > 0) return cached
        val audio = ElevenLabs.speech(key, voiceId, text)
        val out = if (cacheable) cached else File(dir, "last.mp3")
        out.writeBytes(audio)
        return out
    }

    private fun play(file: File, id: String) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                releasePlayer()
                finish(id)
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                finish(id)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            releasePlayer()
            finish(id)
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    // ---------------------------------------------------------------- common

    private fun finish(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) } ?: return
        main.post(cb)
    }

    /** Stops talking right away without running any "done" callbacks (used when the user interrupts). */
    fun stopSilently() {
        generation++
        synchronized(callbacks) { callbacks.clear() }
        queued.clear()
        releasePlayer()
        if (ready) tts.stop()
    }

    fun shutdown() {
        generation++
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        tts.stop()
        tts.shutdown()
    }
}
