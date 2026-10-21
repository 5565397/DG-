package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale

/** Everything configurable, grouped in quiet cards. Rebuilt on every resume so it always shows the truth. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout

    private var voiceBusy = false
    private var voiceMessage: String? = null
    private var aiMessage: String? = null
    private var speechMessage: String? = null
    /** For trying voices here; the real Voxa voice lives in the background service. */
    private val previewSpeaker by lazy { Speaker(this) }
    private var previewStarted = false
    private var hindiVoice: Boolean? = null
    private var urduVoice: Boolean? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        content = findViewById(R.id.content)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        probeVoices()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // =================================================================
    // Building the page
    // =================================================================

    private fun rebuild() {
        if (isFinishing) return
        content.removeAllViews()
        buildSetup()
        buildLanguage()
        buildVoice()
        buildVoiceLock()
        buildAi()
        buildPersonal()
        buildMemory()
        buildHelp()
        footer()
    }

    private fun buildSetup() {
        val done = Setup.doneCount(this)
        val total = Setup.Step.values().size
        val card = section(if (done == total) "SETUP · COMPLETE" else "SETUP · $done OF $total")
        Setup.Step.values().forEach { step ->
            val ok = Setup.isDone(this, step)
            row(card, step.title, step.subtitle, value = if (ok) null else "Set up", icon = if (ok) R.drawable.ic_check else R.drawable.ic_chevron, iconTint = if (ok) R.color.success else R.color.text_tertiary) {
                if (step == Setup.Step.PERMISSIONS && !ok) permissionLauncher.launch(Setup.runtimePermissions())
                else {
                    if (step == Setup.Step.PHONE_CONTROL) toast("Find \"Voxa phone control\" and turn it on.")
                    if (step == Setup.Step.NOTIFICATIONS) toast("Turn on \"Voxa notifications\".")
                    if (step == Setup.Step.ASSISTANT) toast("Tap \"Digital assistant app\" and choose Voxa.")
                    if (step == Setup.Step.LOCK_WINDOW) toast("Allow Voxa to show full-screen.")
                    Setup.open(this, step)
                }
            }
        }
    }

    private fun buildLanguage() {
        val card = section("LANGUAGE")
        val current = LANGUAGES.firstOrNull { it.second == prefs.language } ?: LANGUAGES[0]
        row(card, "Speak with Voxa in", null, value = current.first) {
            val labels = LANGUAGES.map { it.first }.toTypedArray()
            val checked = LANGUAGES.indexOf(current)
            MaterialAlertDialogBuilder(this)
                .setTitle("Language")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    prefs.language = LANGUAGES[which].second
                    dialog.dismiss()
                    restartWakeIfRunning()
                    rebuild()
                }
                .show()
        }
        val styles = listOf(
            Prefs.STYLE_HINGLISH to "Hinglish — Roman Urdu (\"Theek hai sir, kar diya\")",
            Prefs.STYLE_URDU to "Urdu — اردو (\"ٹھیک ہے سر، کر دیا\")",
            Prefs.STYLE_SAME to "Same as I speak"
        )
        val style = styles.firstOrNull { it.first == prefs.replyStyle } ?: styles[0]
        row(card, "Voxa answers in", "She understands Urdu, Hindi and English whichever you pick.", value = style.second.substringBefore(" —")) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Voxa answers in")
                .setSingleChoiceItems(styles.map { it.second }.toTypedArray(), styles.indexOf(style)) { dialog, which ->
                    prefs.replyStyle = styles[which].first
                    dialog.dismiss()
                    rebuild()
                }
                .show()
        }
        if (!prefs.replyHinglish && (prefs.replyStyle == Prefs.STYLE_URDU || !prefs.language.startsWith("en"))) {
            val wantUrdu = prefs.replyStyle == Prefs.STYLE_URDU || prefs.language.startsWith("ur") || prefs.language == Lang.AUTO
            val hasVoice = if (wantUrdu) (urduVoice == true || hindiVoice == true) else hindiVoice == true
            val subtitle = when {
                hindiVoice == null -> "Checking the phone's voices…"
                wantUrdu && urduVoice == true -> "Urdu voice installed."
                wantUrdu && hindiVoice == true -> "No Urdu voice, so Voxa speaks Urdu with the Hindi voice. They sound almost the same."
                hasVoice -> "Hindi voice installed."
                else -> "No voice for this language yet. Tap to download it, or Voxa stays silent."
            }
            row(card, "Voice for this language", subtitle, icon = if (hasVoice) R.drawable.ic_check else R.drawable.ic_chevron, iconTint = if (hasVoice) R.color.success else R.color.text_tertiary) {
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (e: Exception) {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }
            }
        }
    }

    private fun buildVoice() {
        if (!previewStarted) {
            previewStarted = true
            previewSpeaker // start the phone's speech engine early so its voices are ready to list
        }
        val card = section("VOICE")
        val engine = prefs.voiceEngine
        val human = engine == Prefs.VOICE_ELEVEN
        val groq = engine == Prefs.VOICE_GROQ
        row(
            card, "Voxa's voice",
            when {
                groq -> "Natural Groq voice with your Groq key. It speaks English letters (Hinglish, English); Urdu script is read by the phone's Urdu voice."
                human -> "Human-like ElevenLabs voice. Needs internet; falls back to the phone voice without it."
                else -> "The phone's own voice: free, offline, and the one that speaks Urdu script."
            },
            value = when {
                groq -> "Groq"
                human -> "ElevenLabs"
                else -> "Phone"
            }
        ) {
            val options = listOf(
                Prefs.VOICE_GROQ to "Natural · Groq (your Groq key)",
                Prefs.VOICE_ELEVEN to "Human-like · ElevenLabs (own key)",
                Prefs.VOICE_PHONE to "Phone voice · free, offline, Urdu"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle("Voxa's voice")
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == engine }) { dialog, which ->
                    dialog.dismiss()
                    when (options[which].first) {
                        Prefs.VOICE_PHONE -> {
                            prefs.voiceEngine = Prefs.VOICE_PHONE
                            rebuild()
                        }
                        Prefs.VOICE_GROQ -> if (prefs.groqKey.isBlank()) {
                            keyDialog("Groq API key", "gsk_…", "") { key ->
                                if (key.isNotBlank()) {
                                    prefs.groqKey = key
                                    prefs.voiceEngine = Prefs.VOICE_GROQ
                                }
                                rebuild()
                            }
                        } else {
                            prefs.voiceEngine = Prefs.VOICE_GROQ
                            speechMessage = null
                            rebuild()
                        }
                        else -> if (prefs.elevenKey.isBlank()) {
                            askElevenKey { prefs.voiceEngine = Prefs.VOICE_ELEVEN }
                        } else {
                            prefs.voiceEngine = Prefs.VOICE_ELEVEN
                            rebuild()
                        }
                    }
                }
                .show()
        }
        if (groq) {
            val current = GroqClient.FEMALE_VOICES.firstOrNull { it.first == prefs.groqVoice }?.second?.substringBefore(" ·") ?: prefs.groqVoice
            row(card, "Female voice", "Autumn, Diana or Hannah — each one plays a sample.", value = current) {
                val voices = GroqClient.FEMALE_VOICES
                MaterialAlertDialogBuilder(this)
                    .setTitle("Choose Voxa's voice")
                    .setSingleChoiceItems(voices.map { it.second }.toTypedArray(), voices.indexOfFirst { it.first == prefs.groqVoice }) { _, which ->
                        prefs.groqVoice = voices[which].first
                        previewSpeaker.stopSilently()
                        previewSpeaker.speak(sampleLine())
                    }
                    .setPositiveButton("Use this voice") { _, _ -> rebuild() }
                    .setOnDismissListener { previewSpeaker.stopSilently(); rebuild() }
                    .show()
            }
        } else         if (human) {
            row(card, "ElevenLabs API key", "Free at elevenlabs.io → your profile → API keys. Kept only on this phone.", value = mask(prefs.elevenKey)) {
                askElevenKey {}
            }
            row(card, "Female voice", "Pick from your ElevenLabs voices; each one plays a sample.", value = prefs.elevenVoiceName) {
                pickElevenVoice()
            }
        } else {
            val chosen = prefs.phoneVoice
            row(card, "Phone voice", "Tap a voice to hear it, then keep the most natural one.", value = if (chosen.isBlank()) "Best" else "Custom") {
                pickPhoneVoice()
            }
            row(card, "Get better phone voices", "Install the high-quality voices from \"Speech Services by Google\".") {
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (e: Exception) {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }
            }
        }
        row(card, "Test the voice", speechMessage ?: "Hear how Voxa sounds now.") { testSpeech() }
    }

    private fun sampleLine(): String = when {
        // Groq's voice reads English letters, so it always gets the Roman sample.
        prefs.replyHinglish || prefs.voiceEngine == Prefs.VOICE_GROQ -> "Assalam o Alaikum, main Voxa hoon. Bataiye, aaj main aapki kya madad karun?"
        Lang.mode(prefs) == "ur" -> "السلام علیکم، میں ووکسا ہوں۔ بتائیے، آج میں آپ کی کیا مدد کروں؟"
        else -> "Hello, I'm Voxa. How can I help you today?"
    }

    private fun askElevenKey(after: () -> Unit) {
        keyDialog("ElevenLabs API key", "sk_…", prefs.elevenKey) { key ->
            if (key.isNotBlank()) {
                prefs.elevenKey = key
                after()
                speechMessage = null
            }
            rebuild()
        }
    }

    private fun pickElevenVoice() {
        val key = prefs.elevenKey
        if (key.isBlank()) {
            askElevenKey {}
            return
        }
        toast("Loading your ElevenLabs voices…")
        Thread {
            val voices = try {
                ElevenLabs.voices(key)
            } catch (e: ElevenLabs.ElevenException) {
                runOnUiThread { toast(elevenError(e)) }
                return@Thread
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't reach ElevenLabs. Check the internet.") }
                return@Thread
            }
            runOnUiThread {
                if (voices.isEmpty()) {
                    toast("No voices on this ElevenLabs account.")
                    return@runOnUiThread
                }
                val labels = voices.map { v -> if (v.description.isBlank()) v.name else "${v.name} — ${v.description}" }.toTypedArray()
                val current = voices.indexOfFirst { it.id == prefs.elevenVoiceId }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Choose Voxa's voice")
                    .setSingleChoiceItems(labels, current) { _, which ->
                        // Stay open: each tap plays a sample, "Use this voice" keeps it.
                        prefs.elevenVoiceId = voices[which].id
                        prefs.elevenVoiceName = voices[which].name
                        previewSpeaker.stopSilently()
                        previewSpeaker.speak(sampleLine())
                    }
                    .setPositiveButton("Use this voice") { _, _ -> rebuild() }
                    .setOnDismissListener { previewSpeaker.stopSilently(); rebuild() }
                    .show()
            }
        }.start()
    }

    private fun pickPhoneVoice() {
        val voices = previewSpeaker.voiceChoices()
        if (voices.isEmpty()) {
            toast("The phone's voices are still loading — try again in a moment.")
            return
        }
        val labels = (listOf("Automatic — the best one") + voices.mapIndexed { i, v ->
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very high quality"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
                else -> "standard"
            }
            "Voice ${i + 1} · ${v.locale.displayName} · $quality${if (v.isNetworkConnectionRequired) " · online" else ""}"
        }).toTypedArray()
        val current = voices.indexOfFirst { it.name == prefs.phoneVoice } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Phone voice")
            .setSingleChoiceItems(labels, current) { _, which ->
                prefs.phoneVoice = if (which == 0) "" else voices[which - 1].name
                previewSpeaker.stopSilently()
                previewSpeaker.speak(sampleLine())
            }
            .setPositiveButton("Use this voice") { _, _ -> rebuild() }
            .setOnDismissListener { previewSpeaker.stopSilently(); rebuild() }
            .show()
    }

    private fun testSpeech() {
        if (!prefs.humanVoice) {
            speechMessage = null
            previewSpeaker.speak(sampleLine())
            return
        }
        val groqVoice = prefs.voiceEngine == Prefs.VOICE_GROQ
        speechMessage = if (groqVoice) "Contacting Groq…" else "Contacting ElevenLabs…"
        rebuild()
        Thread {
            val error = if (groqVoice) try {
                GroqClient(prefs).speech("OK", prefs.groqVoice)
                null
            } catch (e: GroqClient.GroqException) {
                when {
                    e.code == 401 -> "Groq rejected the key. Check it in AI Brain."
                    e.message.orEmpty().contains("terms", true) ->
                        "Groq needs you to accept the voice model's terms once: open console.groq.com, Playground, pick \"orpheus-v1-english\" and accept."
                    e.code == 429 -> "Groq's voice limit is reached for now. Try again later."
                    else -> "Groq voice error ${e.code}. ${e.message.orEmpty()}"
                }
            } catch (e: Exception) {
                "Couldn't reach Groq. Check the internet."
            } else try {
                ElevenLabs.speech(prefs.elevenKey, prefs.elevenVoiceId, "OK")
                null
            } catch (e: ElevenLabs.ElevenException) {
                elevenError(e)
            } catch (e: Exception) {
                "Couldn't reach ElevenLabs. Check the internet."
            }
            runOnUiThread {
                speechMessage = error ?: "Connected ✓  ${if (groqVoice) prefs.groqVoice.replaceFirstChar { it.uppercase() } else prefs.elevenVoiceName} is speaking."
                rebuild()
                previewSpeaker.speak(sampleLine())
            }
        }.start()
    }

    private fun elevenError(e: ElevenLabs.ElevenException) = when (e.code) {
        401 -> "ElevenLabs rejected the key. Copy it again from elevenlabs.io."
        402, 429 -> "ElevenLabs limit reached: ${e.message.orEmpty().ifBlank { "no characters left this month" }}"
        else -> "ElevenLabs error ${e.code}. ${e.message.orEmpty()}"
    }

    override fun onDestroy() {
        if (previewStarted) previewSpeaker.shutdown()
        super.onDestroy()
    }

    private fun buildVoiceLock() {
        val card = section("VOICE LOCK")
        switchRow(card, "Only obey my voice", "Voxa checks your voice before acting, then stays unlocked for ${prefs.unlockMinutes} min.", prefs.lockEnabled) { on ->
            if (on && !prefs.hasVoiceprint()) {
                toast("Record your voice first.")
                false
            } else {
                prefs.lockEnabled = on
                true
            }
        }
        val recorded = prefs.hasVoiceprint()
        row(card, if (recorded) "Re-record my voice" else "Record my voice", voiceMessage ?: if (recorded) "Voice recorded." else "Three short samples, about 15 seconds.") {
            if (!voiceBusy) enrollVoice()
        }
        row(card, "Test a voice", "Check yours, then let a friend try theirs.") {
            if (!voiceBusy) testVoice()
        }
        sliderRow(card)
    }

    private fun buildAi() {
        val card = section("AI BRAIN")
        val engine = prefs.aiEngine
        val groqOn = engine == Prefs.ENGINE_GROQ
        switchRow(
            card, "Online AI · Groq",
            if (groqOn) "On — Voxa answers anything through Groq, in Urdu, Hindi or English."
            else "Off — Voxa works offline with its built-in Urdu, Hindi and English commands.",
            groqOn
        ) { on ->
            when {
                on && prefs.groqKey.isBlank() -> {
                    keyDialog("Groq API key", "gsk_…", "") { key ->
                        if (key.isNotBlank()) {
                            prefs.groqKey = key
                            prefs.aiEngine = Prefs.ENGINE_GROQ
                        }
                        rebuild()
                    }
                    false
                }
                on -> { prefs.aiEngine = Prefs.ENGINE_GROQ; aiMessage = null; rebuild(); true }
                else -> { prefs.aiEngine = Prefs.ENGINE_OFFLINE; aiMessage = null; rebuild(); true }
            }
        }
        row(card, "Groq API key", "Free key from console.groq.com. Saved only on this phone.", value = mask(prefs.groqKey)) {
            keyDialog("Groq API key", "gsk_…", prefs.groqKey) {
                prefs.groqKey = it
                prefs.groqModels = emptyList()
                aiMessage = null
                // A new key means the user wants Groq: switch it on instead of leaving AI off.
                if (it.isNotBlank() && prefs.aiEngine == Prefs.ENGINE_OFFLINE) {
                    prefs.aiEngine = Prefs.ENGINE_GROQ
                    toast("Groq AI is on.")
                }
                rebuild()
            }
        }
        if (prefs.groqKey.isNotBlank()) {
            row(card, "Groq model", "Automatic picks the best model your key can use.", value = prefs.groqModel.ifBlank { "Automatic" }) { chooseGroqModel() }
        }
        row(card, "AI engine", "Offline, Groq, or Claude (needs its own key).", value = ENGINES.first { it.second == engine }.first) {
            val labels = ENGINES.map { it.first }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("AI engine")
                .setSingleChoiceItems(labels, ENGINES.indexOfFirst { it.second == engine }) { dialog, which ->
                    prefs.aiEngine = ENGINES[which].second
                    aiMessage = null
                    dialog.dismiss()
                    rebuild()
                }
                .show()
        }
        if (engine == Prefs.ENGINE_CLAUDE || prefs.apiKey.isNotBlank()) {
            row(card, "Claude API key", "From console.anthropic.com. Adds live web search.", value = mask(prefs.apiKey)) {
                keyDialog("Claude API key", "sk-ant-…", prefs.apiKey) {
                    prefs.apiKey = it
                    aiMessage = null
                    rebuild()
                }
            }
        }
        if (prefs.aiActive) {
            row(card, "Test connection", aiMessage ?: "Sends one tiny request.") { testAi() }
        }
        row(card, "Home city", "For weather, news and anything local.", value = prefs.city.ifBlank { "Not set" }) {
            textDialog("Home city", "e.g. Lahore", prefs.city, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                prefs.city = it
                rebuild()
            }
        }
    }

    private fun buildPersonal() {
        val card = section("PERSONAL")
        row(card, "What Voxa calls you", null, value = prefs.title) {
            textDialog("What should Voxa call you?", "sir", prefs.title, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                prefs.title = it.ifBlank { "sir" }
                rebuild()
            }
        }
        row(card, "WhatsApp country code", "Added to local numbers when sending WhatsApp messages.", value = prefs.countryCode.ifBlank { "None" }.let { if (it == "None") it else "+$it" }) {
            textDialog("Country code", "92", prefs.countryCode, InputType.TYPE_CLASS_NUMBER) {
                prefs.countryCode = it.filter { c -> c.isDigit() }
                rebuild()
            }
        }
        row(card, "Stay unlocked for", "After a voice check.", value = "${prefs.unlockMinutes} min") {
            textDialog("Minutes", "5", prefs.unlockMinutes.toString(), InputType.TYPE_CLASS_NUMBER) {
                prefs.unlockMinutes = it.toIntOrNull()?.coerceIn(0, 720) ?: 5
                rebuild()
            }
        }
        row(card, "Smart button & power button", "Make Voxa the default assistant (Setup above). Then set your phone's smart / assistant key, or holding the power button, to \"Assistant\" — or pick the app \"Talk to Voxa\" for the key. There's also a Voxa tile in quick settings.") {
            Setup.open(this, Setup.Step.ASSISTANT)
        }
        switchRow(card, "Banking mode", "When a banking or payment app opens, Voxa pauses phone control so the bank app works. A notification lets you turn it back on.", prefs.bankingPause) { on ->
            prefs.bankingPause = on
            true
        }
        switchRow(card, "Voxa window on the lock screen", "Say \"Voxa\" and her window opens over any app or the lock screen. Personal things wait for you to unlock.", prefs.lockWindow) { on ->
            prefs.lockWindow = on
            true
        }
        switchRow(card, "Announce new messages", "Voxa reads incoming WhatsApp, SMS and chat messages aloud.", prefs.announceMessages) { on ->
            prefs.announceMessages = on
            if (on && !Setup.isDone(this, Setup.Step.NOTIFICATIONS)) {
                toast("Turn on \"Voxa notifications\".")
                Setup.open(this, Setup.Step.NOTIFICATIONS)
            }
            true
        }
    }

    private fun buildMemory() {
        val card = section("MEMORY & CHAT")
        val chatCount = ChatStore.sessions(this).size
        row(card, "Chat history", if (chatCount == 0) "No saved chats yet." else "$chatCount conversations saved on this phone.") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        row(card, "Delete all chat history", "Removes every saved conversation and attached file from this phone.") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete all chats?")
                .setMessage("Every saved conversation and attached file is removed from this phone. Voxa also starts a fresh AI conversation.")
                .setPositiveButton("Delete") { _, _ ->
                    ChatStore.clear(this)
                    AiBrain.forgetAll()
                    rebuild()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val memories = prefs.memories
        row(card, "Things I remember", if (memories.isEmpty()) "Say \"Voxa, remember that…\"" else "${memories.size} saved", value = null) {
            if (memories.isEmpty()) {
                toast("Nothing saved yet.")
                return@row
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Things I remember")
                .setItems(memories.toTypedArray(), null)
                .setNeutralButton("Clear all") { _, _ ->
                    prefs.memories = emptyList()
                    rebuild()
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun buildHelp() {
        val card = section("GUIDE")
        row(card, "What you can say", "Every command, in English, Hindi and Urdu.") {
            MaterialAlertDialogBuilder(this)
                .setTitle("What you can say")
                .setMessage(HELP_TEXT)
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun footer() {
        val tv = TextView(this)
        tv.text = "Voxa 4.5"
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        tv.textSize = 12f
        tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(32)
        content.addView(tv, lp)
    }

    // =================================================================
    // Row helpers
    // =================================================================

    private fun section(title: String): LinearLayout {
        val header = TextView(this, null, 0, R.style.Voxa_SectionTitle)
        header.text = title
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (content.childCount == 0) 8 else 28)
            bottomMargin = dp(10)
        })
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        card.clipToOutline = true
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun divider(card: LinearLayout) {
        if (card.childCount == 0) return
        val line = View(this)
        line.setBackgroundColor(ContextCompat.getColor(this, R.color.hairline))
        card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(18) })
    }

    private fun inflateRow(card: LinearLayout, title: String, subtitle: String?): View {
        divider(card)
        val view = LayoutInflater.from(this).inflate(R.layout.item_row, card, false)
        view.findViewById<TextView>(R.id.rowTitle).text = title
        val sub = view.findViewById<TextView>(R.id.rowSubtitle)
        if (!subtitle.isNullOrBlank()) {
            sub.text = subtitle
            sub.visibility = View.VISIBLE
        }
        card.addView(view)
        return view
    }

    private fun row(
        card: LinearLayout,
        title: String,
        subtitle: String?,
        value: String? = null,
        icon: Int = R.drawable.ic_chevron,
        iconTint: Int = R.color.text_tertiary,
        onClick: () -> Unit
    ) {
        val view = inflateRow(card, title, subtitle)
        if (value != null) {
            view.findViewById<TextView>(R.id.rowValue).apply {
                text = value
                visibility = View.VISIBLE
                if (value == "Set up") setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent))
            }
        }
        view.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(this@SettingsActivity, iconTint)
        }
        view.setOnClickListener { onClick() }
    }

    /** [onToggle] returns whether the change was accepted. */
    private fun switchRow(card: LinearLayout, title: String, subtitle: String?, checked: Boolean, onToggle: (Boolean) -> Boolean) {
        val view = inflateRow(card, title, subtitle)
        view.findViewById<ImageView>(R.id.rowIcon).visibility = View.GONE
        val sw = view.findViewById<MaterialSwitch>(R.id.rowSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = checked
        view.setOnClickListener {
            val want = !sw.isChecked
            if (onToggle(want)) sw.isChecked = want
        }
    }

    private fun sliderRow(card: LinearLayout) {
        divider(card)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(14), dp(18), dp(8))
        val label = TextView(this)
        label.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        label.textSize = 15f
        fun setLabel(v: Int) {
            label.text = String.format(Locale.US, "Strictness  ·  %.2f", v / 100f)
        }
        val current = (prefs.threshold * 100).toInt().coerceIn(50, 98)
        setLabel(current)
        val hint = TextView(this)
        hint.text = "Higher is stricter. Set it between your score and a friend's."
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        hint.textSize = 12.5f
        val slider = Slider(this)
        slider.valueFrom = 50f
        slider.valueTo = 98f
        slider.stepSize = 1f
        slider.value = current.toFloat()
        slider.setLabelFormatter { String.format(Locale.US, "%.2f", it / 100f) }
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.threshold = value / 100f
                setLabel(value.toInt())
            }
        }
        box.addView(label)
        box.addView(hint)
        box.addView(slider)
        card.addView(box)
    }

    private fun textDialog(title: String, hint: String, value: String, inputType: Int, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.setText(value)
        input.hint = hint
        input.inputType = inputType
        input.setSelection(input.text.length)
        val frame = FrameLayout(this)
        frame.setPadding(dp(24), dp(8), dp(24), 0)
        frame.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mask(key: String) = if (key.isBlank()) "Not set" else "•••• " + key.takeLast(4)

    /** Like [textDialog], with a Paste button — keys are long and awkward to type. */
    private fun keyDialog(title: String, hint: String, value: String, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.setText(value)
        input.hint = hint
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        input.setSelection(input.text.length)
        val frame = FrameLayout(this)
        frame.setPadding(dp(24), dp(8), dp(24), 0)
        frame.addView(input)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Paste", null)
            .show()
        // Keep the dialog open when Paste is tapped.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrBlank()) toast("Clipboard is empty.") else {
                input.setText(text)
                input.setSelection(input.text.length)
            }
        }
    }

    private fun chooseGroqModel() {
        toast("Loading Groq models…")
        Thread {
            val models = try {
                GroqClient(prefs).models(forceRefresh = true)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (models.isNullOrEmpty()) {
                    toast("Couldn't load models. Check the key and the internet.")
                    return@runOnUiThread
                }
                val options = listOf("Automatic") + models
                val checked = if (prefs.groqModel.isBlank()) 0 else options.indexOf(prefs.groqModel).coerceAtLeast(0)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Groq model")
                    .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                        prefs.groqModel = if (which == 0) "" else options[which]
                        dialog.dismiss()
                        rebuild()
                    }
                    .show()
            }
        }.start()
    }

    // =================================================================
    // Actions
    // =================================================================

    private fun restartWakeIfRunning() {
        if (JarvisService.isRunning && JarvisService.wakeMode) {
            ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_START_WAKE))
        }
    }

    private fun stopJarvisForMic() {
        if (JarvisService.isRunning) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        }
    }

    private fun setVoiceMessage(text: String) = runOnUiThread {
        voiceMessage = text
        rebuild()
    }

    private fun enrollVoice() {
        if (!Setup.hasMic(this)) {
            permissionLauncher.launch(Setup.runtimePermissions())
            return
        }
        stopJarvisForMic()
        voiceBusy = true
        Thread {
            val samples = ArrayList<FloatArray>()
            var attempts = 0
            while (samples.size < 3 && attempts < 6) {
                attempts++
                val n = samples.size + 1
                setVoiceMessage("Sample $n of 3 — after the beep, say: \"Voxa, this is my voice. Unlock.\"")
                Thread.sleep(1400)
                VoicePrint.beep()
                setVoiceMessage("Recording sample $n… speak now")
                val features = try {
                    VoicePrint.features(VoicePrint.record())
                } catch (e: Exception) {
                    null
                }
                if (features == null) {
                    setVoiceMessage("I didn't hear enough speech. Let's try that one again.")
                    Thread.sleep(1500)
                } else {
                    samples.add(features)
                }
            }
            runOnUiThread {
                voiceBusy = false
                if (samples.size == 3) {
                    prefs.voiceprints = samples
                    val selfScore = samples.indices.map { i ->
                        VoicePrint.score(samples[i], samples.filterIndexed { j, _ -> j != i })
                    }.average().toFloat()
                    if (selfScore < prefs.threshold) prefs.threshold = (selfScore - 0.03f).coerceIn(0.50f, 0.98f)
                    voiceMessage = String.format(Locale.US, "Voice recorded. Your samples match each other at %.2f.", selfScore)
                } else {
                    voiceMessage = "Recording failed. Find a quieter place and try again."
                }
                rebuild()
            }
        }.start()
    }

    private fun testVoice() {
        if (!Setup.hasMic(this)) {
            permissionLauncher.launch(Setup.runtimePermissions())
            return
        }
        if (!prefs.hasVoiceprint()) {
            toast("Record your voice first.")
            return
        }
        stopJarvisForMic()
        voiceBusy = true
        setVoiceMessage("After the beep, say the passphrase…")
        Thread {
            Thread.sleep(900)
            val score = try {
                VoicePrint.verify(prefs)
            } catch (e: Exception) {
                -1f
            }
            runOnUiThread {
                voiceBusy = false
                voiceMessage = when {
                    score < 0f -> "Microphone busy. Try again."
                    score == 0f -> "I didn't hear enough speech."
                    score >= prefs.threshold -> String.format(Locale.US, "Match — score %.2f (needs %.2f)", score, prefs.threshold)
                    else -> String.format(Locale.US, "Rejected — score %.2f (needs %.2f)", score, prefs.threshold)
                }
                rebuild()
            }
        }.start()
    }

    private fun testAi() {
        val groq = prefs.aiEngine == Prefs.ENGINE_GROQ
        aiMessage = if (groq) "Contacting Groq…" else "Contacting Claude…"
        rebuild()
        Thread {
            val reply = AiBrain(this).ask("Connection test. Reply with one short word.", remember = false)
            val model = if (groq) (try { GroqClient(prefs).currentModel() } catch (e: Exception) { null }) else "claude-opus-5-5"
            runOnUiThread {
                aiMessage = if (reply.failed) reply.speech
                else "Connected ✓  ${if (groq) "Groq" else "Claude"} is online${model?.let { " · $it" } ?: ""}."
                rebuild()
            }
        }.start()
    }

    private fun probeVoices() {
        var probe: TextToSpeech? = null
        probe = TextToSpeech(applicationContext) { status ->
            val tts = probe
            if (status == TextToSpeech.SUCCESS && tts != null) {
                hindiVoice = tts.isLanguageAvailable(Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE
                urduVoice = tts.isLanguageAvailable(Locale("ur", "PK")) >= TextToSpeech.LANG_AVAILABLE
            } else {
                hindiVoice = false
                urduVoice = false
            }
            tts?.shutdown()
            runOnUiThread { rebuild() }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val ENGINES = listOf(
            "Offline (built-in commands only)" to Prefs.ENGINE_OFFLINE,
            "Groq" to Prefs.ENGINE_GROQ,
            "Claude" to Prefs.ENGINE_CLAUDE
        )

        private val LANGUAGES = listOf(
            "Auto · Urdu + Hindi + English" to Lang.AUTO,
            "English (US)" to "en-US",
            "English (South Asian accent)" to "en-IN",
            "English (UK)" to "en-GB",
            "हिन्दी · Hindi" to "hi-IN",
            "اردو · Urdu" to "ur-PK"
        )

        private val HELP_TEXT = """
            Start with "Voxa" when Hey Voxa is on, or tap the orb.

            ASK ANYTHING (AI key)
            "Who won the match last night?" · "Explain black holes simply" · then a follow-up without the wake word. "New conversation" starts fresh.

            YOUR SCREEN (AI key)
            "What's on my screen?" · "Look at my screen and translate this" · "Read the screen"

            BRIEFING
            "Good morning" · "Brief me" — time, battery, weather and headlines

            MESSAGES
            "Read my notifications" · "Any new messages?" · "Send a message to Ali saying I'm on my way" · "WhatsApp Sara saying see you at 8" · "Call Mom"

            MEMORY & REMINDERS
            "Remember that my car is on level 3" · "What do you remember?" · "Remind me to call Ahmed at 5 pm" · "Remind me in 20 minutes to check the oven"

            ALARMS & TIMERS
            "Set an alarm for 6:30 am" · "Set a timer for 10 minutes"

            APPS & MEDIA
            "Open Instagram" · "Play Believer on YouTube" · "Pause" · "Next song" · "Take a selfie"

            PHONE
            "Flashlight on" · "Volume to 40 percent" · "Mute" · "Brightness 70 percent" · "Turn on Bluetooth" · "Airplane mode on" · "Silent mode"

            SCREEN CONTROL
            "Go home" · "Go back" · "Scroll down" · "Tap Subscribe" · "Type hello there" · "Take a screenshot" · "Lock the phone"

            MAPS & WEB
            "Navigate to the airport" · "Search for pizza near me"

            HINDI / URDU
            "टॉर्च जलाओ" · "آواز بڑھاؤ" · "YouTube kholo" · "मेरे मैसेज पढ़ो" · "یاد رکھو کہ چابی دراز میں ہے" — with the AI key, anything at all.

            VOXA
            "Lock yourself" · "Stop listening" · "Help"
        """.trimIndent()
    }
}
