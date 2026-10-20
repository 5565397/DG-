package com.jarvis.assistant

import com.jarvis.assistant.CommandParser.Action
import com.jarvis.assistant.CommandParser.Media
import com.jarvis.assistant.CommandParser.Ringer
import com.jarvis.assistant.CommandParser.Switch
import com.jarvis.assistant.CommandParser.Thing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Urdu and Hindi commands that must work with no AI key at all. */
class UrduOfflineTest {

    private fun ur(text: String) = CommandParser.parse(text, nonEnglishMode = true)

    // ---------------- Alarms, timers, reminders ----------------

    @Test fun urduAlarms() {
        assertEquals(Action.Alarm(6, 0, null), ur("صبح چھ بجے کا الارم لگاؤ"))
        assertEquals(Action.Alarm(17, 30, null), ur("شام ساڑھے پانچ بجے الارم لگا دو"))
        assertEquals(Action.Alarm(7, 15, null), ur("صبح سوا سات بجے الارم لگاؤ"))
        assertEquals(Action.Alarm(8, 45, null), ur("صبح پونے نو بجے الارم لگاؤ"))
        assertEquals(Action.Alarm(22, 0, null), ur("رات دس بجے الارم لگاؤ"))
        assertEquals(Action.Alarm(14, 0, null), ur("دوپہر دو بجے الارم لگاؤ"))
        assertEquals(Action.Alarm(5, 0, null), ur("مجھے صبح پانچ بجے جگا دینا"))
    }

    @Test fun urduDigits() {
        assertEquals(Action.Alarm(6, 0, null), ur("صبح ۶ بجے کا الارم لگاؤ"))
        assertEquals(Action.Timer(900, null), ur("۱۵ منٹ کا ٹائمر لگاؤ"))
    }

    @Test fun hindiAlarms() {
        assertEquals(Action.Alarm(6, 30, null), ur("सुबह 6:30 बजे का अलार्म लगाओ"))
        assertEquals(Action.Alarm(22, 0, null), ur("रात 10 बजे अलार्म"))
        assertEquals(Action.Alarm(19, 30, null), ur("शाम साढ़े सात बजे अलार्म लगा दो"))
    }

    @Test fun timers() {
        assertEquals(Action.Timer(600, null), ur("دس منٹ کا ٹائمر لگاؤ"))
        assertEquals(Action.Timer(1800, null), ur("आधा घंटा का टाइमर"))
        assertEquals(Action.Timer(5400, null), ur("ڈیڑھ گھنٹے کا ٹائمر لگا دو"))
        assertEquals(Action.Timer(600, null), ur("10 minute ka timer"))
    }

    @Test fun reminders() {
        assertEquals(Action.Alarm(17, 0, "دوائی لینی ہے"), ur("مجھے شام پانچ بجے یاد دلانا کہ دوائی لینی ہے"))
        assertEquals(Action.Timer(600, "چائے بند کرنی ہے"), ur("دس منٹ بعد یاد دلانا کہ چائے بند کرنی ہے"))
        assertEquals(Action.Timer(1200, "माँ को फोन करना है"), ur("बीस मिनट बाद याद दिलाना कि माँ को फोन करना है"))
    }

    // ---------------- Calls & messages ----------------

    @Test fun calls() {
        assertEquals(Action.Call("علی"), ur("علی کو کال کرو"))
        assertEquals(Action.Call("امی"), ur("امی کو فون ملاؤ"))
        assertEquals(Action.Call("अम्मी"), ur("अम्मी को कॉल करो"))
        assertEquals(Action.Call("ali"), ur("ali ko call karo"))
    }

    @Test fun messages() {
        assertEquals(Action.Sms("علی", "میں آ رہا ہوں"), ur("علی کو میسج کرو کہ میں آ رہا ہوں"))
        assertEquals(Action.Sms("अली", "मैं आ रहा हूँ"), ur("अली को मैसेज भेजो कि मैं आ रहा हूँ"))
        assertEquals(Action.WhatsApp("سارہ", "آٹھ بجے ملتے ہیں"), ur("سارہ کو واٹس ایپ کرو کہ آٹھ بجے ملتے ہیں"))
    }

    // ---------------- Apps ----------------

    @Test fun openAppsByUrduName() {
        assertEquals(Action.OpenApp("youtube"), ur("یوٹیوب کھولو"))
        assertEquals(Action.OpenApp("instagram"), ur("انسٹاگرام کھولو"))
        assertEquals(Action.OpenApp("instagram"), ur("इंस्टाग्राम खोलो"))
        assertEquals(Action.OpenApp("whatsapp"), ur("واٹس ایپ کھولو"))
        assertEquals(Action.OpenApp("settings"), ur("سیٹنگز کھولو"))
        assertEquals(Action.OpenApp("کریم"), ur("کریم کھولو")) // unknown name: matched to installed apps by sound
    }

    // ---------------- Phone controls ----------------

