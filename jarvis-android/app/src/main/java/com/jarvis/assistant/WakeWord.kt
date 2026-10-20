package com.jarvis.assistant

import java.util.Locale

/** Finds "Jarvis" (and the ways speech recognizers usually mishear it) in a transcript. */
object WakeWord {
    private val variants = listOf(
        "jarvis", "jarvish", "jarvas", "jervis", "javis", "jaarvis", "jarwis", "jarvi's", "jarviss"
    )
    private val pattern = Regex(
        "\\b(?:(?:hey|hi|ok|okay|yo)\\s+)?(?:" + variants.joinToString("|") { Regex.escape(it) } + ")\\b[,.!?]?"
    )

    /** Returns the text after the wake word ("" if nothing follows), or null if there is no wake word. */
    fun extract(text: String): String? {
        val t = text.lowercase(Locale.US).trim()
        val match = pattern.find(t) ?: return null
        return t.substring(match.range.last + 1).trim(' ', ',', '.', '!', '?')
    }

    /** Removes the wake word if present, otherwise returns the text as is. */
    fun strip(text: String): String = extract(text) ?: text.trim()
}
