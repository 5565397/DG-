package com.jarvis.assistant

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Pushes live state from the background service to every open Voxa screen (the app and the lock-screen window). */
object JarvisBus {

    enum class State { OFFLINE, IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State, status: String)
        /** Live words while you're still speaking ("" clears it). */
        fun onPartial(text: String)
        fun onChat(message: ChatStore.Message)
        fun onChatCleared()
        fun onLevel(level: Float)
        /** Voxa went back to sleep: the lock-screen window should close. */
        fun onSleep() {}
        /** A command needs the phone unlocked first; run [command] once it is. */
        fun onNeedUnlock(command: String) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(l: Listener) = listeners.add(l)
    fun unregister(l: Listener) = listeners.remove(l)

    /** True if some Voxa screen is open and listening. */
    val hasScreen: Boolean get() = listeners.isNotEmpty()

    @Volatile var state = State.OFFLINE
        private set
    @Volatile var status = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    private fun each(block: (Listener) -> Unit) = main.post { listeners.forEach(block) }

    fun state(newState: State, text: String = "") {
        state = newState
        status = text
        each { it.onState(newState, text) }
    }

    /** Status text without changing the state. */
    fun status(text: String) = state(state, text)

    fun partial(text: String) = each { it.onPartial(text) }

    fun chat(message: ChatStore.Message) = each { it.onChat(message) }

    fun chatCleared() = each { it.onChatCleared() }

    fun sleep() = each { it.onSleep() }

    fun needUnlock(command: String) = each { it.onNeedUnlock(command) }

    /** Microphone loudness from the recognizer, roughly -2..10 dB, mapped to 0..1. */
    fun level(rmsDb: Float) {
        val level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        each { it.onLevel(level) }
    }
}
