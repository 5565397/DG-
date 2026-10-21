package com.jarvis.assistant

/**
 * Voxa's own lines in English, Hindi and Urdu, and which language to speak.
 * In "Auto" mode Voxa answers in the language of what you last said or typed.
 * Urdu falls back to the Hindi (Devanagari) wording when the phone has no Urdu
 * text-to-speech voice — spoken aloud the two sound nearly the same.
 */
object Lang {
    enum class Line { YES, DIDNT_CATCH, GLITCH, VOICE_CHECK, VOICE_DENIED, MIC_FAIL, NO_BRAIN, CANT_DO, THINKING, UNLOCK_FIRST }

    const val AUTO = "auto"

    /** Language of the latest thing the user said or typed ("en", "hi", "ur"). */
    @Volatile var lastInput: String = "ur"
        private set

    /** Call with every user message; [recognizerTag] is the language the recognizer detected, if any. */
    fun noteInput(text: String, recognizerTag: String? = null) {
        val byScript = LangDetect.detect(text)
        // Latin letters can be English or Roman Urdu; the recognizer's own detection settles it when it has one.
        lastInput = if (byScript == "en") LangDetect.fromTag(recognizerTag) ?: "en" else byScript
    }

    fun get(prefs: Prefs, line: Line): String {
        val sir = prefs.title
        return when (mode(prefs)) {
            "hi" -> hindi(line, if (sir == "sir") "सर" else sir)
            "ur" -> urdu(line, if (sir == "sir") "سر" else sir)
            HINGLISH -> hinglish(line, sir)
            else -> english(line, sir)
        }
    }

    /** Replies in Hindustani written with Roman letters ("Theek hai, sir"). */
    const val HINGLISH = "rm"

    /** "en", "hi", "ur" or [HINGLISH] — the language Voxa should talk in right now. */
    fun mode(prefs: Prefs): String {
        if (prefs.replyHinglish) return HINGLISH
        val wanted = when {
            prefs.language == AUTO -> lastInput
            prefs.language.startsWith("hi") -> "hi"
            prefs.language.startsWith("ur") -> "ur"
            else -> "en"
        }
        // Without an Urdu voice, Urdu script would be read in silence — use the Hindi voice instead.
        return if (wanted == "ur" && Speaker.voicesChecked && !Speaker.urduVoice) "hi" else wanted
    }

    /** Whether Urdu/Hindi command words should be recognised in the current input. */
    fun hindustaniInput(prefs: Prefs): Boolean = when {
        prefs.language == AUTO -> lastInput != "en"
        else -> !prefs.language.startsWith("en")
    }

    private fun english(line: Line, sir: String) = when (line) {
        Line.YES -> "Yes, $sir?"
        Line.DIDNT_CATCH -> "I didn't catch that, $sir."
        Line.GLITCH -> "My hearing glitched. Try again, $sir."
        Line.VOICE_CHECK -> "Voice check, $sir. Say your passphrase after the beep."
        Line.VOICE_DENIED -> "Voice not recognized. Access denied."
        Line.MIC_FAIL -> "I couldn't use the microphone for the voice check. Try again, $sir."
        Line.NO_BRAIN -> "Online AI is off, $sir. Turn on Groq in Settings to ask me anything."
        Line.CANT_DO -> "I'm not sure how to do that on this phone, $sir."
        Line.THINKING -> "Thinking…"
        Line.UNLOCK_FIRST -> "Please unlock your phone first, $sir, then I'll do it."
    }

    private fun hinglish(line: Line, sir: String) = when (line) {
        Line.YES -> "Ji, $sir?"
        Line.DIDNT_CATCH -> "Maaf kijiye $sir, theek se sun nahi payi."
        Line.GLITCH -> "Sunne mein thodi gadbad hui. Dobara boliye, $sir."
        Line.VOICE_CHECK -> "Awaaz ki jaanch, $sir. Beep ke baad apna passphrase boliye."
        Line.VOICE_DENIED -> "Ye awaaz pehchani nahi gayi. Ijazat nahi hai."
        Line.MIC_FAIL -> "Awaaz ki jaanch ke liye mic nahi mila. Dobara koshish kijiye, $sir."
        Line.NO_BRAIN -> "Online AI band hai, $sir. Kuch bhi poochne ke liye Settings mein Groq on kar dijiye."
        Line.CANT_DO -> "Ye kaam is phone par kaise karun, mujhe pakka nahi pata, $sir."
        Line.THINKING -> "Soch rahi hoon…"
        Line.UNLOCK_FIRST -> "Pehle phone unlock kar dijiye, $sir, phir main kar deti hoon."
    }

    private fun hindi(line: Line, sir: String) = when (line) {
        Line.YES -> "जी, $sir?"
        Line.DIDNT_CATCH -> "माफ़ कीजिए $sir, मैं ठीक से सुन नहीं पाई।"
        Line.GLITCH -> "सुनने में थोड़ी गड़बड़ हुई। फिर से बोलिए, $sir।"
        Line.VOICE_CHECK -> "आवाज़ की जाँच, $sir। बीप के बाद अपना पासफ़्रेज़ बोलिए।"
        Line.VOICE_DENIED -> "यह आवाज़ पहचानी नहीं गई। इजाज़त नहीं है।"
        Line.MIC_FAIL -> "आवाज़ की जाँच के लिए माइक नहीं मिला। फिर से कोशिश कीजिए, $sir।"
        Line.NO_BRAIN -> "ऑनलाइन AI बंद है, $sir। कुछ भी पूछने के लिए सेटिंग्स में Groq चालू कर दीजिए।"
        Line.CANT_DO -> "यह काम इस फ़ोन पर कैसे करूँ, मुझे पक्का नहीं पता, $sir।"
        Line.THINKING -> "सोच रही हूँ…"
        Line.UNLOCK_FIRST -> "पहले फ़ोन अनलॉक कर दीजिए, $sir, फिर मैं कर देती हूँ।"
    }

    private fun urdu(line: Line, sir: String) = when (line) {
        Line.YES -> "جی، $sir؟"
        Line.DIDNT_CATCH -> "معاف کیجیے $sir، میں ٹھیک سے سن نہیں پائی۔"
        Line.GLITCH -> "سننے میں تھوڑی گڑبڑ ہوئی۔ دوبارہ بولیے، $sir۔"
        Line.VOICE_CHECK -> "آواز کی جانچ، $sir۔ بیپ کے بعد اپنا پاس فریز بولیے۔"
        Line.VOICE_DENIED -> "یہ آواز پہچانی نہیں گئی۔ اجازت نہیں ہے۔"
        Line.MIC_FAIL -> "آواز کی جانچ کے لیے مائیک نہیں ملا۔ دوبارہ کوشش کیجیے، $sir۔"
        Line.NO_BRAIN -> "آن لائن اے آئی بند ہے، $sir۔ کچھ بھی پوچھنے کے لیے سیٹنگز میں Groq آن کر دیجیے۔"
        Line.CANT_DO -> "یہ کام اس فون پر کیسے کروں، مجھے پکا نہیں پتا، $sir۔"
        Line.THINKING -> "سوچ رہی ہوں…"
        Line.UNLOCK_FIRST -> "پہلے فون ان لاک کر دیجیے، $sir، پھر میں کر دیتی ہوں۔"
    }
}
