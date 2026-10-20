package com.jarvis.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The main screen: the orb, the whole conversation as a chat, and a message box. */
class MainActivity : AppCompatActivity(), JarvisBus.Listener {

    private lateinit var prefs: Prefs
    private lateinit var orb: OrbView
    private lateinit var txtStatus: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var txtPartial: TextView
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupProgress: TextView
    private lateinit var inputCommand: EditText
    private lateinit var btnMic: ImageButton
    private lateinit var btnSend: ImageButton
    private lateinit var chipWake: LinearLayout
    private lateinit var txtWake: TextView
    private lateinit var wakeDot: View

    /** Number of chat messages currently drawn, to append only what's new. */
    private var shown = 0
    private var lastRole: ChatStore.Role? = null
    private var lastDay = ""

    /** What to do once the microphone permission is granted. */
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetupBanner()
            if (Setup.hasMic(this)) afterPermission?.invoke()
            afterPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        orb = findViewById(R.id.orb)
        txtStatus = findViewById(R.id.txtStatus)
        chatScroll = findViewById(R.id.chatScroll)
        chatList = findViewById(R.id.chatList)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtPartial = findViewById(R.id.txtPartial)
        setupBanner = findViewById(R.id.setupBanner)
        setupProgress = findViewById(R.id.setupProgress)
        inputCommand = findViewById(R.id.inputCommand)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        chipWake = findViewById(R.id.chipWake)
        txtWake = findViewById(R.id.txtWake)
        wakeDot = findViewById(R.id.wakeDot)

