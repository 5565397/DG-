package com.jarvis.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Understands the most common phone commands spoken in Hindi or Urdu — in Devanagari,
 * Urdu script, or Roman letters ("youtube kholo") — and rewrites them as the English
 * commands [CommandProcessor] already knows. Works offline.
 *
 * Anything it can't map (and isn't plain English) is sent to the AI brain, which can
 * answer in Hindi/Urdu or translate it into a phone command.
 */
object Hindustani {

    private val devanagari = Regex("[\\u0900-\\u097F]")
    private val arabicScript = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val latinWord = Regex("^[a-zA-Z0-9 .'&+-]+$")

    fun isDevanagari(text: String) = devanagari.containsMatchIn(text)
    fun isUrduScript(text: String) = arabicScript.containsMatchIn(text)
    fun isNonLatin(text: String) = isDevanagari(text) || isUrduScript(text)

    // ---- Word lists (Devanagari | Urdu script | Roman) ----
    private val torch = listOf("टॉर्च", "टोर्च", "फ्लैशलाइट", "फ्लैश लाइट", "ٹارچ", "فلیش لائٹ", "torch", "flashlight")
    private val on = listOf("चालू", "ऑन", "जला", "जलाओ", "चला", "چالو", "آن", "جلا", "جلاؤ", "on", "chalu", "jala", "jalao")
    private val off = listOf("बंद", "ऑफ", "बुझा", "بند", "آف", "بجھا", "band", "off", "bujha")
    private val volume = listOf("आवाज़", "आवाज", "वॉल्यूम", "वॉल्युम", "آواز", "والیوم", "awaz", "awaaz", "aawaz", "volume")
    private val up = listOf("बढ़ा", "बढ़ाओ", "बढाओ", "तेज़", "तेज", "ज़्यादा", "ज्यादा", "ऊपर", "بڑھا", "بڑھاؤ", "تیز", "زیادہ", "اونچی",
        "badha", "badhao", "barhao", "tez", "zyada", "ziada", "upar", "oonchi")
    private val down = listOf("कम", "धीमी", "धीमा", "नीचे", "کم", "دھیمی", "آہستہ", "نیچے", "kam", "dheemi", "dheema", "neeche", "aahista")
    private val mute = listOf("म्यूट", "चुप", "میوٹ", "mute")
    private val time = listOf("टाइम", "समय", "वक़्त", "वक्त", "ٹائم", "وقت", "time", "waqt", "samay")
    private val what = listOf("क्या", "कितने", "कितना", "कितनी", "बताओ", "کیا", "کتنے", "کتنا", "کتنی", "بتاؤ", "kya", "kitne", "kitna", "batao")
    private val date = listOf("तारीख", "तारीख़", "दिनांक", "تاریخ", "tareekh", "tarikh")
    private val battery = listOf("बैटरी", "चार्ज", "بیٹری", "چارج", "battery", "charge")
    private val screenshot = listOf("स्क्रीनशॉट", "स्क्रीन शॉट", "اسکرین شاٹ", "سکرین شاٹ", "screenshot", "screen shot")
    private val phone = listOf("फोन", "फ़ोन", "मोबाइल", "स्क्रीन", "فون", "موبائل", "اسکرین", "phone", "mobile", "screen")
    private val lock = listOf("लॉक", "لاک", "lock")
    private val scroll = listOf("स्क्रॉल", "سکرول", "اسکرول", "scroll")
    private val home = listOf("होम", "ہوم", "home")
    private val back = listOf("वापस", "पीछे", "واپس", "پیچھے", "wapas", "wapis", "peeche")
    private val go = listOf("जाओ", "चलो", "جاؤ", "چلو", "jao", "chalo")
    private val sleep = listOf("सो जाओ", "चुप हो जाओ", "बंद हो जाओ", "سو جاؤ", "خاموش ہو جاؤ", "بند ہو جاؤ", "so jao", "chup ho jao", "band ho jao")
    private val hello = listOf("नमस्ते", "नमस्कार", "हेलो", "सलाम", "अस्सलाम", "السلام", "سلام", "ہیلو", "namaste", "salam", "assalam", "hello")
    private val newChat = listOf("नई बात", "नयी बात", "सब भूल जाओ", "نئی بات", "سب بھول جاؤ", "nayi baat", "nai baat", "sab bhool jao")
    private val notifications = listOf("नोटिफिकेशन", "نوٹیفکیشن", "notification")
    private val messages = listOf("मैसेज", "मेसेज", "संदेश", "میسج", "میسیج", "پیغام", "message", "messages", "msg")
    private val readVerbs = listOf("पढ़ो", "पढ़कर", "सुनाओ", "बताओ", "پڑھو", "پڑھ کر", "سناؤ", "بتاؤ", "padho", "parho", "sunao", "batao")
    private val screenWords = listOf("स्क्रीन", "اسکرین", "سکرین", "screen")
    private val camera = listOf("कैमरा", "फोटो", "تصویر", "کیمرا", "camera", "photo", "tasveer")
    private val selfie = listOf("सेल्फी", "سیلفی", "selfie")

