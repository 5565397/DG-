package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
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
 * Keeps Voxa alive in the background: listens (for the wake word, or for one command),
 * checks the voice lock, runs the command, speaks the reply and writes everything to
 * the chat. Recovers on its own when the speech recognizer hangs, and steps aside when
 * a call or another app needs the microphone.
 */
class JarvisService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START_WAKE = "com.jarvis.assistant.START_WAKE"
        const val ACTION_LISTEN_ONCE = "com.jarvis.assistant.LISTEN_ONCE"
        const val ACTION_RUN_TEXT = "com.jarvis.assistant.RUN_TEXT"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val ACTION_RUN_FILE = "com.jarvis.assistant.RUN_FILE"
        /** End the conversation and go back to waiting for "Voxa". */
        const val ACTION_SLEEP = "com.jarvis.assistant.SLEEP"
        /** Run a command that was waiting for the phone to be unlocked. */
        const val ACTION_RUN_UNLOCKED = "com.jarvis.assistant.RUN_UNLOCKED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_FILE_MIME = "file_mime"

        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIF_ID = 7

        /** Give up on a recognizer that never answers. */
        private const val COMMAND_TIMEOUT_MS = 12_000L
        private const val WAKE_TIMEOUT_MS = 25_000L
        /** After speech ends, wait this long for the final text before using the live text. */
        private const val END_OF_SPEECH_GRACE_MS = 3_000L
        private const val MIC_RECHECK_MS = 2_500L
        /** After "Voxa", keep listening for more commands without the wake word for this long. */
        private const val ACTIVE_MS = 60_000L
        private const val WAKE_NOTIF_ID = 8

        @Volatile var isRunning = false
            private set
        @Volatile var wakeMode = false
            private set
        @Volatile private var unlockedUntil = 0L

        fun isUnlocked() = System.currentTimeMillis() < unlockedUntil

        /** A command said on the lock screen, waiting for the phone to be unlocked. */
        @Volatile var pendingUnlock: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var processor: CommandProcessor
    private lateinit var brain: AiBrain
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    /** Speaking, verifying, thinking or executing — don't listen meanwhile. */
    private var busy = false
    private var speaking = false
    private var thinking = false
    /** The next thing heard is a command (no wake word needed). */
    private var awaitingCommand = false
    private var pendingCommand: String? = null
    /** Listening for an optional follow-up after an AI answer — stay quiet if nobody speaks. */
    private var quietFollowUp = false
    /** The last input was typed, so don't open the mic for a follow-up. */
    private var lastInputTyped = false
    /** Bumped on every new input; late AI answers for older inputs are dropped. */
    private var turn = 0
    /** Waiting for a call or another app to release the microphone. */
    private var micPaused = false
    private var lastPartial = ""
    /** The user's latest words, so AI-derived commands keep details like "WhatsApp". */
    private var lastUserText = ""
    private var detectedTag: String? = null
    /** Woken with "Voxa": until this time, commands don't need the wake word. */
    private var activeUntil = 0L

    private val watchdog = Runnable { onRecognizerStuck() }
    private val endOfSpeech = Runnable { onEndOfSpeechTimeout() }
    private val micRecheck = Runnable { tryResumeAfterMic() }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val count = configs?.size ?: 0
            main.post {
                when {
                    // Someone besides us started recording while we wait for the wake word: step aside.
                    listening && !awaitingCommand && count >= 2 -> {
                        stopListening()
                        pauseForMic()
                    }
                    micPaused && count == 0 -> tryResumeAfterMic()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        processor = CommandProcessor(this)
        brain = AiBrain(this)
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioRecordingCallback(recordingCallback, main)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wakeMode = false
            prefs.wakeEnabled = false
            awaitingCommand = false
            goToSleep()
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted by Android after being killed: pick the wake word up again if it was on.
        val action = intent?.action ?: if (prefs.wakeEnabled) ACTION_START_WAKE else null
        if (!goForeground()) {
            if (action == ACTION_START_WAKE) postWakeUpNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START_WAKE -> {
                prefs.wakeEnabled = true
                getSystemService(NotificationManager::class.java).cancel(WAKE_NOTIF_ID)
                wakeMode = true
                awaitingCommand = false
                micPaused = false
                if (!busy) restartListening()
            }
            ACTION_LISTEN_ONCE -> onOrbTapped()
            ACTION_RUN_TEXT -> {
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    interrupt()
                    stopListening()
                    awaitingCommand = false
                    quietFollowUp = false
                    handleInput(text, typed = true)
                }
            }
            ACTION_RUN_FILE -> {
                val path = intent?.getStringExtra(EXTRA_FILE_PATH)
                if (path != null) {
                    interrupt()
                    stopListening()
                    awaitingCommand = false
                    quietFollowUp = false
                    val file = java.io.File(path)
                    analyzeFile(
                        FileAnalyzer.Attachment(path, intent?.getStringExtra(EXTRA_FILE_NAME) ?: file.name, intent?.getStringExtra(EXTRA_FILE_MIME) ?: "", file.length()),
                        intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                    )
                }
            }
            ACTION_SLEEP -> {
                interrupt()
                stopListening()
                goToSleep()
                resume()
            }
            ACTION_RUN_UNLOCKED -> {
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                pendingUnlock = null
                if (text.isNotBlank()) {
                    interrupt()
                    stopListening()
                    awaitingCommand = false
                    quietFollowUp = false
                    ChatStore.add(this, ChatStore.Role.NOTE, "Phone unlocked")
                    runCommand(text)
                }
            }
            else -> if (!wakeMode && !busy && !listening) stopSelf()
        }
        return if (wakeMode) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        wakeMode = false
        main.removeCallbacksAndMessages(null)
        try {
            audio.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        speaker.shutdown()
        JarvisBus.partial("")
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

    // ---------------------------------------------------------------- user actions

    /** Orb / mic tapped: interrupt whatever Voxa is doing and listen — or stop listening if already. */
    private fun onOrbTapped() {
        if (listening && awaitingCommand && !wakeMode) {
            stopListening()
            awaitingCommand = false
            JarvisBus.partial("")
            resume()
            return
        }
        interrupt()
        awaitingCommand = true
        quietFollowUp = false
        micPaused = false
        restartListening()
    }

    /** Stops speech and drops any AI answer still on its way. */
    private fun interrupt() {
        if (speaking) {
            speaker.stopSilently()
            speaking = false
        }
        if (thinking) {
            turn++
            thinking = false
        }
        if (pendingCommand == null) busy = false
    }

    // ---------------------------------------------------------------- listening

    private fun restartListening() {
        stopListening()
        main.postDelayed({ startListening() }, 150)
    }

    private fun recognizerIntent(): Intent {
        val auto = prefs.language == Lang.AUTO
        val primary = if (auto) "ur-PK" else prefs.language
        val all = arrayListOf("ur-PK", "en-US", "hi-IN")
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (auto) {
                // Lets Google's recognizer accept English and Hindi alongside Urdu.
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "hi-IN"))
                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, all)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, all)
                }
            }
        }
    }

    private fun startListening() {
        if (listening || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisBus.state(JarvisBus.State.IDLE, "Speech recognition isn't available. Install or update the Google app.")
            return
        }
        // While only waiting for the wake word, never fight a call or another app for the mic.
        if (!awaitingCommand && micBusyElsewhere()) {
            pauseForMic()
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        listening = true
        lastPartial = ""
        detectedTag = null
        try {
            rec.startListening(recognizerIntent())
            JarvisBus.state(JarvisBus.State.LISTENING, if (awaitingCommand) "Listening" else "Say \"Voxa\"")
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, if (awaitingCommand) COMMAND_TIMEOUT_MS else WAKE_TIMEOUT_MS)
        } catch (e: Exception) {
            listening = false
            resetRecognizer()
            if (wakeMode) main.postDelayed({ startListening() }, 1000)
            else resume()
        }
    }

    private fun stopListening() {
        listening = false
        main.removeCallbacks(watchdog)
        main.removeCallbacks(endOfSpeech)
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

    private fun micBusyElsewhere(): Boolean {
        val mode = audio.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return true
        return try {
            audio.activeRecordingConfigurations.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun pauseForMic() {
        micPaused = true
        JarvisBus.state(JarvisBus.State.IDLE, "Paused — the mic is in use")
        main.removeCallbacks(micRecheck)
        main.postDelayed(micRecheck, MIC_RECHECK_MS)
    }

    private fun tryResumeAfterMic() {
        if (!micPaused || busy || listening) return
        if (!wakeMode && !awaitingCommand) {
            micPaused = false
            return
        }
        if (micBusyElsewhere()) {
            main.removeCallbacks(micRecheck)
            main.postDelayed(micRecheck, MIC_RECHECK_MS)
            return
        }
        micPaused = false
        startListening()
    }

    /** The recognizer went quiet without results or an error — a known Android bug. */
    private fun onRecognizerStuck() {
        if (!listening) return
        val partial = lastPartial
        stopListening()
        resetRecognizer()
        if (partial.isNotBlank()) deliver(listOf(partial)) else afterNoResult(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }

    private fun onEndOfSpeechTimeout() {
        if (!listening) return
        val partial = lastPartial
        stopListening()
        resetRecognizer()
        if (partial.isNotBlank()) deliver(listOf(partial)) else afterNoResult(SpeechRecognizer.ERROR_NO_MATCH)
    }

    /** Continue whatever mode we're in after speaking / acting. */
    private fun resume() {
        // Still in a conversation started with "Voxa": listen for the next command, no wake word needed.
        if (wakeMode && !awaitingCommand && isActive() && !lastInputTyped) {
            awaitingCommand = true
            quietFollowUp = true
        }
        if (awaitingCommand || wakeMode) {
            startListening()
        } else {
            JarvisBus.state(JarvisBus.State.IDLE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- recognizer callbacks

    override fun onResults(results: Bundle?) {
        if (!listening) return
        listening = false
        main.removeCallbacks(watchdog)
        main.removeCallbacks(endOfSpeech)
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
        val list = heard.ifEmpty { if (lastPartial.isNotBlank()) listOf(lastPartial) else emptyList() }
        if (list.isEmpty()) {
            afterNoResult(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        deliver(list)
    }

    override fun onError(error: Int) {
        if (!listening) return
        listening = false
        main.removeCallbacks(watchdog)
        main.removeCallbacks(endOfSpeech)
        if (lastPartial.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            deliver(listOf(lastPartial))
            return
        }
        afterNoResult(error)
    }

    private fun afterNoResult(error: Int) {
        JarvisBus.partial("")
        if (busy) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wakeMode = false
            awaitingCommand = false
            JarvisBus.state(JarvisBus.State.OFFLINE, "Microphone permission is missing")
            ChatStore.add(this, ChatStore.Role.NOTE, "Microphone permission is missing. Open Settings → Setup.")
            stopSelf()
            return
        }
        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        val hardError = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_AUDIO
        if (hardError) resetRecognizer()
        if (awaitingCommand && quietFollowUp) {
            awaitingCommand = false
            quietFollowUp = false
            // Nobody spoke: the conversation is over, back to waiting for "Voxa".
            goToSleep()
            resume()
            return
        }
        if (awaitingCommand) {
            awaitingCommand = false
            if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                ChatStore.add(this, ChatStore.Role.NOTE, "No internet — Google's speech recognition needs a connection.")
            }
            say(Lang.get(prefs, if (silence) Lang.Line.DIDNT_CATCH else Lang.Line.GLITCH))
            return
        }
        if (wakeMode) {
            main.postDelayed({ startListening() }, if (hardError) 1200L else 200L)
        } else {
            resume()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!listening) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isBlank()) return
        lastPartial = text
        // Show live words only when they're meant for Voxa.
        if (awaitingCommand) JarvisBus.partial(text)
        else WakeWord.extract(text)?.let { JarvisBus.partial(it) }
    }

    override fun onLanguageDetection(results: Bundle) {
        detectedTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
    }

    override fun onEndOfSpeech() {
        main.removeCallbacks(endOfSpeech)
        main.postDelayed(endOfSpeech, END_OF_SPEECH_GRACE_MS)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) = JarvisBus.level(rmsdB)
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    /** Final text from the recognizer (several guesses, best first). */
    private fun deliver(heard: List<String>) {
        JarvisBus.partial("")
        if (awaitingCommand) {
            awaitingCommand = false
            quietFollowUp = false
            handleInput(heard[0], typed = false)
            return
        }
        for (candidate in heard) {
            val command = WakeWord.extract(candidate) ?: continue
            activate()
            if (command.isBlank()) {
                awaitingCommand = true
                say(Lang.get(prefs, Lang.Line.YES), log = false)
            } else {
                handleInput(command, typed = false)
            }
            return
        }
        // Heard something, but nobody called Voxa.
        main.postDelayed({ startListening() }, 100)
    }

    // ---------------------------------------------------------------- commands

    private fun handleInput(text: String, typed: Boolean) {
        turn++
        if (!typed && isActive()) activeUntil = System.currentTimeMillis() + ACTIVE_MS
        lastInputTyped = typed
        lastUserText = text
        val command = WakeWord.strip(text)
        Lang.noteInput(command.ifBlank { text }, if (typed) null else detectedTag)
        ChatStore.add(this, ChatStore.Role.USER, text, typed)
        if (command.isBlank()) {
            awaitingCommand = true
            say(Lang.get(prefs, Lang.Line.YES), log = false)
            return
        }
        // The voice lock guards spoken commands; typing already needs the phone unlocked.
        if (!typed && prefs.lockEnabled && prefs.hasVoiceprint() && !isUnlocked()) {
            pendingCommand = command
            busy = true
            speaking = true
            stopListening()
            JarvisBus.state(JarvisBus.State.SPEAKING, "Voice check")
            speaker.speak(Lang.get(prefs, Lang.Line.VOICE_CHECK)) {
                speaking = false
                verifyThenRun()
            }
            return
        }
        execute(command)
    }

    /** [chatText] is the fuller version for the chat when the spoken one is shortened. */
    private fun say(text: String, after: (() -> Unit)? = null, log: Boolean = true, chatText: String? = null) {
        busy = true
        stopListening()
        if (text.isBlank()) {
            main.postDelayed({
                busy = false
                after?.invoke()
                resume()
            }, 300)
            return
        }
        if (log) ChatStore.add(this, ChatStore.Role.VOXA, chatText ?: text)
        speaking = true
        JarvisBus.state(JarvisBus.State.SPEAKING)
        speaker.speak(text) {
            speaking = false
            busy = false
            after?.invoke()
            resume()
        }
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
                        ChatStore.add(this, ChatStore.Role.NOTE, "Voice verified")
                        execute(command)
                    }
                    else -> say(Lang.get(prefs, Lang.Line.VOICE_DENIED))
                }
            }
        }.start()
    }

    private fun execute(command: String) {
        // On the lock screen, anything personal waits until the phone is unlocked.
        if (lockedNow() && processor.needsUnlock(command)) {
            askUnlock(command)
            return
        }
        runCommand(command)
    }

    private fun runCommand(command: String) {
        JarvisBus.status(command)
        val result = processor.handle(command, context = lastUserText)
        applyControl(result)
        val question = result.ask
        when {
            question == null -> say(result.reply, result.after, chatText = result.display)
            // e.g. the briefing: speak the local part, then fetch the rest.
            result.reply.isNotBlank() -> say(result.reply, after = { think(question, result.withScreen, result.detailed) })
            else -> think(question, result.withScreen, result.detailed)
        }
    }

    private fun lockedNow(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Asks for the phone to be unlocked; the window runs [command] once it is. */
    private fun askUnlock(command: String) {
        pendingUnlock = command
        if (!JarvisBus.hasScreen) showWindow()
        JarvisBus.needUnlock(command)
        say(Lang.get(prefs, Lang.Line.UNLOCK_FIRST))
    }

    private fun isActive() = System.currentTimeMillis() < activeUntil

    /** "Voxa" was heard: start a conversation and show Voxa's window (on the lock screen too). */
    private fun activate() {
        activeUntil = System.currentTimeMillis() + ACTIVE_MS
        showWindow()
    }

    /** The conversation is over: close the window and wait for "Voxa" again. */
    private fun goToSleep() {
        activeUntil = 0L
        quietFollowUp = false
        JarvisBus.sleep()
    }

    private fun showWindow() {
        if (!prefs.lockWindow || MainActivity.visible || VoxaWindowActivity.open) return
        val intent = Intent(this, VoxaWindowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        // Android lets the bound phone-control service open screens from the background.
        val starter: android.content.Context = JarvisAccessibilityService.instance ?: this
        try {
            starter.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** Android didn't let the microphone start in the background (e.g. after a reboot): one tap wakes Voxa. */
    private fun postWakeUpNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voxa listening", NotificationManager.IMPORTANCE_LOW))
        }
        val wake = PendingIntent.getForegroundService(
            this, 2, Intent(this, JarvisService::class.java).setAction(ACTION_START_WAKE), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            WAKE_NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Voxa is asleep")
                .setContentText("Tap to turn \"Voxa\" listening back on")
                .setContentIntent(wake)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun applyControl(result: CommandProcessor.Result) {
        when (result.control) {
            CommandProcessor.Control.STOP_LISTENING -> {
                // With the wake word on, "sleep" just ends the conversation; "Voxa" wakes her again.
                awaitingCommand = false
                goToSleep()
            }
            CommandProcessor.Control.TURN_OFF -> {
                wakeMode = false
                prefs.wakeEnabled = false
                awaitingCommand = false
                goToSleep()
            }
            CommandProcessor.Control.RELOCK -> unlockedUntil = 0L
            CommandProcessor.Control.NEW_CHAT -> brain.forget()
            CommandProcessor.Control.NONE -> {}
        }
    }

    /** Runs a phone command that the AI translated (e.g. from Hindi or Urdu). */
    private fun runAiCommand(command: String) {
        JarvisBus.status(command)
        ChatStore.add(this, ChatStore.Role.NOTE, "Running: $command")
        if (lockedNow() && processor.needsUnlock(command)) {
            askUnlock(command)
            return
        }
        val result = processor.handle(command, context = lastUserText)
        applyControl(result)
        // Never bounce back to the AI a second time.
        if (result.ask != null) {
            say(Lang.get(prefs, Lang.Line.CANT_DO))
            return
        }
        say(result.reply, result.after)
    }

    private fun think(question: String, withScreen: Boolean = false, detailed: Boolean = false) {
        busy = true
        thinking = true
        stopListening()
        val myTurn = turn
        JarvisBus.state(JarvisBus.State.THINKING, Lang.get(prefs, Lang.Line.THINKING))
        val a11y = JarvisAccessibilityService.instance
        if (withScreen && a11y != null) {
            ChatStore.add(this, ChatStore.Role.NOTE, "Looking at your screen")
            // The visible text goes along with the screenshot, so even a model without vision knows what's there.
            val text = a11y.readScreen(maxChars = 2500)
            val withText = "This is a screenshot of my phone screen right now. $question" +
                if (text.isBlank()) "" else "\n\nVisible text on my screen:\n$text"
            a11y.captureScreen { jpeg ->
                if (myTurn != turn) return@captureScreen
                askBrain(withText, listOfNotNull(jpeg), myTurn)
            }
        } else {
            askBrain(question, emptyList(), myTurn, detailed)
        }
    }

    /** A file from the chat's + button: read it, then let the AI analyse it. */
    private fun analyzeFile(file: FileAnalyzer.Attachment, question: String) {
        turn++
        lastInputTyped = true
        if (question.isNotBlank()) {
            lastUserText = question
            Lang.noteInput(question)
        }
        ChatStore.add(this, ChatStore.Role.USER, question, typed = true, file = file)
        val online = brain.hasKey
        busy = true
        thinking = true
        val myTurn = turn
        JarvisBus.state(JarvisBus.State.THINKING, "Reading ${file.name}")
        Thread {
            val groq = if (online && prefs.groqKey.isNotBlank()) GroqClient(prefs) else null
            val maxChars = if (prefs.aiEngine == Prefs.ENGINE_GROQ) 14_000 else 60_000
            val prepared = FileAnalyzer.prepare(this, file, maxChars, groq)
            // Claude always sees pictures; with Groq it depends on the models this key has.
            var canSee = online
            if (online && prepared.error == null && prepared.images.isNotEmpty() && prefs.aiEngine == Prefs.ENGINE_GROQ) {
                canSee = try { groq?.hasVision() != false } catch (e: Exception) { true }
            }
            main.post {
                if (myTurn != turn) return@post
                if (prepared.error != null) {
                    thinking = false
                    busy = false
                    say(prepared.error)
                    return@post
                }
                if (!online) {
                    thinking = false
                    JarvisBus.state(JarvisBus.State.IDLE)
                    offlineFileAnswer(prepared)
                    return@post
                }
                if (prepared.truncated) ChatStore.add(this, ChatStore.Role.NOTE, "Long file — the first part was analysed")
                if (!canSee && prepared.images.isNotEmpty()) {
                    ChatStore.add(this, ChatStore.Role.NOTE, "This Groq key has no picture model — using the text and objects the phone read from the image")
                }
                val ask = question.ifBlank { "Analyse this file and tell me what's in it." }
                val prompt = buildString {
                    append(ask)
                    append("\n\nAttached file: ").append(file.name).append(" (").append(prepared.note.ifBlank { file.kind.name.lowercase() }).append(")")
                    if (prepared.images.isNotEmpty() && canSee) append(". ").append(prepared.images.size).append(" image(s) attached.")
                    if (prepared.images.isNotEmpty() && !canSee) {
                        append(". You can't see the image itself; use the on-device reading below and say so if it isn't enough.")
                    }
                    if (prepared.text.isNotBlank()) append("\n\nContent:\n").append(prepared.text)
                    if (prepared.seen.isNotBlank()) append("\n\nRead on the phone (OCR and object detection):\n").append(prepared.seen)
                }
                askBrain(prompt, if (canSee) prepared.images else emptyList(), myTurn, detailed = true)
            }
        }.start()
    }

    /** AI is off: show what the phone could read by itself (text in pictures, objects, document text). */
    private fun offlineFileAnswer(prepared: FileAnalyzer.Prepared) {
        val found = listOf(prepared.seen, prepared.text).filter { it.isNotBlank() }.joinToString("\n\n").take(3000)
        val lang = Lang.mode(prefs)
        if (found.isBlank()) {
            say(when (lang) {
                "ur" -> "اس فائل میں مجھے کوئی لکھائی نہیں ملی۔ تفصیل سے سمجھنے کے لیے اے آئی آن کیجیے۔"
                Lang.HINGLISH -> "Is file mein mujhe koi likhai nahi mili. Poori tafseel ke liye AI on kijiye."
                "hi" -> "इस फ़ाइल में मुझे कोई लिखावट नहीं मिली। पूरी तरह समझने के लिए AI चालू कीजिए।"
                else -> "I couldn't find any text in this file. Turn on the AI for a full description."
            })
            return
        }
        val spoken = when (lang) {
            "ur" -> "یہ فائل سے پڑھا گیا ہے۔ پوری تفصیل کے لیے اے آئی آن کیجیے۔"
            Lang.HINGLISH -> "Ye file se parha gaya hai. Poori tafseel ke liye AI on kijiye."
            "hi" -> "फ़ाइल से यह पढ़ा। पूरी जानकारी के लिए AI चालू कीजिए।"
            else -> "Here's what I could read on the phone. Turn on the AI for a full analysis."
        }
        say(spoken, chatText = "$found\n\n— $spoken")
    }

    private fun askBrain(question: String, images: List<String>, myTurn: Int, detailed: Boolean = false) {
        Thread {
            val reply = brain.ask(question, images, detailed = detailed)
            main.post {
                // The user moved on (typed or tapped again) — this answer is stale.
                if (myTurn != turn) return@post
                thinking = false
                busy = false
                val command = reply.command
                if (command != null) {
                    runAiCommand(command)
                    return@post
                }
                say(reply.speech, chatText = reply.display, after = {
                    // Listen once more for a spoken follow-up, without needing the wake word.
                    if (!reply.failed && !lastInputTyped) {
                        awaitingCommand = true
                        quietFollowUp = true
                    }
                })
            }
        }.start()
    }
}
