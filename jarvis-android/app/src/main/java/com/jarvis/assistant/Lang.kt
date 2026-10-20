package com.jarvis.assistant

/**
 * Jarvis's own lines in English, Hindi and Urdu. Urdu falls back to the Hindi
 * (Devanagari) wording when the phone has no Urdu text-to-speech voice — spoken
 * aloud the two sound nearly the same.
 */
object Lang {
    enum class Line { YES, DIDNT_CATCH, GLITCH, VOICE_CHECK, VOICE_DENIED, MIC_FAIL, NO_BRAIN, CANT_DO, THINKING }

    fun get(prefs: Prefs, line: Line): String {
        val sir = prefs.title
        return when (mode(prefs)) {
            "hi" -> hindi(line, if (sir == "sir") "सर" else sir)
            "ur" -> urdu(line, if (sir == "sir") "سر" else sir)
            else -> english(line, sir)
        }
    }

    /** "en", "hi" or "ur" — the language Jarvis should talk in. */
    fun mode(prefs: Prefs): String = when {
        prefs.language.startsWith("hi") -> "hi"
        prefs.language.startsWith("ur") -> if (Speaker.urduVoice) "ur" else "hi"
        else -> "en"
    }

    private fun english(line: Line, sir: String) = when (line) {
        Line.YES -> "Yes, $sir?"
        Line.DIDNT_CATCH -> "I didn't catch that, $sir."
        Line.GLITCH -> "My hearing glitched. Try again, $sir."
        Line.VOICE_CHECK -> "Voice check, $sir. Say your passphrase after the beep."
        Line.VOICE_DENIED -> "Voice not recognized. Access denied."
        Line.MIC_FAIL -> "I couldn't use the microphone for the voice check. Try again, $sir."
        Line.NO_BRAIN -> "I only understand a few commands in this language without my AI brain, $sir. Add a Claude API key in my settings."
        Line.CANT_DO -> "I'm not sure how to do that on this phone, $sir."
        Line.THINKING -> "Thinking..."
    }

    private fun hindi(line: Line, sir: String) = when (line) {
        Line.YES -> "जी, $sir?"
        Line.DIDNT_CATCH -> "माफ़ कीजिए $sir, मैं ठीक से सुन नहीं पाया।"
        Line.GLITCH -> "सुनने में थोड़ी गड़बड़ हुई। फिर से बोलिए, $sir।"
        Line.VOICE_CHECK -> "आवाज़ की जाँच, $sir। बीप के बाद अपना पासफ़्रेज़ बोलिए।"
        Line.VOICE_DENIED -> "यह आवाज़ पहचानी नहीं गई। इजाज़त नहीं है।"
        Line.MIC_FAIL -> "आवाज़ की जाँच के लिए माइक नहीं मिला। फिर से कोशिश कीजिए, $sir।"
        Line.NO_BRAIN -> "AI के बिना मैं इस ज़बान में कुछ ही कमांड समझता हूँ, $sir। सेटिंग्स में Claude API key डाल दीजिए।"
        Line.CANT_DO -> "यह काम इस फ़ोन पर कैसे करूँ, मुझे पक्का नहीं पता, $sir।"
        Line.THINKING -> "सोच रहा हूँ..."
    }

    private fun urdu(line: Line, sir: String) = when (line) {
        Line.YES -> "جی، $sir؟"
        Line.DIDNT_CATCH -> "معاف کیجیے $sir، میں ٹھیک سے سن نہیں پایا۔"
        Line.GLITCH -> "سننے میں تھوڑی گڑبڑ ہوئی۔ دوبارہ بولیے، $sir۔"
        Line.VOICE_CHECK -> "آواز کی جانچ، $sir۔ بیپ کے بعد اپنا پاس فریز بولیے۔"
        Line.VOICE_DENIED -> "یہ آواز پہچانی نہیں گئی۔ اجازت نہیں ہے۔"
        Line.MIC_FAIL -> "آواز کی جانچ کے لیے مائیک نہیں ملا۔ دوبارہ کوشش کیجیے، $sir۔"
        Line.NO_BRAIN -> "اے آئی کے بغیر میں اس زبان میں چند ہی کمانڈز سمجھتا ہوں، $sir۔ سیٹنگز میں Claude API key ڈال دیجیے۔"
        Line.CANT_DO -> "یہ کام اس فون پر کیسے کروں، مجھے پکا نہیں پتا، $sir۔"
        Line.THINKING -> "سوچ رہا ہوں..."
    }
}
