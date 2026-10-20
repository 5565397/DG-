package com.jarvis.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Matches names across scripts, fully offline: "انسٹاگرام" or "इंस्टाग्राम" finds the app
 * "Instagram", and "امی" finds the contact "Ammi". It reduces every name to a consonant
 * "skeleton" (how it sounds, roughly) and compares skeletons.
 */
object Translit {

    /** Common app names as people say them in Urdu / Hindi, mapped to their English names. */
    private val KNOWN_APPS = mapOf(
        "یوٹیوب" to "youtube", "یو ٹیوب" to "youtube", "यूट्यूब" to "youtube", "यू ट्यूब" to "youtube",
        "واٹس ایپ" to "whatsapp", "واٹساپ" to "whatsapp", "وٹس ایپ" to "whatsapp", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "فیس بک" to "facebook", "फेसबुक" to "facebook",
        "انسٹاگرام" to "instagram", "انسٹا" to "instagram", "इंस्टाग्राम" to "instagram", "इंस्टा" to "instagram",
        "ٹک ٹاک" to "tiktok", "टिकटॉक" to "tiktok", "टिक टॉक" to "tiktok",
        "کروم" to "chrome", "क्रोम" to "chrome", "براؤزر" to "chrome", "ब्राउज़र" to "chrome",
        "جی میل" to "gmail", "जीमेल" to "gmail", "ای میل" to "gmail", "ईमेल" to "gmail",
        "میپس" to "maps", "گوگل میپس" to "maps", "نقشہ" to "maps", "मैप्स" to "maps", "गूगल मैप्स" to "maps", "नक्शा" to "maps",
        "کیمرا" to "camera", "کیمرہ" to "camera", "कैमरा" to "camera",
        "گیلری" to "gallery", "تصویریں" to "gallery", "गैलरी" to "gallery", "फोटो" to "photos", "فوٹوز" to "photos", "फ़ोटोज़" to "photos",
        "سیٹنگز" to "settings", "سیٹنگ" to "settings", "ترتیبات" to "settings", "सेटिंग्स" to "settings", "सेटिंग" to "settings",
        "کیلکولیٹر" to "calculator", "कैलकुलेटर" to "calculator",
        "گھڑی" to "clock", "کلاک" to "clock", "घड़ी" to "clock", "क्लॉक" to "clock",
        "کیلنڈر" to "calendar", "कैलेंडर" to "calendar",
        "پلے سٹور" to "play store", "پلے اسٹور" to "play store", "प्ले स्टोर" to "play store",
        "ٹیلیگرام" to "telegram", "टेलीग्राम" to "telegram",
        "سنیپ چیٹ" to "snapchat", "स्नैपचैट" to "snapchat",
        "اسپاٹیفائی" to "spotify", "سپاٹیفائی" to "spotify", "स्पॉटिफाई" to "spotify",
        "نیٹ فلکس" to "netflix", "नेटफ्लिक्स" to "netflix",
        "میسنجر" to "messenger", "मैसेंजर" to "messenger",
        "ڈائلر" to "phone", "فون" to "phone", "डायलर" to "phone",
        "میسجز" to "messages", "پیغامات" to "messages", "मैसेजेस" to "messages",
        "فائلز" to "files", "फाइल्स" to "files", "फ़ाइलें" to "files",
        "ٹویٹر" to "x", "ایکس" to "x", "ट्विटर" to "x"
    )

    fun isLatin(text: String) = text.none { Character.UnicodeBlock.of(it).let { b -> b == Character.UnicodeBlock.DEVANAGARI || b == Character.UnicodeBlock.ARABIC || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B } }

    /** English app name for a spoken Urdu/Hindi app name, if it's a well-known one. */
    fun knownApp(spoken: String): String? {
        val s = nfc(spoken.trim())
        return KNOWN_APPS[s] ?: KNOWN_APPS.entries.firstOrNull { nfc(it.key) == s }?.value
    }

