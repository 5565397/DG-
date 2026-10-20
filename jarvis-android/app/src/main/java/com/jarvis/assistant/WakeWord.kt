package com.jarvis.assistant

import java.util.Locale

/** Finds "Jarvis" (and the ways speech recognizers usually mishear it) in a transcript. */
object WakeWord {
    private val variants = listOf(
        "jarvis", "jarvish", "jarvas", "jervis", "javis", "jaarvis", "jarwis", "jarvi's", "jarviss",
        // Hindi (Devanagari) and Urdu spellings from hi-IN / ur-PK speech recognition
        "जार्विस", "जारविस", "जार्वीस", "जारवीस", "जर्विस", "जार्विज", "जारविज़",
        "جارویس", "جاروس", "جاروِس", "جارویز", "جاروز"
    ).sortedByDescending { it.length }
    // Lookarounds instead of \b, which only understands ASCII letters.
    private const val NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{M}])"
    private const val NOT_LETTER_AFTER = "(?![\\p{L}\\p{M}])"
    private val pattern = Regex(
        NOT_LETTER_BEFORE + "(?:(?:hey|hi|ok|okay|yo|हे|ओके|हेलो|ہے|اوکے|ہیلو)\\s+)?(?:" +
            variants.joinToString("|") { Regex.escape(it) } + ")" + NOT_LETTER_AFTER + "[,.!?।؟،]?"
    )

    /** Returns the text after the wake word ("" if nothing follows), or null if there is no wake word. */
    fun extract(text: String): String? {
        val t = text.lowercase(Locale.US).trim()
        val match = pattern.find(t) ?: return null
        return t.substring(match.range.last + 1).trim(' ', ',', '.', '!', '?', '।', '؟', '،')
    }

    /** Removes the wake word if present, otherwise returns the text as is. */
    fun strip(text: String): String = extract(text) ?: text.trim()
}