    private val openVerbs = "खोलो|खोल दो|खोलिए|खोल|ओपन करो|ओपन कर दो|चालू करो|लगाओ|کھولو|کھول دو|کھولیں|کھول|اوپن کرو|اوپن کر دو|kholo|khol do|kholiye|khol|open karo|open kar do"
    private val openFront = "ओपन|اوپن|open"
    private val callVerbs = "को कॉल करो|को कॉल कर दो|को फोन करो|को फ़ोन करो|को फोन लगाओ|कॉल करो|फोन करो|फ़ोन करो|کو کال کرو|کو کال کر دو|کو فون کرو|کو فون لگاؤ|کال کرو|فون کرو|ko call karo|ko call kar do|ko phone karo|ko phone lagao|call karo|phone karo|phone lagao"
    private val youtube = "यूट्यूब|यू ट्यूब|یوٹیوب|youtube"
    private val onWord = "पर|पे|में|پر|پے|میں|par|pe|mein"
    private val playVerbs = "चलाओ|लगाओ|बजाओ|चला दो|लगा दो|چلاؤ|لگاؤ|بجاؤ|چلا دو|لگا دو|chalao|lagao|bajao|chala do|laga do"
    private val searchVerbs = "सर्च करो|खोजो|ढूंढो|ढूँढो|सर्च कर दो|سرچ کرو|تلاش کرو|ڈھونڈو|سرچ کر دو|search karo|khojo|dhundo|dhoondo|talash karo"

    /** Returns an English command, or null if this isn't one of the known Hindi/Urdu commands. */
    fun toEnglish(input: String): String? {
        val t = input.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}&&[^'&+-]]"), " ") // drop punctuation like । ؟ , .
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return null
        fun has(words: List<String>) = words.any { containsWord(t, it) }
        fun m(p: String) = Regex(p).matchEntire(t)

        // Phrases with a free-text part go first (they work with any script).
        m("(?:याद रखो|याद रखना|یاد رکھو|یاد رکھنا|yaad rakho|yaad rakhna)(?: कि| که| کہ| ki| ke)? (.+)")?.let { return "remember ${it.groupValues[1]}" }
        m("(.+?) (?:याद रखो|याद रखना|یاد رکھو|یاد رکھنا|yaad rakho|yaad rakhna)")?.let { return "remember ${it.groupValues[1]}" }
        m("(.+?) (?:$youtube) (?:$onWord) (?:$playVerbs)")?.let { return "play ${it.groupValues[1]} on youtube" }
        m("(?:$youtube) (?:$onWord) (.+?) (?:$playVerbs|$searchVerbs)")?.let { return "play ${it.groupValues[1]} on youtube" }
        m("(.+?) (?:$searchVerbs)")?.let { return "search for ${it.groupValues[1]}" }

        if (has(newChat)) return "new conversation"
        if (has(sleep)) return "stop listening"
        if (has(torch)) return if (has(off)) "turn off the flashlight" else "turn on the flashlight"
        if (has(volume)) {
            if (has(up)) return "volume up"
            if (has(down)) return "volume down"
            if (has(off) || has(mute)) return "mute"
        }
        if (has(mute) && !has(volume)) return "mute"
        if (has(screenshot)) return "take a screenshot"
        if (has(lock) && has(phone)) return "lock the phone"
        if (has(scroll)) return if (has(listOf("ऊपर", "اوپر", "upar"))) "scroll up" else "scroll down"
        if (has(home) && (has(go) || t.split(' ').size <= 2)) return "go home"
        if (has(back) && has(go)) return "go back"
        if ((has(notifications) || has(messages)) && has(readVerbs)) return "read my notifications"
        if (has(notifications)) return "notifications"
        if (has(screenWords) && has(what)) return "what's on my screen"
        if (has(selfie)) return "take a selfie"
        if (has(camera) && has(listOf("खोलो", "खींचो", "लो", "کھولو", "کھینچو", "لو", "kholo", "khencho", "lo"))) return "take a photo"
        if (has(time) && has(what)) return "what time is it"
        if (has(date) && has(what)) return "what's the date"
        if (has(battery) && (has(what) || t.split(' ').size <= 3)) return "battery level"
        if (t.split(' ').size <= 3 && has(hello)) return "hello"

        // App / contact names must be in English letters for the offline route;
        // anything else goes to the AI brain, which transliterates.
        m("(.+?) (?:$openVerbs)")?.let { if (latinWord.matches(it.groupValues[1])) return "open ${it.groupValues[1]}" }
        m("(?:$openFront) (.+?)(?: (?:$openVerbs))?")?.let { if (latinWord.matches(it.groupValues[1])) return "open ${it.groupValues[1]}" }
        m("(.+?) (?:$callVerbs)")?.let { if (latinWord.matches(it.groupValues[1])) return "call ${it.groupValues[1]}" }
        return null
    }

    /** Whole-word match that also works for Devanagari / Urdu (Java's \b is ASCII-only). */
    private fun containsWord(rawText: String, rawWord: String): Boolean {
        // Same letter can arrive precomposed (ज़) or as letter + nukta (ज़); compare one form.
        val text = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        val word = Normalizer.normalize(rawWord, Normalizer.Form.NFC)
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val end = i + word.length
            val after = if (end >= text.length) ' ' else text[end]
            if (!isWordChar(before) && !isWordChar(after)) return true
            from = i + 1
        }
    }

    private fun isWordChar(ch: Char): Boolean {
        val type = Character.getType(ch)
        return Character.isLetterOrDigit(ch) ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }
}