    /** Rough sound-skeleton: consonants only, similar sounds merged. Works for Latin, Urdu and Devanagari. */
    fun skeleton(name: String): String {
        val latin = toLatin(name).lowercase(Locale.ROOT)
        val s = latin
            .replace("chr", "k").replace("ch", "c").replace("sh", "s").replace("kh", "k").replace("gh", "g")
            .replace("ph", "f").replace("th", "t").replace("dh", "d").replace("bh", "b").replace("ck", "k")
            .replace(Regex("c(?=[eiy])"), "s")
            .replace('q', 'k').replace('v', 'w').replace('z', 's').replace("x", "ks")
        val out = StringBuilder()
        for (ch in s) {
            if (ch !in 'a'..'z') continue
            if (ch in "aeiouyhw") continue
            if (out.isNotEmpty() && out.last() == ch) continue
            out.append(ch)
        }
        return out.toString()
    }

    /** 0..1 — how alike two names sound. */
    fun similarity(a: String, b: String): Float {
        val x = skeleton(a)
        val y = skeleton(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        // Very short names must match exactly — too easy to confuse otherwise.
        if (minOf(x.length, y.length) <= 2) return 0f
        val d = levenshtein(x, y)
        return 1f - d.toFloat() / maxOf(x.length, y.length)
    }

    /**
     * Picks a contact for a spoken name: an exact name first, then a contact whose name holds every
     * spoken word as a whole word ("ali" -> "Ali Raza"), then a match by sound. A name is never
     * matched by letters buried inside another word ("ali" must not pick "AdXalive").
     */
    fun <T> bestName(spoken: String, candidates: List<T>, label: (T) -> String): T? {
        val q = words(spoken)
        if (q.isEmpty()) return null
        candidates.firstOrNull { words(label(it)) == q }?.let { return it }
        candidates.filter { c -> words(label(c)).containsAll(q) }
            .minByOrNull { words(label(it)).size }?.let { return it }
        return bestMatch(spoken, candidates, label)
    }

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }

    /** Best candidate by sound, or null if nothing is close enough. */
    fun <T> bestMatch(spoken: String, candidates: List<T>, label: (T) -> String, threshold: Float = 0.75f): T? {
        var best: T? = null
        var bestScore = 0f
        for (c in candidates) {
            val full = label(c)
            // Compare with the whole label and with its first word ("Ali Bhai" -> "Ali").
            val score = maxOf(similarity(spoken, full), similarity(spoken, full.split(' ').first()) - 0.05f)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= threshold) best else null
    }

    // ---------------------------------------------------------------- transliteration

    private val URDU = mapOf(
        'ا' to "a", 'آ' to "a", 'أ' to "a", 'إ' to "i", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s",
        'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh", 'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r",
        'ز' to "z", 'ژ' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a",
        'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ک' to "k", 'ك' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ں' to "n", 'ہ' to "h", 'ھ' to "h", 'ه' to "h", 'ۃ' to "h", 'ة' to "h", 'ء' to "", 'ی' to "y", 'ي' to "y",
        'ے' to "e", 'ۓ' to "e", 'ئ' to "y", 'ؤ' to "o"
    )

    private val DEVANAGARI = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "ch", 'ज' to "j", 'झ' to "j",
        'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
        'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r",
        'ल' to "l", 'व' to "w", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ं' to "n", 'ः' to "h",
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "e", 'ओ' to "o", 'औ' to "o", 'ऑ' to "o"
    )

    /** Letters that change sound with a nukta dot (ज़ = z, फ़ = f, ड़ = r ...). */
    private val NUKTA = mapOf('ज' to "z", 'फ' to "f", 'ड' to "r", 'ढ' to "r", 'क' to "q", 'ख' to "kh", 'ग' to "gh")

    fun toLatin(text: String): String {
        val s = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val next = s.getOrNull(i + 1)
            when {
                ch == 'و' -> out.append(if (out.isEmpty() || out.last() == ' ') "w" else "o")
                ch in URDU -> out.append(URDU[ch])
                ch in DEVANAGARI && next == '़' && ch in NUKTA -> {
                    out.append(NUKTA[ch]); i++
                }
                ch in DEVANAGARI -> out.append(DEVANAGARI[ch])
                ch.isLetterOrDigit() && ch.code < 0x250 -> out.append(ch)
                ch.isWhitespace() -> out.append(' ')
                else -> {} // vowel signs, virama, harakat — skipped
            }
            i++
        }
        return out.toString()
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
