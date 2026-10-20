package com.jarvis.assistant

import java.text.Normalizer
import java.util.Locale

/** Finds "Voxa" (and the ways speech recognizers usually write or mishear it) in a transcript. */
object WakeWord {
    private val variants = listOf(
        // English recognizers
        "voxa", "voksa", "vocsa", "voxah", "voxxa", "vox a", "vox-a", "vaksa", "voxer", "vokza", "boxa", "box a", "foxa", "fox a",
        // Hindi (Devanagari)
        "वोक्सा", "वॉक्सा", "वोकसा", "वाक्सा", "बोक्सा", "वोक्स",
        // Urdu
        "ووکسا", "ووکسہ", "واکسا", "وکسا", "بوکسا", "ووکس"
    ).map { Normalizer.normalize(it, Normalizer.Form.NFC) }.sortedByDescending { it.length }

    // Lookarounds instead of \b, which only understands ASCII letters.
    private const val NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{M}])"
    private const val NOT_LETTER_AFTER = "(?![\\p{L}\\p{M}])"
    private val pattern = Regex(
        NOT_LETTER_BEFORE + "(?:(?:hey|hi|ok|okay|yo|हे|ओके|हेलो|ہے|اوکے|ہیلو)\\s+)?(?:" +
            variants.joinToString("|") { Regex.escape(it) } + ")" + NOT_LETTER_AFTER + "[,.!?।؟،]?"
    )

    /** Returns the text after the wake word ("" if nothing follows), or null if there is no wake word. */
    fun extract(text: String): String? {
        val t = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.US).trim()
        val match = pattern.find(t) ?: return null
        return t.substring(match.range.last + 1).trim(' ', ',', '.', '!', '?', '।', '؟', '،')
    }

    /** Removes the wake word if present, otherwise returns the text as is. */
    fun strip(text: String): String = extract(text) ?: text.trim()
}