        val listen = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            withMic { send(JarvisService.ACTION_LISTEN_ONCE) }
        }
        orb.setOnClickListener(listen)
        btnMic.setOnClickListener(listen)
        btnSend.setOnClickListener { runTyped() }

        // Wraps like a multi-line box but shows a Send key on the keyboard.
        inputCommand.setHorizontallyScrolling(false)
        inputCommand.maxLines = 4
        inputCommand.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runTyped()
                true
            } else {
                false
            }
        }
        inputCommand.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrBlank()
                btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                btnMic.visibility = if (hasText) View.GONE else View.VISIBLE
            }
        })

        chipWake.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isWakeOn()) {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
                renderWake(false)
            } else {
                withMic {
                    send(JarvisService.ACTION_START_WAKE)
                    renderWake(true)
                }
            }
        }
        findViewById<LinearLayout>(R.id.chipAi).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when {
                prefs.aiActive -> prefs.aiEngine = Prefs.ENGINE_OFFLINE
                prefs.groqKey.isNotBlank() -> prefs.aiEngine = Prefs.ENGINE_GROQ
                prefs.apiKey.isNotBlank() -> prefs.aiEngine = Prefs.ENGINE_CLAUDE
                else -> {
                    Toast.makeText(this, "Add your Groq API key in Settings → AI Brain.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            renderAi()
        }
        val openSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener(openSettings)
        setupBanner.setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        JarvisBus.listener = this
        val state = if (JarvisService.isRunning) JarvisBus.state else JarvisBus.State.OFFLINE
        onState(state, if (JarvisService.isRunning) JarvisBus.status else "")
        renderChat()
        renderWake(isWakeOn())
        renderAi()
        refreshSetupBanner()
    }

    override fun onPause() {
        JarvisBus.listener = null
        super.onPause()
    }

    // ---------------------------------------------------------------- live updates

    override fun onState(state: JarvisBus.State, status: String) {
        orb.state = state
        txtStatus.text = status.ifBlank {
            when (state) {
                JarvisBus.State.LISTENING -> "Listening"
                JarvisBus.State.THINKING -> "Thinking"
                JarvisBus.State.SPEAKING -> "Speaking"
                else -> "Tap the orb to speak"
            }
        }
        btnMic.imageTintList = ContextCompat.getColorStateList(
            this, if (state == JarvisBus.State.LISTENING) R.color.accent else R.color.text_secondary
        )
        if (state == JarvisBus.State.OFFLINE) renderWake(false)
    }

    override fun onPartial(text: String) {
        if (text.isBlank()) {
            txtPartial.visibility = View.GONE
        } else {
            txtPartial.text = text
            txtPartial.visibility = View.VISIBLE
            scrollToEnd()
        }
    }

    override fun onChat(message: ChatStore.Message) = renderChat()

    override fun onChatCleared() {
        // Remove every bubble but keep the empty state and live-text views.
        for (i in chatList.childCount - 1 downTo 0) {
            val v = chatList.getChildAt(i)
            if (v !== txtEmpty && v !== txtPartial) chatList.removeViewAt(i)
        }
        shown = 0
        lastRole = null
        lastDay = ""
        renderChat()
    }

    override fun onLevel(level: Float) = orb.setLevel(level)

    // ---------------------------------------------------------------- chat drawing

    private fun renderChat() {
        val all = ChatStore.all(this)
        if (all.size < shown) {
            onChatCleared()
            return
        }
        txtEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        if (all.isEmpty()) txtEmpty.text = greeting() + "\n" + hint()
        for (i in shown until all.size) addBubble(all[i])
        shown = all.size
        scrollToEnd()
    }

    private fun addBubble(m: ChatStore.Message) {
        val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(m.time))
        if (day != lastDay) {
            lastDay = day
            insert(note(day), topMargin = 18)
            lastRole = null
        }
        when (m.role) {
            ChatStore.Role.NOTE -> {
                insert(note(m.text), topMargin = 8)
                lastRole = ChatStore.Role.NOTE
                return
            }
            ChatStore.Role.USER, ChatStore.Role.VOXA -> {}
        }
        val user = m.role == ChatStore.Role.USER
        val sameSender = lastRole == m.role
        if (!user && !sameSender) {
            val label = TextView(this)
            label.text = "VOXA"
            label.letterSpacing = 0.25f
            label.textSize = 10.5f
            label.setTextColor(ContextCompat.getColor(this, R.color.accent))
            label.setPadding(dp(6), 0, 0, 0)
            insert(label, topMargin = 14)
        }
        val bubble = TextView(this)
        bubble.text = m.text
        bubble.textSize = 15.5f
        bubble.setLineSpacing(dp(3).toFloat(), 1f)
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        bubble.setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_voxa)
        bubble.setPadding(dp(16), dp(10), dp(16), dp(11))
        bubble.maxWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        bubble.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        bubble.setOnLongClickListener {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Voxa", m.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(if (sameSender) 4 else if (user) 14 else 4)
        // Keep the live-text bubble last.
        chatList.addView(bubble, chatList.indexOfChild(txtPartial), lp)
        lastRole = m.role
    }

    private fun note(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 11.5f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        return tv
    }

    private fun insert(view: TextView, topMargin: Int) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topMargin)
        chatList.addView(view, chatList.indexOfChild(txtPartial), lp)
    }

    private fun scrollToEnd() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- actions

    private fun withMic(action: () -> Unit) {
        if (Setup.hasMic(this)) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(Setup.runtimePermissions())
        }
    }

    private fun send(action: String, text: String? = null) {
        val intent = Intent(this, JarvisService::class.java).setAction(action)
        if (text != null) intent.putExtra(JarvisService.EXTRA_TEXT, text)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun runTyped() {
        val text = inputCommand.text.toString().trim()
        if (text.isEmpty()) return
        inputCommand.setText("")
        send(JarvisService.ACTION_RUN_TEXT, text)
    }

    private fun isWakeOn() = JarvisService.isRunning && JarvisService.wakeMode

    private fun renderWake(on: Boolean) {
        chipWake.setBackgroundResource(if (on) R.drawable.pill_active else R.drawable.pill)
        txtWake.text = if (on) "Hey Voxa · On" else "Hey Voxa · Off"
        txtWake.setTextColor(ContextCompat.getColor(this, if (on) R.color.text_primary else R.color.text_secondary))
        wakeDot.alpha = if (on) 1f else 0.25f
    }

    /** "AI · Groq" when online AI is on, "AI · Off" when Voxa runs fully offline. */
    private fun renderAi() {
        val on = prefs.aiActive
        val chip = findViewById<LinearLayout>(R.id.chipAi)
        val label = findViewById<TextView>(R.id.txtAi)
        chip.setBackgroundResource(if (on) R.drawable.pill_active else R.drawable.pill)
        label.text = when {
            !on -> "AI · Off"
            prefs.aiEngine == Prefs.ENGINE_GROQ -> "AI · Groq"
            else -> "AI · Claude"
        }
        label.setTextColor(ContextCompat.getColor(this, if (on) R.color.text_primary else R.color.text_secondary))
        findViewById<View>(R.id.aiDot).alpha = if (on) 1f else 0.25f
    }

    private fun refreshSetupBanner() {
        val total = Setup.Step.values().size
        val done = Setup.doneCount(this)
        setupBanner.visibility = if (done < total) View.VISIBLE else View.GONE
        setupProgress.text = "Finish setting up Voxa · $done of $total done"
    }

    private fun hint(): String = when (Lang.mode(prefs)) {
        "ur" -> "اورب کو چھوئیں اور بولیں، یا نیچے لکھیں۔"
        "hi" -> "ऑर्ब को छुएँ और बोलें, या नीचे लिखें।"
        else -> "Tap the orb and speak, or type below."
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isSir = prefs.title.equals("sir", true)
        return when (Lang.mode(prefs)) {
            "ur" -> (if (hour in 4..11) "صبح بخیر" else if (hour in 12..16) "السلام علیکم" else "شام بخیر") + "، " + (if (isSir) "سر" else prefs.title)
            "hi" -> (if (hour in 4..11) "सुप्रभात" else if (hour in 12..16) "नमस्ते" else "शुभ संध्या") + ", " + (if (isSir) "सर" else prefs.title)
            else -> (when {
                hour < 5 -> "Good evening"
                hour < 12 -> "Good morning"
                hour < 17 -> "Good afternoon"
                else -> "Good evening"
            }) + ", ${prefs.title}."
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
