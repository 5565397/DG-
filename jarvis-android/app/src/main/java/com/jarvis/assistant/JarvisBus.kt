package com.jarvis.assistant

import android.os.Handler
import android.os.Looper

/** Pushes live status from the background service to the screen, if it is open. */
object JarvisBus {
    interface Listener {
        fun onStatus(text: String, listening: Boolean)
        fun onHeard(text: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var lastStatus = "Offline"
        private set
    @Volatile var lastListening = false
        private set

    private val main = Handler(Looper.getMainLooper())

    fun status(text: String, listening: Boolean = false) {
        lastStatus = text
        lastListening = listening
        main.post { listener?.onStatus(text, listening) }
    }

    fun heard(text: String) {
        main.post { listener?.onHeard(text) }
    }
}
