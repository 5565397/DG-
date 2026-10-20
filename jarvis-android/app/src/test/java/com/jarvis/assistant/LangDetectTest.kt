package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Voxa must answer in the language you used: English, Hindi or Urdu (including Roman Urdu). */
class LangDetectTest {

    @Test fun english() {
        for (s in listOf("open youtube", "what is the time", "do it now", "I am in the main hall", "Who is the prime minister of Pakistan?"))
            assertEquals(s, "en", LangDetect.detect(s))
    }

    @Test fun urduScript() {
        for (s in listOf("ٹارچ جلاؤ", "عمران خان کون ہے", "امی کو فون ملاؤ"))
            assertEquals(s, "ur", LangDetect.detect(s))
    }

    @Test fun hindiScript() {
        for (s in listOf("टॉर्च जलाओ", "आज मौसम कैसा है"))
            assertEquals(s, "hi", LangDetect.detect(s))
    }

    @Test fun romanUrdu() {
        for (s in listOf("youtube kholo", "mujhe batao kya hai", "ali ko call karo", "main ghar ja raha hoon", "aaj mausam kaisa hai", "bhai ye kya hai"))
            assertEquals(s, "ur", LangDetect.detect(s))
    }

    @Test fun recognizerTags() {
        assertEquals("ur", LangDetect.fromTag("ur-PK"))
        assertEquals("hi", LangDetect.fromTag("hi-IN"))
        assertEquals("en", LangDetect.fromTag("en-US"))
        assertNull(LangDetect.fromTag(null))
        assertNull(LangDetect.fromTag("fr-FR"))
    }

    @Test fun englishQuestionsStayEnglishInAutoMode() {
        // In Auto mode, English input is parsed without Urdu/Hindi keyword mapping.
        val c = CommandParser.parse("how do I lock my phone screen", nonEnglishMode = LangDetect.detect("how do I lock my phone screen") != "en")
        assertEquals(CommandParser.Action.Question("how do i lock my phone screen"), c)
    }
}
