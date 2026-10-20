package com.jarvis.assistant

import java.util.Locale

/**
 * Works out which language a sentence is in, so Voxa can answer in the same one:
 * Urdu script -> "ur", Devanagari -> "hi", Roman Urdu/Hindi ("mujhe batao kya hai") -> "ur",
 * everything else -> "en".
 */
object LangDetect {

    private val ROMAN_HINDUSTANI = setOf(
        "hai", "hain", "hay", "kya", "kia", "kyaa", "karo", "kar", "kro", "krdo", "kardo", "mujhe", "mujhy", "muje", "mera", "meri", "mere",
        "aap", "ap", "tum", "tera", "teri", "nahi", "nahin", "nhi", "kaise", "kaisa", "kaisi", "kesy", "kese", "kyun", "kyon", "kahan", "kab",
        "ko", "ka", "ki", "ke", "se", "mein", "main", "hun", "hoon", "hu", "bhai", "yaar", "yar", "acha", "achha", "accha", "theek", "thik",
        "batao", "bata", "btao", "kholo", "chalao", "lagao", "karna", "chahiye", "chahie", "wala", "wali", "abhi", "aaj", "kal", "sab",
        "kuch", "koi", "bohat", "bahut", "zara", "jaldi", "salam", "shukriya", "kaun", "kon", "kitna", "kitne", "kitni", "dikhao",
        "sunao", "padho", "bhejo", "likho", "rakho", "dena", "dedo", "do", "lo", "jao", "aao", "bolo", "suno", "chahta", "chahti"
    )
    // Words that are also common English — don't count them alone.
    private val AMBIGUOUS = setOf("do", "main", "ko", "se", "ka", "ki", "ke", "ap", "hay", "kar", "lo", "hu", "kal", "sab")

    fun detect(text: String): String {
        if (Hindustani.isUrduScript(text)) return "ur"
        if (Hindustani.isDevanagari(text)) return "hi"
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "en"
        val strong = words.count { it in ROMAN_HINDUSTANI && it !in AMBIGUOUS }
        val weak = words.count { it in AMBIGUOUS }
        return if (strong >= 2 || (strong >= 1 && weak >= 1) || (strong >= 1 && words.size <= 3)) "ur" else "en"
    }

    /** Maps a recognizer language tag ("ur-PK", "hi-IN", "en-US") to "ur" / "hi" / "en". */
    fun fromTag(tag: String?): String? {
        val t = tag?.lowercase(Locale.ROOT) ?: return null
        return when {
            t.startsWith("ur") -> "ur"
            t.startsWith("hi") -> "hi"
            t.startsWith("en") -> "en"
            else -> null
        }
    }
}
