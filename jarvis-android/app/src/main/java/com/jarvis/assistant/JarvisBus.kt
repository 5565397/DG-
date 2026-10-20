package com.jarvis.assistant

import android.os.Handler
import android.os.Looper

/** Pushes live state from the background service to the screen, if it is open. */
object JarvisBus {

    enum class State { OFFLINE, IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State, status: String)
        fun onHeard(text: String)
        fun onReply(text: String)
        fun onLevel(level: Float)
    }

    @Volatile var listener: Listener? = null
    @Volatile var state = State.OFFLINE
        private set
    @Volatile var status = ""
        private set
    @Volatile var lastHeard = ""
        private set
    @Volatile var lastReply = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    fun state(newState: State, text: String = "") {
        state = newState
        status = text
        main.post { listener?.onState(newState, text) }
    }

    /** Status text without changing the state. */
    fun status(text: String) = state(state, text)

    fun heard(text: String) {
        lastHeard = text
        main.post { listener?.onHeard(text) }
    }

    fun reply(text: String) {
        if (text.isBlank()) return
        lastReply = text
        main.post { listener?.onReply(text) }
    }

    /** Microphone loudness from the recognizer, roughly -2..10 dB, mapped to 0..1. */
    fun level(rmsDb: Float) {
        val level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        main.post { listener?.onLevel(level) }
    }
}
