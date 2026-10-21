package com.jarvis.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
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

    companion object {
        /** The app is on screen, so the lock-screen window isn't needed. */
        @Volatile var visible = false
            private set
    }

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
    private lateinit var renderer: ChatRenderer

    /** A file picked with + and waiting to be sent. */
    private var pendingFile: FileAnalyzer.Attachment? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val file = FileAnalyzer.importUri(this, uri)
            runOnUiThread {
                if (file == null) Toast.makeText(this, "Couldn't open that file (max 40 MB).", Toast.LENGTH_LONG).show()
                else showPending(file)
            }
        }.start()
    }

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
        renderer = ChatRenderer(this, chatList, txtPartial)

        val listen = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            withMic { send(JarvisService.ACTION_LISTEN_ONCE) }
        }
        orb.setOnClickListener(listen)
        btnMic.setOnClickListener(listen)
        btnSend.setOnClickListener { runTyped() }
        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            filePicker.launch(arrayOf("image/*", "application/pdf", "text/*", "audio/*", "video/*", "application/*"))
        }
        findViewById<ImageButton>(R.id.attachRemove).setOnClickListener {
            pendingFile?.let { java.io.File(it.path).delete() }
            showPending(null)
        }

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
            override fun afterTextChanged(s: Editable?) = updateSendButton()
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
        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnNewChat).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            ChatStore.newSession(this)
            AiBrain.forgetAll()
            Toast.makeText(this, "New chat — the old one is saved in History", Toast.LENGTH_SHORT).show()
        }
        setupBanner.setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        JarvisBus.register(this)
        visible = true
        val state = if (JarvisService.isRunning) JarvisBus.state else JarvisBus.State.OFFLINE
        onState(state, if (JarvisService.isRunning) JarvisBus.status else "")
        renderChat()
        // Voxa stays listening for "Voxa" in the background unless you switched it off.
        if (prefs.wakeEnabled && !JarvisService.isRunning && Setup.hasMic(this)) {
            send(JarvisService.ACTION_START_WAKE)
            renderWake(true)
        } else {
            renderWake(isWakeOn())
        }
        renderAi()
        refreshSetupBanner()
    }

    override fun onPause() {
        JarvisBus.unregister(this)
        visible = false
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
        renderer.reset()
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
        for (i in shown until all.size) renderer.add(all[i])
        shown = all.size
        scrollToEnd()
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
        val file = pendingFile
        if (file != null) {
            inputCommand.setText("")
            showPending(null)
            val intent = Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_RUN_FILE)
                .putExtra(JarvisService.EXTRA_FILE_PATH, file.path)
                .putExtra(JarvisService.EXTRA_FILE_NAME, file.name)
                .putExtra(JarvisService.EXTRA_FILE_MIME, file.mime)
                .putExtra(JarvisService.EXTRA_TEXT, text)
            ContextCompat.startForegroundService(this, intent)
            return
        }
        if (text.isEmpty()) return
        inputCommand.setText("")
        send(JarvisService.ACTION_RUN_TEXT, text)
    }

    private fun updateSendButton() {
        val ready = !inputCommand.text.isNullOrBlank() || pendingFile != null
        btnSend.visibility = if (ready) View.VISIBLE else View.GONE
        btnMic.visibility = if (ready) View.GONE else View.VISIBLE
    }

    /** Shows (or hides) the file waiting above the message box. */
    private fun showPending(file: FileAnalyzer.Attachment?) {
        pendingFile = file
        val box = findViewById<LinearLayout>(R.id.attachPreview)
        if (file == null) {
            box.visibility = View.GONE
            updateSendButton()
            return
        }
        box.visibility = View.VISIBLE
        findViewById<TextView>(R.id.attachName).text = file.name
        findViewById<TextView>(R.id.attachInfo).text = "${kindLabel(file.kind)} · ${sizeLabel(file.size)} · add a question or just send"
        val thumb = findViewById<ImageView>(R.id.attachThumb)
        val bmp = if (file.kind == FileAnalyzer.Kind.IMAGE) ChatRenderer.thumbnail(file.path, 200) else null
        if (bmp != null) {
            thumb.imageTintList = null
            thumb.setPadding(0, 0, 0, 0)
            thumb.setImageBitmap(bmp)
        } else {
            thumb.imageTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
            thumb.setPadding(dp(10), dp(10), dp(10), dp(10))
            thumb.setImageResource(R.drawable.ic_file)
        }
        updateSendButton()
        inputCommand.requestFocus()
    }

    private fun kindLabel(kind: FileAnalyzer.Kind) = when (kind) {
        FileAnalyzer.Kind.IMAGE -> "Picture"
        FileAnalyzer.Kind.PDF -> "PDF"
        FileAnalyzer.Kind.DOCUMENT -> "Document"
        FileAnalyzer.Kind.TEXT -> "Text"
        FileAnalyzer.Kind.AUDIO -> "Audio"
        FileAnalyzer.Kind.VIDEO -> "Video"
        FileAnalyzer.Kind.OTHER -> "File"
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
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
        Lang.HINGLISH -> "Orb ko chhuyein aur bolein, ya neeche likhein."
        else -> "Tap the orb and speak, or type below."
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isSir = prefs.title.equals("sir", true)
        return when (Lang.mode(prefs)) {
            "ur" -> (if (hour in 4..11) "صبح بخیر" else if (hour in 12..16) "السلام علیکم" else "شام بخیر") + "، " + (if (isSir) "سر" else prefs.title)
            "hi" -> (if (hour in 4..11) "सुप्रभात" else if (hour in 12..16) "नमस्ते" else "शुभ संध्या") + ", " + (if (isSir) "सर" else prefs.title)
            Lang.HINGLISH -> (if (hour in 4..11) "Subah bakhair" else if (hour in 12..16) "Assalam o Alaikum" else "Shaam bakhair") + ", " + prefs.title.replaceFirstChar { it.uppercase() }
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
