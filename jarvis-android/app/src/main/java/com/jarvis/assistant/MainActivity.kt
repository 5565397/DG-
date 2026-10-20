package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** The main screen: one orb, what you said, what Jarvis said. Everything else lives in Settings. */
class MainActivity : AppCompatActivity(), JarvisBus.Listener {

    private lateinit var orb: OrbView
    private lateinit var txtStatus: TextView
    private lateinit var txtHeard: TextView
    private lateinit var txtReply: TextView
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupProgress: TextView
    private lateinit var inputRow: LinearLayout
    private lateinit var inputCommand: EditText
    private lateinit var chipWake: LinearLayout
    private lateinit var txtWake: TextView
    private lateinit var wakeDot: View

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

        orb = findViewById(R.id.orb)
        txtStatus = findViewById(R.id.txtStatus)
        txtHeard = findViewById(R.id.txtHeard)
        txtReply = findViewById(R.id.txtReply)
        setupBanner = findViewById(R.id.setupBanner)
        setupProgress = findViewById(R.id.setupProgress)
        inputRow = findViewById(R.id.inputRow)
        inputCommand = findViewById(R.id.inputCommand)
        chipWake = findViewById(R.id.chipWake)
        txtWake = findViewById(R.id.txtWake)
        wakeDot = findViewById(R.id.wakeDot)

        orb.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            withMic { send(JarvisService.ACTION_LISTEN_ONCE) }
        }
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
        findViewById<ImageButton>(R.id.btnKeyboard).setOnClickListener { toggleKeyboard() }
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener { runTyped() }
        inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runTyped()
                true
            } else {
                false
            }
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
        if (JarvisBus.lastHeard.isNotBlank()) txtHeard.text = quote(JarvisBus.lastHeard)
        if (JarvisBus.lastReply.isNotBlank()) txtReply.text = JarvisBus.lastReply
        if (txtReply.text.isNullOrBlank()) txtReply.text = greeting()
        renderWake(isWakeOn())
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
                JarvisBus.State.SPEAKING -> ""
                else -> "Tap the orb to speak"
            }
        }
        if (state == JarvisBus.State.OFFLINE) renderWake(false)
    }

    override fun onHeard(text: String) {
        txtHeard.text = quote(text)
        txtReply.animate().alpha(0.35f).setDuration(200).start()
    }

    override fun onReply(text: String) {
        txtReply.alpha = 0f
        txtReply.translationY = 12f
        txtReply.text = text
        txtReply.animate().alpha(1f).translationY(0f).setDuration(320).start()
    }

    override fun onLevel(level: Float) = orb.setLevel(level)

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
        onHeard(text)
        send(JarvisService.ACTION_RUN_TEXT, text)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        if (inputRow.visibility == View.VISIBLE) {
            imm.hideSoftInputFromWindow(inputCommand.windowToken, 0)
            inputRow.visibility = View.GONE
        } else {
            inputRow.visibility = View.VISIBLE
            inputCommand.requestFocus()
            inputCommand.post { imm.showSoftInput(inputCommand, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun isWakeOn() = JarvisService.isRunning && JarvisService.wakeMode

    private fun renderWake(on: Boolean) {
        chipWake.setBackgroundResource(if (on) R.drawable.pill_active else R.drawable.pill)
        txtWake.text = if (on) "Hey Jarvis · On" else "Hey Jarvis · Off"
        txtWake.setTextColor(ContextCompat.getColor(this, if (on) R.color.text_primary else R.color.text_secondary))
        wakeDot.alpha = if (on) 1f else 0.25f
    }

    private fun refreshSetupBanner() {
        val total = Setup.Step.values().size
        val done = Setup.doneCount(this)
        setupBanner.visibility = if (done < total) View.VISIBLE else View.GONE
        setupProgress.text = "$done of $total done"
    }

    private fun quote(text: String) = "“$text”"

    private fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when {
            hour < 5 -> "Good evening"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, ${Prefs(this).title}."
    }
}
