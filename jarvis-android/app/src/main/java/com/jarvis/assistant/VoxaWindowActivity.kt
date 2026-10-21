package com.jarvis.assistant

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Voxa's own window: opens when you say "Voxa" — over the lock screen or any app — shows the
 * conversation as it happens and closes when Voxa goes back to sleep. Anything personal asks
 * for the phone to be unlocked first, then carries on by itself.
 */
class VoxaWindowActivity : AppCompatActivity(), JarvisBus.Listener {

    companion object {
        @Volatile var open = false
            private set

        /** Messages from just before the window opened that are still worth showing. */
        private const val RECENT_MS = 3 * 60_000L

        /** Start listening as soon as the window opens (assistant button, smart key, tile, shortcut). */
        const val EXTRA_LISTEN = "listen"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var chat: LinearLayout
    private lateinit var partial: TextView
    private lateinit var renderer: ChatRenderer
    private var shown = 0
    private var openedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_window)
        orb = findViewById(R.id.windowOrb)
        status = findViewById(R.id.windowStatus)
        scroll = findViewById(R.id.windowScroll)
        chat = findViewById(R.id.windowChat)
        partial = findViewById(R.id.windowPartial)
        renderer = ChatRenderer(this, chat, partial, widthFraction = 0.74f)
        openedAt = System.currentTimeMillis()

        findViewById<TextView>(R.id.windowHint).text = when (Lang.mode(Prefs(this))) {
            Lang.HINGLISH -> "Boliye, ya orb dabaiye"
            "ur" -> "بولیے، یا اورب دبائیے"
            "hi" -> "बोलिए, या ऑर्ब दबाइए"
            else -> "Speak, or tap the orb"
        }
        orb.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            service(JarvisService.ACTION_LISTEN_ONCE)
        }
        findViewById<ImageButton>(R.id.windowClose).setOnClickListener { sleepAndClose() }
        // Tapping the dimmed area outside the card closes the window too.
        findViewById<View>(R.id.windowRoot).setOnClickListener { sleepAndClose() }
        findViewById<TextView>(R.id.windowOpenApp).setOnClickListener {
            unlockThen {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        open = true
        JarvisBus.register(this)
        renderChat()
        listenIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        main.removeCallbacksAndMessages(null)
        listenIfAsked(intent)
    }

    /** Opened by a button rather than by the wake word: nobody is listening yet, so start. */
    private fun listenIfAsked(intent: Intent?) {
        getSystemService(android.app.NotificationManager::class.java)?.cancel(JarvisService.WINDOW_NOTIF_ID)
        val action = intent?.action
        val asked = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            action == Intent.ACTION_MAIN || action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND
        if (asked && !Setup.hasMic(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (asked) service(JarvisService.ACTION_LISTEN_ONCE)
    }

    override fun onResume() {
        super.onResume()
        onState(JarvisBus.state, JarvisBus.status)
        JarvisService.pendingUnlock?.let { onNeedUnlock(it) }
    }

    override fun onDestroy() {
        JarvisBus.unregister(this)
        open = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- live updates

    override fun onState(state: JarvisBus.State, status: String) {
        orb.state = state
        this.status.text = status.ifBlank {
            when (state) {
                JarvisBus.State.LISTENING -> "Listening"
                JarvisBus.State.THINKING -> "Thinking"
                JarvisBus.State.SPEAKING -> "Speaking"
                else -> ""
            }
        }
        if (state == JarvisBus.State.OFFLINE) finish()
    }

    override fun onPartial(text: String) {
        if (text.isBlank()) {
            partial.visibility = View.GONE
        } else {
            partial.text = text
            partial.visibility = View.VISIBLE
            scrollToEnd()
        }
    }

    override fun onChat(message: ChatStore.Message) {
        // Woken again before the window closed: keep it open.
        if (message.role == ChatStore.Role.USER) main.removeCallbacksAndMessages(null)
        renderChat()
    }

    override fun onChatCleared() {
        for (i in chat.childCount - 1 downTo 0) {
            if (chat.getChildAt(i) !== partial) chat.removeViewAt(i)
        }
        shown = 0
        renderer.reset()
        renderChat()
    }

    override fun onLevel(level: Float) = orb.setLevel(level)

    override fun onSleep() {
        // Let the last answer stay readable for a moment.
        main.postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    override fun onNeedUnlock(command: String) {
        main.removeCallbacksAndMessages(null)
        unlockThen {
            val waiting = JarvisService.pendingUnlock ?: return@unlockThen
            JarvisService.pendingUnlock = null
            ContextCompat.startForegroundService(
                this, Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_RUN_UNLOCKED).putExtra(JarvisService.EXTRA_TEXT, waiting)
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Only this conversation: messages from a little before the window opened onwards. */
    private fun renderChat() {
        val all = ChatStore.all(this).filter { it.time >= openedAt - RECENT_MS }
        if (all.size < shown) {
            onChatCleared()
            return
        }
        for (i in shown until all.size) renderer.add(all[i])
        shown = all.size
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    /** Runs [action] once the phone is unlocked (straight away if it already is). */
    private fun unlockThen(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            action()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    private fun sleepAndClose() {
        service(JarvisService.ACTION_SLEEP)
        finish()
    }

    private fun service(action: String) {
        if (!JarvisService.isRunning && action == JarvisService.ACTION_SLEEP) return
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java).setAction(action))
    }
}
