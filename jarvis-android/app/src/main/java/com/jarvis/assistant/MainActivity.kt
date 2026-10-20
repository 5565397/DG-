package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.animation.LinearInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), JarvisBus.Listener {

    private lateinit var prefs: Prefs
    private lateinit var ring: View
    private lateinit var btnMic: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtHeard: TextView
    private lateinit var inputCommand: EditText
    private lateinit var switchWake: SwitchCompat
    private lateinit var switchLock: SwitchCompat
    private lateinit var btnEnroll: Button
    private lateinit var btnTest: Button
    private lateinit var txtVoiceInfo: TextView
    private lateinit var txtThreshold: TextView
    private lateinit var seekThreshold: SeekBar
    private lateinit var btnPerms: Button
    private lateinit var btnAccess: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnWriteSettings: Button
    private lateinit var btnBattery: Button

    private var spin: ObjectAnimator? = null
    private var recording = false

    private val runtimePermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
            )
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            return list.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        ring = findViewById(R.id.ring)
        btnMic = findViewById(R.id.btnMic)
        txtStatus = findViewById(R.id.txtStatus)
        txtHeard = findViewById(R.id.txtHeard)
        inputCommand = findViewById(R.id.inputCommand)
        switchWake = findViewById(R.id.switchWake)
        switchLock = findViewById(R.id.switchLock)
        btnEnroll = findViewById(R.id.btnEnroll)
        btnTest = findViewById(R.id.btnTest)
        txtVoiceInfo = findViewById(R.id.txtVoiceInfo)
        txtThreshold = findViewById(R.id.txtThreshold)
        seekThreshold = findViewById(R.id.seekThreshold)
        btnPerms = findViewById(R.id.btnPerms)
        btnAccess = findViewById(R.id.btnAccess)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnWriteSettings = findViewById(R.id.btnWriteSettings)
        btnBattery = findViewById(R.id.btnBattery)

        btnMic.setOnClickListener {
            if (!hasMic()) {
                askPermissions()
                return@setOnClickListener
            }
            sendToJarvis(JarvisService.ACTION_LISTEN_ONCE)
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener { runTyped() }
        inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runTyped()
                true
            } else {
                false
            }
        }

        // ----- Voice lock -----
        switchLock.isChecked = prefs.lockEnabled
        switchLock.setOnCheckedChangeListener { _, on ->
            if (on && !prefs.hasVoiceprint()) {
                switchLock.isChecked = false
                toast("Record your voice first.")
                return@setOnCheckedChangeListener
            }
            prefs.lockEnabled = on
            updateVoiceInfo()
        }
        btnEnroll.setOnClickListener { enrollVoice() }
        btnTest.setOnClickListener { testVoice() }
        seekThreshold.progress = ((prefs.threshold - 0.50f) * 100).toInt().coerceIn(0, 48)
        updateThresholdLabel()
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.threshold = 0.50f + progress / 100f
                    updateThresholdLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ----- Setup -----
        btnPerms.setOnClickListener { askPermissions() }
        btnAccess.setOnClickListener {
            toast("Find \"Jarvis phone control\" in the list and turn it on.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnWriteSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ----- Preferences -----
        val inputTitle = findViewById<EditText>(R.id.inputTitle)
        val spinnerLanguage = findViewById<Spinner>(R.id.spinnerLanguage)
        spinnerLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, LANGUAGES.map { it.first }
        )
        spinnerLanguage.setSelection(LANGUAGES.indexOfFirst { it.second == prefs.language }.coerceAtLeast(0))
        findViewById<Button>(R.id.btnTtsVoices).setOnClickListener {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }
            toast("Download the Hindi and/or Urdu voice, then come back.")
        }
        val inputCountry = findViewById<EditText>(R.id.inputCountry)
        val inputUnlock = findViewById<EditText>(R.id.inputUnlock)
        val inputApiKey = findViewById<EditText>(R.id.inputApiKey)
        inputApiKey.setText(prefs.apiKey)
        inputTitle.setText(prefs.title)
        inputCountry.setText(prefs.countryCode)
        inputUnlock.setText(prefs.unlockMinutes.toString())
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.title = inputTitle.text.toString().trim().ifEmpty { "sir" }
            prefs.language = LANGUAGES[spinnerLanguage.selectedItemPosition.coerceIn(0, LANGUAGES.size - 1)].second
            updateVoiceStatus()
            if (JarvisService.isRunning && JarvisService.wakeMode) sendToJarvis(JarvisService.ACTION_START_WAKE)
            prefs.countryCode = inputCountry.text.toString().trim()
            prefs.apiKey = inputApiKey.text.toString().trim()
            prefs.unlockMinutes = inputUnlock.text.toString().trim().toIntOrNull()?.coerceIn(0, 720) ?: 5
            toast("Saved.")
        }

        findViewById<TextView>(R.id.txtHelp).text = HELP_TEXT
    }

    override fun onResume() {
        super.onResume()
        JarvisBus.listener = this
        restoreWakeSwitch()
        onStatus(if (JarvisService.isRunning) JarvisBus.lastStatus else "Offline", JarvisBus.lastListening)
        refreshSetup()
        updateVoiceInfo()
        updateVoiceStatus()
    }

    /** Tells the user whether the phone can actually speak the chosen language. */
    private fun updateVoiceStatus() {
        val view = findViewById<TextView>(R.id.txtVoiceStatus)
        val lang = prefs.language
        if (lang.startsWith("en")) {
            view.text = ""
            return
        }
        var probe: TextToSpeech? = null
        probe = TextToSpeech(applicationContext) { status ->
            val tts = probe
            val text = if (status != TextToSpeech.SUCCESS || tts == null) {
                "Couldn't check the phone's voices."
            } else {
                val hindi = tts.isLanguageAvailable(java.util.Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE
                val urdu = tts.isLanguageAvailable(java.util.Locale("ur", "PK")) >= TextToSpeech.LANG_AVAILABLE
                when {
                    lang.startsWith("ur") && urdu -> "\u2713 Urdu voice installed."
                    lang.startsWith("ur") && hindi -> "No Urdu voice on this phone, so Jarvis will speak Urdu with the Hindi voice (sounds almost the same). Tap below to install the Urdu voice."
                    lang.startsWith("hi") && hindi -> "\u2713 Hindi voice installed."
                    else -> "This phone has no Hindi/Urdu voice yet. Tap below and download it, or Jarvis will stay silent in this language."
                }
            }
            runOnUiThread { view.text = text }
            tts?.shutdown()
        }
    }

    override fun onPause() {
        JarvisBus.listener = null
        super.onPause()
    }

    private fun restoreWakeSwitch() {
        val on = JarvisService.isRunning && JarvisService.wakeMode
        switchWake.setOnCheckedChangeListener(null)
        switchWake.isChecked = on
        switchWake.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasMic()) {
                    switchWake.isChecked = false
                    askPermissions()
                    return@setOnCheckedChangeListener
                }
                sendToJarvis(JarvisService.ACTION_START_WAKE)
            } else if (JarvisService.isRunning) {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
            }
        }
    }

    // ---------------------------------------------------------------- JarvisBus

    override fun onStatus(text: String, listening: Boolean) {
        txtStatus.text = text
        setSpinning(listening)
        if (!JarvisService.isRunning && switchWake.isChecked) restoreWakeSwitch()
    }

    override fun onHeard(text: String) {
        txtHeard.text = "“$text”"
    }

    private fun setSpinning(on: Boolean) {
        if (on) {
            if (spin == null) {
                spin = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                    duration = 2400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
            btnMic.text = "LISTENING"
        } else {
            spin?.cancel()
            spin = null
            btnMic.text = "TAP\nTO SPEAK"
        }
    }

    // ---------------------------------------------------------------- actions

    private fun runTyped() {
        val text = inputCommand.text.toString().trim()
        if (text.isEmpty()) return
        inputCommand.setText("")
        txtHeard.text = "“$text”"
        sendToJarvis(JarvisService.ACTION_RUN_TEXT, text)
    }

    private fun sendToJarvis(action: String, text: String? = null) {
        val intent = Intent(this, JarvisService::class.java).setAction(action)
        if (text != null) intent.putExtra(JarvisService.EXTRA_TEXT, text)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() = permissionLauncher.launch(runtimePermissions)

    private fun refreshSetup() {
        val allGranted = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        btnPerms.text = mark(allGranted, "1. Grant microphone, contacts, calls, SMS")
        btnAccess.text = mark(JarvisAccessibilityService.instance != null, "2. Enable phone control (Accessibility)")
        btnOverlay.text = mark(Settings.canDrawOverlays(this), "3. Allow display over other apps")
        btnWriteSettings.text = mark(Settings.System.canWrite(this), "4. Allow brightness control")
        val pm = getSystemService(PowerManager::class.java)
        btnBattery.text = mark(pm.isIgnoringBatteryOptimizations(packageName), "5. Keep Jarvis running (battery)")
    }

    private fun mark(ok: Boolean, label: String) = if (ok) "✓  $label" else label

    // ---------------------------------------------------------------- voice lock

    private fun updateThresholdLabel() {
        txtThreshold.text = "Strictness: %.2f (higher = stricter)".format(prefs.threshold)
    }

    private fun updateVoiceInfo() {
        if (recording) return
        txtVoiceInfo.text = when {
            !prefs.hasVoiceprint() -> "No voice recorded yet."
            prefs.lockEnabled -> "Voice recorded ✓  Lock is ON. Jarvis checks your voice, then stays unlocked for ${prefs.unlockMinutes} min."
            else -> "Voice recorded ✓  Lock is OFF."
        }
    }

    private fun stopJarvisForMic() {
        if (JarvisService.isRunning) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        }
        restoreWakeSwitchOff()
    }

    private fun restoreWakeSwitchOff() {
        switchWake.setOnCheckedChangeListener(null)
        switchWake.isChecked = false
        restoreWakeSwitch()
    }

    private fun setVoiceButtons(enabled: Boolean) {
        recording = !enabled
        btnEnroll.isEnabled = enabled
        btnTest.isEnabled = enabled
        btnMic.isEnabled = enabled
    }

    private fun enrollVoice() {
        if (!hasMic()) {
            askPermissions()
            return
        }
        stopJarvisForMic()
        setVoiceButtons(false)
        Thread {
            val samples = ArrayList<FloatArray>()
            var attempts = 0
            while (samples.size < 3 && attempts < 6) {
                attempts++
                val n = samples.size + 1
                runOnUiThread {
                    txtVoiceInfo.text = "Sample $n of 3 — after the beep, say:\n\"Jarvis, this is my voice. Unlock.\""
                }
                Thread.sleep(1300)
                VoicePrint.beep()
                runOnUiThread { txtVoiceInfo.text = "Recording sample $n... speak now" }
                val f = try {
                    VoicePrint.features(VoicePrint.record())
                } catch (e: Exception) {
                    null
                }
                if (f == null) {
                    runOnUiThread { txtVoiceInfo.text = "I didn't hear enough speech. Let's try that one again." }
                    Thread.sleep(1500)
                } else {
                    samples.add(f)
                }
            }
            runOnUiThread {
                setVoiceButtons(true)
                if (samples.size == 3) {
                    prefs.voiceprints = samples
                    val selfScore = samples.indices.map { i ->
                        VoicePrint.score(samples[i], samples.filterIndexed { j, _ -> j != i })
                    }.average()
                    txtVoiceInfo.text = "Voice recorded ✓  Your samples match each other at %.2f. Now turn on the lock.".format(selfScore)
                    if (selfScore < prefs.threshold) {
                        prefs.threshold = (selfScore.toFloat() - 0.03f).coerceIn(0.50f, 0.98f)
                        seekThreshold.progress = ((prefs.threshold - 0.50f) * 100).toInt().coerceIn(0, 48)
                        updateThresholdLabel()
                    }
                } else {
                    txtVoiceInfo.text = "Recording failed. Find a quieter place and try again."
                }
            }
        }.start()
    }

    private fun testVoice() {
        if (!hasMic()) {
            askPermissions()
            return
        }
        if (!prefs.hasVoiceprint()) {
            toast("Record your voice first.")
            return
        }
        stopJarvisForMic()
        setVoiceButtons(false)
        txtVoiceInfo.text = "After the beep, say the passphrase..."
        Thread {
            Thread.sleep(900)
            val score = try {
                VoicePrint.verify(prefs)
            } catch (e: Exception) {
                -1f
            }
            runOnUiThread {
                setVoiceButtons(true)
                txtVoiceInfo.text = when {
                    score < 0f -> "Microphone busy. Try again."
                    score == 0f -> "I didn't hear enough speech."
                    score >= prefs.threshold -> "Score %.2f ≥ %.2f  →  MATCH ✓".format(score, prefs.threshold)
                    else -> "Score %.2f < %.2f  →  REJECTED ✗".format(score, prefs.threshold)
                }
            }
        }.start()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        /** Spinner label to speech-recognition language tag. */
        private val LANGUAGES = listOf(
            "English (US)" to "en-US",
            "English (India / Pakistan accent)" to "en-IN",
            "English (UK)" to "en-GB",
            "\u0939\u093F\u0928\u094D\u0926\u0940  Hindi" to "hi-IN",
            "\u0627\u0631\u062F\u0648  Urdu" to "ur-PK"
        )

        private val HELP_TEXT = """
            THINGS YOU CAN SAY
            (start with "Jarvis" when the wake word is on)

            HINDI / URDU: pick the language in Preferences. Then e.g. "Jarvis, टॉर्च जलाओ", "Jarvis, آواز بڑھاؤ", "YouTube kholo", "अरिजीत के गाने यूट्यूब पर चलाओ", "وقت کیا ہوا ہے". With the AI key, Jarvis understands anything in Hindi/Urdu and answers in the same language.

            ASK ANYTHING (needs a Claude API key): "Who won the match last night?", "Explain black holes simply", "Tell me a joke", "What should I cook tonight?" — then just ask a follow-up, no wake word needed. "New conversation" clears its memory.

            APPS: "Open WhatsApp", "Launch Instagram", "Open settings"
            CALLS: "Call Mom", "Call 0300 1234567"
            SMS: "Send a message to Ali saying I'm on my way"
            WHATSAPP: "WhatsApp Sara saying see you at 8"
            YOUTUBE: "Play Believer on YouTube", "Play lo-fi music"
            WEB: "Search for best pizza near me", "Who is Tony Stark"
            MAPS: "Navigate to the airport", "Show Times Square on the map"
            ALARM / TIMER: "Set an alarm for 6:30 am", "Set a timer for 10 minutes"
            FLASHLIGHT: "Turn on the flashlight", "Flashlight off"
            VOLUME: "Volume up", "Set volume to 40 percent", "Mute"
            MEDIA: "Pause", "Next song", "Resume music"
            BRIGHTNESS: "Brightness 70 percent", "Dim the screen"
            TOGGLES: "Turn on Bluetooth", "Turn off Wi-Fi", "Enable airplane mode", "Turn on hotspot"
            SCREEN: "Go home", "Go back", "Recent apps", "Notifications", "Quick settings"
            CONTROL: "Scroll down", "Click Sign in", "Tap Subscribe", "Type hello there", "Read the screen"
            SYSTEM: "Take a screenshot", "Lock the phone", "Power menu"
            INFO: "What time is it", "What's the date", "Battery level"
            CAMERA: "Take a photo", "Take a selfie"
            JARVIS: "Lock yourself", "Stop listening", "Help"
        """.trimIndent()
    }
}
