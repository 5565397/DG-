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
 * Keeps Jarvis alive in the background: listens for the wake word, checks the voice
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
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    /** Speaking, verifying or executing — don't listen meanwhile. */
    private var busy = false
    /** The next thing heard is a command (no wake word needed). */
    private var awaitingCommand = false
    private var pendingCommand: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        processor = CommandProcessor(this)
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
        JarvisBus.status("Offline")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis listening", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Jarvis is online")
            .setContentText(if (wakeMode) "Say \"Jarvis\" followed by a command" else "Ready")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            true
        } catch (e: Exception) {
            JarvisBus.status("Couldn't start in the background. Open the app and try again.")
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
            JarvisBus.status("Speech recognition isn't available. Install or update the Google app.")
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
            JarvisBus.status(if (awaitingCommand) "Listening..." else "Waiting for \"Jarvis\"...", true)
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
            JarvisBus.status("Ready")
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
            handleCommand(heard[0])
            return
        }
        for (candidate in heard) {
            val command = WakeWord.extract(candidate) ?: continue
            if (command.isBlank()) {
                awaitingCommand = true
                say("Yes, ${prefs.title}?")
            } else {
                handleCommand(command)
            }
            return
        }
        // Heard something, but nobody called Jarvis.
        main.postDelayed({ startListening() }, 100)
    }

    override fun onError(error: Int) {
        listening = false
        if (busy) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wakeMode = false
            awaitingCommand = false
            JarvisBus.status("Microphone permission is missing.")
            stopSelf()
            return
        }
        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (awaitingCommand) {
            awaitingCommand = false
            say(if (silence) "I didn't catch that, ${prefs.title}." else "My hearing glitched. Try again, ${prefs.title}.")
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
            JarvisBus.status("Ready")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
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
        JarvisBus.status("Jarvis: $text")
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
            say("Yes, ${prefs.title}?")
            return
        }
        if (prefs.lockEnabled && prefs.hasVoiceprint() && !isUnlocked()) {
            pendingCommand = command
            busy = true
            stopListening()
            JarvisBus.status("Voice check: say anything after the beep")
            speaker.speak("Voice check, ${prefs.title}. Say your passphrase after the beep.") { verifyThenRun() }
            return
        }
        execute(command)
    }

    private fun verifyThenRun() {
        JarvisBus.status("Verifying voice...", true)
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
                    score < 0f -> say("I couldn't use the microphone for the voice check. Try again, ${prefs.title}.")
                    score >= prefs.threshold && command != null -> {
                        unlockedUntil = System.currentTimeMillis() + prefs.unlockMinutes * 60_000L
                        JarvisBus.status("Voice match (${"%.2f".format(score)}). Unlocked.")
                        execute(command)
                    }
                    else -> say("Voice not recognized. Access denied.")
                }
            }
        }.start()
    }

    private fun execute(command: String) {
        JarvisBus.status("Command: $command")
        val result = processor.handle(command)
        when (result.control) {
            CommandProcessor.Control.STOP_LISTENING -> {
                wakeMode = false
                awaitingCommand = false
            }
            CommandProcessor.Control.RELOCK -> unlockedUntil = 0L
            CommandProcessor.Control.NONE -> {}
        }
        say(result.reply, result.after)
    }
}
