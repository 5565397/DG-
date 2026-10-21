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

        /** Last reason the online voice failed, shown once in the chat. */
        @Volatile private var reportedError: String? = null
        /** After a key/terms/limit error, don't wait on the online voice again for a while. */
        @Volatile private var onlineVoiceOffUntil = 0L

        // Android voices don't say whether they're female, but their names do (Google: en-in-x-ena…,
        // Samsung: …SMTf00). Voxa is a woman, so female voices come first.
        private val FEMALE_CODES = setOf("ena", "enc", "sfg", "iob", "iog", "tpc", "tpf", "gba", "gbc", "gbg", "hia", "hic", "cfn", "cfc", "aua", "auc", "cxx")
        private val MALE_CODES = setOf("end", "ene", "iol", "iom", "tpd", "gbb", "gbd", "rjs", "hid", "hie", "aub", "aud", "cfm")

        /** true = female, false = male, null = unknown. */
        fun isFemale(voice: Voice): Boolean? {
            val name = voice.name.lowercase(Locale.ROOT)
            if (Regex("smtf\\d|female|-f\\d").containsMatchIn(name)) return true
            if (Regex("smtm\\d|(?<!fe)male|-m\\d").containsMatchIn(name)) return false
            val code = Regex("-x-([a-z]{3})").find(name)?.groupValues?.get(1) ?: return null
            return when (code) {
                in FEMALE_CODES -> true
                in MALE_CODES -> false
                else -> null
            }
        }

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
                .thenByDescending { isFemale(it) == true }
                .thenBy { isFemale(it) == false }
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
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country == locale.country }
                        .thenByDescending { isFemale(it) == true }
                        .thenBy { isFemale(it) == false }
                        .thenByDescending { it.quality }
                        .thenBy { it.name }
                )
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- human voice (ElevenLabs)

    private fun speakHuman(text: String, onDone: (() -> Unit)?) {
        val groq = prefs.voiceEngine == Prefs.VOICE_GROQ
        // Groq's voice reads English letters only; Urdu or Hindi script goes to the phone voice.
        if ((groq && !Translit.isLatin(text)) || System.currentTimeMillis() < onlineVoiceOffUntil) {
            speakPhone(text, onDone)
            return
        }
        val id = "jarvis_${counter++}"
        if (onDone != null) synchronized(callbacks) { callbacks[id] = onDone }
        val myGeneration = generation
        // Safety net: a slow network must never leave Voxa stuck.
        main.postDelayed({ finish(id) }, 20_000L + text.length * 150L)
        // Long answers go in pieces: the first sentence plays while the next ones are made.
        val pieces = if (groq) splitForSpeech(text, 190) else listOf(text)
        val voiceKey = if (groq) "groq_${prefs.groqVoice}" else prefs.elevenVoiceId
        Thread {
            for ((i, piece) in pieces.withIndex()) {
                if (myGeneration != generation) return@Thread
                val file = try {
                    clip(voiceKey, piece, "${id}_$i") {
                        if (groq) GroqClient(prefs).speech(piece, prefs.groqVoice) else ElevenLabs.speech(prefs.elevenKey, prefs.elevenVoiceId, piece)
                    }
                } catch (e: Exception) {
                    reportVoiceError(groq, e)
                    null
                }
                if (file == null) {
                    // No internet or a key problem: say the rest with the phone's voice instead.
                    val rest = pieces.drop(i).joinToString(" ")
                    main.post {
                        if (myGeneration != generation) return@post
                        afterClips(id) { speakPhone(rest, synchronized(callbacks) { callbacks.remove(id) }, id) }
                    }
                    return@Thread
                }
                val last = i == pieces.lastIndex
                main.post { if (myGeneration == generation) enqueue(file, last, id) }
            }
        }.start()
    }

    /** Tells the user once, in the chat, why the online voice isn't speaking (so it's not a mystery male voice). */
    private fun reportVoiceError(groq: Boolean, e: Exception) {
        val reason = when {
            e is GroqClient.GroqException && e.message.orEmpty().contains("terms", true) ->
                "Groq voice needs one-time approval: open console.groq.com → Playground, choose \"canopylabs/orpheus-v1-english\" and accept its terms. Until then the phone voice speaks."
            e is GroqClient.GroqException && e.code == 401 -> "Groq rejected the key, so the phone voice is speaking. Check the key in Settings → AI Brain."
            e is GroqClient.GroqException && e.code == 429 -> "Groq's voice limit is reached for now, so the phone voice is speaking."
            e is GroqClient.GroqException -> "Groq voice error ${e.code}: ${e.message.orEmpty().take(120)}. The phone voice is speaking."
            e is ElevenLabs.ElevenException -> "ElevenLabs voice error ${e.code}: ${e.message.orEmpty().take(120)}. The phone voice is speaking."
            else -> null // no internet: nothing to fix, just carry on
        } ?: return
        onlineVoiceOffUntil = System.currentTimeMillis() + 10 * 60_000L
        if (reason == reportedError) return
        reportedError = reason
        main.post { ChatStore.add(app, ChatStore.Role.NOTE, reason) }
    }

    /** Sentences joined up to [max] characters, so each request stays small and quick. */
    private fun splitForSpeech(text: String, max: Int): List<String> {
        val sentences = text.trim().split(Regex("(?<=[.!?।؟])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out.add(current.toString().trim())
            current.clear()
        }
        for (sentence in sentences) {
            var rest = sentence
            while (rest.length > max) {
                // A very long sentence: break at a comma or space.
                val cut = rest.lastIndexOf(',', max).takeIf { it > max / 2 } ?: rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                flush()
                out.add(rest.substring(0, cut + 1).trim())
                rest = rest.substring(cut + 1)
            }
            if (current.length + rest.length + 1 > max) flush()
            current.append(rest).append(' ')
        }
        flush()
        return out.ifEmpty { listOf(text) }
    }

    private fun clip(voiceKey: String, text: String, tempName: String, fetch: () -> ByteArray): File {
        val cacheable = text.length <= CACHE_MAX_CHARS
        val dir = File(app.cacheDir, "voice").apply { mkdirs() }
        val cached = File(dir, "${voiceKey}_${text.hashCode().toUInt()}.audio")
        if (cacheable && cached.exists() && cached.length() > 0) return cached
        val audio = fetch()
        val out = if (cacheable) cached else File(dir, "$tempName.tmp")
        out.writeBytes(audio)
        return out
    }

    /** Clips of the current answer waiting to play, and whether each is the last one. */
    private val clips = ArrayDeque<Pair<File, Boolean>>()
    private var playingClip = false
    private var whenClipsEnd: (() -> Unit)? = null

    private fun enqueue(file: File, last: Boolean, id: String) {
        clips.addLast(file to last)
        if (!playingClip) playNext(id)
    }

    private fun playNext(id: String) {
        val next = clips.removeFirstOrNull()
        if (next == null) {
            playingClip = false
            whenClipsEnd?.let { whenClipsEnd = null; it() }
            return
        }
        playingClip = true
        play(next.first) {
            if (next.first.name.endsWith(".tmp")) next.first.delete()
            if (next.second) {
                playingClip = false
                finish(id)
            } else {
                playNext(id)
            }
        }
    }

    /** Runs [block] once the clips already queued have played (straight away if none are). */
    private fun afterClips(id: String, block: () -> Unit) {
        if (playingClip) whenClipsEnd = block else block()
    }

    private fun play(file: File, onEnd: () -> Unit) {
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
                onEnd()
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                onEnd()
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            releasePlayer()
            onEnd()
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
        clips.clear()
        playingClip = false
        whenClipsEnd = null
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
