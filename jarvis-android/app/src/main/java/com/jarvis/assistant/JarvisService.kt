package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps Voxa alive in the background: listens for the wake word, checks the voice
 * lock, runs the command, and speaks the reply.
 */
class JarvisService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START_WAKE = "com.jarvis.assistant.START_WAKE"
        const val ACTION_LISTEN_ONCE = "com.jarvis.assistant.LISTEN_ONCE"
        const val ACTION_RUN_TEXT = "com.jarvis.assistant.RUN_TEXT"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIF_ID = 7

        @Volatile var isRunning = false
            private set
        @Volatile var wakeMode = false
            private set
        @Volatile private var unlockedUntil = 0L

        fun isUnlocked() = System.currentTimeMillis() < unlockedUntil
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var processor: CommandProcessor
    private lateinit var brain: AiBrain
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    /** Speaking, verifying or executing — don't listen meanwhile. */
    private var busy = false
    /** The next thing heard is a command (no wake word needed). */
    private var awaitingCommand = false
    private var pendingCommand: String? = null
    /** Listening for an optional follow-up after an AI answer — stay quiet if nobody speaks. */
    private var quietFollowUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        processor = CommandProcessor(this)
        brain = AiBrain(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wakeMode = false
            awaitingCommand = false
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_WAKE -> {
                wakeMode = true
                awaitingCommand = false
                if (!busy) restartListening()
            }
            ACTION_LISTEN_ONCE -> {
                awaitingCommand = true
                if (!busy) restartListening()
            }
            ACTION_RUN_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    stopListening()
                    handleCommand(text)
                }
            }
            else -> if (!wakeMode && !busy && !listening) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        wakeMode = false
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        speaker.shutdown()
        JarvisBus.state(JarvisBus.State.OFFLINE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voxa listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Voxa is online")
            .setContentText(if (wakeMode) "Say \"Voxa\" followed by a command" else "Ready")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            true
        } catch (e: Exception) {
            JarvisBus.state(JarvisBus.State.OFFLINE, "Couldn't start in the background. Open the app and try again.")
            false
        }
    }

    // ---------------------------------------------------------------- listening

    private fun restartListening() {
        stopListening()
        main.postDelayed({ startListening() }, 150)
    }

    private fun startListening() {
        if (listening || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisBus.state(JarvisBus.State.IDLE, "Speech recognition isn't available. Install or update the Google app.")
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        try {
            rec.startListening(intent)
            JarvisBus.state(JarvisBus.State.LISTENING, if (awaitingCommand) "Listening" else "Say \"Voxa\"")
        } catch (e: Exception) {
            listening = false
            resetRecognizer()
            if (wakeMode) main.postDelayed({ startListening() }, 1000)
        }
    }

    private fun stopListening() {
        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    /** Continue whatever mode we're in after speaking / acting. */
    private fun resume() {
        if (awaitingCommand || wakeMode) {
            startListening()
        } else {
            JarvisBus.state(JarvisBus.State.IDLE)
            stopSelf()
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.isEmpty()) {
            onError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        JarvisBus.heard(heard[0])

        if (awaitingCommand) {
            awaitingCommand = false
            quietFollowUp = false
            handleCommand(heard[0])
            return
        }
        for (candidate in heard) {
            val command = WakeWord.extract(candidate) ?: continue
            if (command.isBlank()) {
                awaitingCommand = true
                say(Lang.get(prefs, Lang.Line.YES))
            } else {
                handleCommand(command)
            }
            return
        }
        // Heard something, but nobody called Voxa.
        main.postDelayed({ startListening() }, 100)
    }

    override fun onError(error: Int) {
        listening = false
        if (busy) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wakeMode = false
            awaitingCommand = false
            JarvisBus.state(JarvisBus.State.OFFLINE, "Microphone permission is missing")
            stopSelf()
            return
        }
        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (awaitingCommand && quietFollowUp) {
            awaitingCommand = false
            quietFollowUp = false
            resume()
            return
        }
        if (awaitingCommand) {
            awaitingCommand = false
            say(Lang.get(prefs, if (silence) Lang.Line.DIDNT_CATCH else Lang.Line.GLITCH))
            return
        }
        if (wakeMode) {
            val hardError = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_NETWORK
            if (hardError) resetRecognizer()
            main.postDelayed({ startListening() }, if (hardError) 1200L else 200L)
        } else {
            JarvisBus.state(JarvisBus.State.IDLE)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) = JarvisBus.level(rmsdB)
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------------------------------------------------------- commands

    private fun say(text: String, after: (() -> Unit)? = null) {
        busy = true
        stopListening()
        if (text.isBlank()) {
            main.postDelayed({
                busy = false
                after?.invoke()
                resume()
            }, 400)
            return
        }
        JarvisBus.state(JarvisBus.State.SPEAKING)
        JarvisBus.reply(text)
        speaker.speak(text) {
            busy = false
            after?.invoke()
            resume()
        }
    }

    private fun handleCommand(raw: String) {
        val command = WakeWord.strip(raw)
        if (command.isBlank()) {
            awaitingCommand = true
            say(Lang.get(prefs, Lang.Line.YES))
            return
        }
        if (prefs.lockEnabled && prefs.hasVoiceprint() && !isUnlocked()) {
            pendingCommand = command
            busy = true
            stopListening()
            JarvisBus.state(JarvisBus.State.SPEAKING, "Voice check")
            speaker.speak(Lang.get(prefs, Lang.Line.VOICE_CHECK)) { verifyThenRun() }
            return
        }
        execute(command)
    }

    private fun verifyThenRun() {
        JarvisBus.state(JarvisBus.State.LISTENING, "Verifying your voice")
        Thread {
            val score = try {
                VoicePrint.verify(prefs)
            } catch (e: Exception) {
                -1f
            }
            main.post {
                busy = false
                val command = pendingCommand
                pendingCommand = null
                when {
                    score < 0f -> say(Lang.get(prefs, Lang.Line.MIC_FAIL))
                    score >= prefs.threshold && command != null -> {
                        unlockedUntil = System.currentTimeMillis() + prefs.unlockMinutes * 60_000L
                        JarvisBus.status("Voice verified")
                        execute(command)
                    }
                    else -> say(Lang.get(prefs, Lang.Line.VOICE_DENIED))
                }
            }
        }.start()
    }

    private fun execute(command: String) {
        JarvisBus.status(command)
        val result = processor.handle(command)
        applyControl(result)
        val question = result.ask
        when {
            question == null -> say(result.reply, result.after)
            // e.g. the briefing: speak the local part, then fetch the rest.
            result.reply.isNotBlank() -> say(result.reply) { think(question, result.withScreen) }
            else -> think(question, result.withScreen)
        }
    }

    private fun applyControl(result: CommandProcessor.Result) {
        when (result.control) {
            CommandProcessor.Control.STOP_LISTENING -> {
                wakeMode = false
                awaitingCommand = false
            }
            CommandProcessor.Control.RELOCK -> unlockedUntil = 0L
            CommandProcessor.Control.NEW_CHAT -> brain.forget()
            CommandProcessor.Control.NONE -> {}
        }
    }

    /** Runs a phone command that the AI translated (e.g. from Hindi or Urdu). */
    private fun runAiCommand(command: String) {
        JarvisBus.status(command)
        val result = processor.handle(command)
        applyControl(result)
        // Never bounce back to the AI a second time.
        if (result.ask != null) {
            say(Lang.get(prefs, Lang.Line.CANT_DO))
            return
        }
        say(result.reply, result.after)
    }

    private fun think(question: String, withScreen: Boolean = false) {
        busy = true
        stopListening()
        JarvisBus.state(JarvisBus.State.THINKING, Lang.get(prefs, Lang.Line.THINKING))
        val a11y = JarvisAccessibilityService.instance
        if (withScreen && a11y != null) {
            a11y.captureScreen { jpeg ->
                if (jpeg != null) {
                    askBrain(question, jpeg)
                } else {
                    // Older Android or capture failed: describe the screen from its text instead.
                    val text = a11y.readScreen(maxChars = 2500)
                    askBrain("$question\n\nVisible text on my screen:\n$text", null)
                }
            }
        } else {
            askBrain(question, null)
        }
    }

    private fun askBrain(question: String, screenJpeg: String?) {
        Thread {
            val reply = brain.ask(question, screenJpeg)
            main.post {
                busy = false
                val command = reply.command
                if (command != null) {
                    runAiCommand(command)
                    return@post
                }
                // Listen once more for a follow-up question, without needing the wake word.
                say(reply.speech) {
                    awaitingCommand = true
                    quietFollowUp = true
                }
            }
        }.start()
    }
}
