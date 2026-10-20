package com.jarvis.assistant

import android.os.Handler
import android.os.Looper

/** Pushes live state from the background service to the screen, if it is open. */
object JarvisBus {

    enum class State { OFFLINE, IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State, status: String)
        /** Live words while you're still speaking ("" clears it). */
        fun onPartial(text: String)
        fun onChat(message: ChatStore.Message)
        fun onChatCleared()
        fun onLevel(level: Float)
    }

    @Volatile var listener: Listener? = null
    @Volatile var state = State.OFFLINE
        private set
    @Volatile var status = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    fun state(newState: State, text: String = "") {
        state = newState
        status = text
        main.post { listener?.onState(newState, text) }
    }

    /** Status text without changing the state. */
    fun status(text: String) = state(state, text)

    fun partial(text: String) {
        main.post { listener?.onPartial(text) }
    }

    fun chat(message: ChatStore.Message) {
        main.post { listener?.onChat(message) }
    }

    fun chatCleared() {
        main.post { listener?.onChatCleared() }
    }

    /** Microphone loudness from the recognizer, roughly -2..10 dB, mapped to 0..1. */
    fun level(rmsDb: Float) {
        val level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        main.post { listener?.onLevel(level) }
    }
}