    @Test fun toggles() {
        assertEquals(Action.Toggle(Thing.BLUETOOTH, Switch.ON), ur("بلوٹوتھ آن کرو"))
        assertEquals(Action.Toggle(Thing.WIFI, Switch.OFF), ur("वाईफाई बंद करो"))
        assertEquals(Action.Toggle(Thing.HOTSPOT, Switch.ON), ur("ہاٹ سپاٹ چالو کرو"))
        assertEquals(Action.Toggle(Thing.LOCATION, Switch.OFF), ur("لوکیشن بند کرو"))
    }

    @Test fun volumeAndBrightness() {
        assertEquals(Action.VolumeSet(50), ur("آواز پچاس فیصد کر دو"))
        assertEquals(Action.VolumeSet(30), ur("आवाज़ 30 प्रतिशत करो"))
        assertEquals(Action.BrightnessStep(false), ur("ब्राइटनेस कम करो"))
        assertEquals(Action.BrightnessStep(true), ur("روشنی بڑھاؤ"))
        assertEquals(Action.BrightnessSet(80), ur("برائٹنس اسی فیصد کر دو"))
    }

    @Test fun ringerAndMedia() {
        assertEquals(Action.RingerMode(Ringer.SILENT), ur("فون سائلنٹ کر دو"))
        assertEquals(Action.RingerMode(Ringer.VIBRATE), ur("वाइब्रेट मोड करो"))
        assertEquals(Action.MediaKey(Media.PAUSE), ur("گانا روکو"))
        assertEquals(Action.MediaKey(Media.NEXT), ur("اگلا گانا"))
        assertEquals(Action.MediaKey(Media.PREVIOUS), ur("पिछला गाना"))
        assertEquals(Action.YouTube("عاطف اسلم songs"), ur("عاطف اسلم کے گانے چلاؤ"))
    }

    @Test fun navigation() {
        assertEquals(Action.Navigate("لاہور ایئرپورٹ"), ur("لاہور ایئرپورٹ کا راستہ دکھاؤ"))
        assertEquals(Action.Navigate("घर"), ur("घर ले चलो"))
    }

    // ---------------- Talking to Voxa ----------------

    @Test fun conversation() {
        assertEquals(Action.Date, ur("آج کیا تاریخ ہے"))
        assertEquals(Action.WhoAreYou, ur("تم کون ہو"))
        assertEquals(Action.Thanks, ur("شکریہ"))
        assertEquals(Action.Briefing, ur("صبح بخیر"))
        assertEquals(Action.Help, ur("میری مدد کرو"))
        assertEquals(Action.ReadScreen, ur("اسکرین پڑھو"))
        assertEquals(Action.Recall, ur("کیا یاد ہے"))
        assertEquals(Action.Remember("گاڑی بیسمنٹ میں ہے"), ur("یاد رکھو کہ گاڑی بیسمنٹ میں ہے"))
    }

    @Test fun romanUrdu() {
        assertEquals(Action.Alarm(7, 0, null), ur("subah 7 baje alarm lagao"))
        assertEquals(Action.Call("ali"), ur("ali ko phone lagao"))
    }

    // ---------------- Wake word ----------------

    @Test fun wakeWord() {
        assertEquals("open youtube", WakeWord.extract("Voxa open YouTube"))
        assertEquals("ٹارچ جلاؤ", WakeWord.extract("ووکسا، ٹارچ جلاؤ"))
        assertEquals("टॉर्च जलाओ", WakeWord.extract("वोक्सा टॉर्च जलाओ"))
        assertEquals("", WakeWord.extract("hey voxa"))
        assertNull(WakeWord.extract("open youtube"))
    }

    // ---------------- Matching names across scripts ----------------

    @Test fun translit() {
        assertEquals(Translit.skeleton("Instagram"), Translit.skeleton("انسٹاگرام"))
        assertEquals(Translit.skeleton("Instagram"), Translit.skeleton("इंस्टाग्राम"))
        assertEquals(1f, Translit.similarity("امی", "Ammi"), 0f)
        assertEquals("Ali Bhai", Translit.bestMatch("علی بھائی", listOf("Alina", "Ali Bhai"), { it }))
        assertEquals("Facebook", Translit.bestMatch("فیس بک", listOf("Firefox", "Facebook"), { it }))
        assertEquals("WhatsApp", Translit.bestMatch("واٹس ایپ", listOf("Waze", "WhatsApp"), { it }))
        assertNull(Translit.bestMatch("علی", listOf("Bilal", "Sara"), { it }))
    }

    @Test fun numbers() {
        assertEquals(5.0, Hindustani.number("پانچ")!!, 0.0)
        assertEquals(5.0, Hindustani.number("पाँच")!!, 0.0)
        assertEquals(5.0, Hindustani.number("۵")!!, 0.0)
        assertEquals(5.0, Hindustani.number("५")!!, 0.0)
        assertEquals(1.5, Hindustani.number("ڈیڑھ")!!, 0.0)
    }
}
