package com.jarvis.assistant

import com.jarvis.assistant.CommandParser.Action
import com.jarvis.assistant.CommandParser.Global
import com.jarvis.assistant.CommandParser.Media
import com.jarvis.assistant.CommandParser.Ringer
import com.jarvis.assistant.CommandParser.Switch
import com.jarvis.assistant.CommandParser.Thing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun en(text: String) = CommandParser.parse(text, nonEnglishMode = false)
    private fun hi(text: String) = CommandParser.parse(text, nonEnglishMode = true)

    private fun check(expected: Action, vararg phrases: String, parse: (String) -> Action = ::en) {
        for (p in phrases) assertEquals("\"$p\"", expected, parse(p))
    }

    // ---------------- Jarvis itself ----------------

    @Test fun stopListening() = check(Action.StopListening, "stop listening", "Go to sleep.", "goodbye", "that's all", "Jarvis, stop")
    @Test fun relock() = check(Action.Relock, "lock yourself", "voice lock")
    @Test fun newChat() = check(Action.NewChat, "new conversation", "start over", "forget that")
    @Test fun greet() = check(Action.Greet, "hello", "Hi Jarvis", "are you there?", "good evening")
    @Test fun briefing() = check(Action.Briefing, "good morning", "brief me", "daily briefing", "what's my day look like")
    @Test fun help() = check(Action.Help, "help", "what can you do?")
    @Test fun thanks() = check(Action.Thanks, "thank you", "thanks jarvis", "good job")
    @Test fun empty() = check(Action.Empty, "", "  ", "please")

    // ---------------- Info ----------------

    @Test fun time() = check(Action.Time, "what time is it", "What's the time?", "time", "tell me the time", "can you tell me the time")
    @Test fun date() = check(Action.Date, "what's the date", "what day is it today", "today's date")
    @Test fun battery() = check(Action.Battery, "battery", "what's my battery level", "how much battery is left")

    // ---------------- Memory ----------------

    @Test fun remember() {
        assertEquals(Action.Remember("my car is on level 3"), en("Remember that my car is on level 3."))
        assertEquals(Action.Remember("the wifi password is tiger123"), en("remember the wifi password is tiger123"))
        assertEquals(Action.Remember("milk"), en("don't forget milk"))
    }

    @Test fun recallAndClear() {
        check(Action.Recall, "what do you remember", "what did I tell you to remember", "show your memories")
        check(Action.ClearMemory, "forget everything you remember", "clear your memory", "clear memory")
    }

    // ---------------- Screen & notifications ----------------

    @Test fun readNotifications() = check(
        Action.ReadNotifications,
        "read my notifications", "any new messages?", "what did I miss", "who texted me", "read my messages"
    )

    @Test fun describeScreen() {
        check(Action.DescribeScreen(null), "what's on my screen", "describe the screen", "what am I looking at")
        assertEquals(Action.DescribeScreen("translate this"), en("look at my screen and translate this"))
    }

    @Test fun readScreen() = check(Action.ReadScreen, "read the screen", "read this page to me")

    @Test fun globals() {
        check(Action.GlobalAction(Global.HOME), "go home", "home", "home screen")
        check(Action.GlobalAction(Global.BACK), "go back", "back")
        check(Action.GlobalAction(Global.RECENTS), "recent apps", "show the app switcher")
        check(Action.GlobalAction(Global.NOTIFICATIONS), "open notifications", "pull down the notification shade")
        check(Action.GlobalAction(Global.QUICK_SETTINGS), "quick settings")
        check(Action.GlobalAction(Global.POWER), "power menu")
    }

    @Test fun screenshotAndLock() {
        check(Action.Screenshot, "take a screenshot", "screenshot", "grab a screen shot")
        check(Action.LockScreen, "lock the phone", "lock my screen", "screen off")
    }

    @Test fun scrollClickType() {
        assertEquals(Action.Scroll("down"), en("scroll down"))
        assertEquals(Action.Scroll("up"), en("swipe up"))
        assertEquals(Action.Scroll("down"), en("scroll"))
        assertEquals(Action.Click("subscribe"), en("tap subscribe"))
        assertEquals(Action.Click("sign in"), en("click on the sign in button"))
        assertEquals(Action.Type("hello there"), en("type hello there"))
    }

    @Test fun writingRequestsAreNotTyping() {
        assertTrue(en("write an email to my boss about leave") is Action.Question)
        assertTrue(en("write a poem about rain") is Action.Question)
    }

    // ---------------- Hardware ----------------

    @Test fun torch() {
        check(Action.Torch(true), "turn on the flashlight", "flashlight on", "torch", "switch on the torch", "enable flashlight")
        check(Action.Torch(false), "turn off the flashlight", "flashlight off", "torch off", "disable the flashlight")
    }

    @Test fun volume() {
        check(Action.VolumeStep(true), "volume up", "louder", "turn up the volume", "turn it up")
        check(Action.VolumeStep(false), "volume down", "quieter", "turn down the music")
        assertEquals(Action.VolumeSet(40), en("set volume to 40 percent"))
        assertEquals(Action.VolumeSet(40), en("volume 40%"))
        assertEquals(Action.VolumeSet(100), en("max volume"))
        check(Action.Mute(true), "mute", "mute the phone")
        check(Action.Mute(false), "unmute")
    }

    @Test fun ringer() {
        check(Action.RingerMode(Ringer.SILENT), "silent mode", "put my phone on silent")
        check(Action.RingerMode(Ringer.VIBRATE), "vibrate mode")
        check(Action.RingerMode(Ringer.NORMAL), "normal mode")
    }

    @Test fun media() {
        check(Action.MediaKey(Media.PAUSE), "pause", "pause the music", "stop the song")
        check(Action.MediaKey(Media.PLAY), "play", "resume", "play music")
        check(Action.MediaKey(Media.NEXT), "next song", "skip", "skip this song")
        check(Action.MediaKey(Media.PREVIOUS), "previous song", "previous")
    }

    @Test fun brightness() {
        assertEquals(Action.BrightnessSet(70), en("brightness 70 percent"))
        assertEquals(Action.BrightnessSet(70), en("set the brightness to 70"))
        assertEquals(Action.BrightnessSet(100), en("full brightness"))
        assertEquals(Action.BrightnessSet(1), en("minimum brightness"))
        assertEquals(Action.BrightnessStep(true), en("brighter"))
        assertEquals(Action.BrightnessStep(false), en("dim the screen"))
    }

    @Test fun toggles() {
        assertEquals(Action.Toggle(Thing.BLUETOOTH, Switch.ON), en("turn on bluetooth"))
        assertEquals(Action.Toggle(Thing.BLUETOOTH, Switch.OFF), en("turn bluetooth off"))
        assertEquals(Action.Toggle(Thing.WIFI, Switch.OFF), en("turn off wifi"))
        assertEquals(Action.Toggle(Thing.WIFI, Switch.ON), en("switch on the wi-fi"))
        assertEquals(Action.Toggle(Thing.AIRPLANE, Switch.ON), en("enable airplane mode"))
        assertEquals(Action.Toggle(Thing.HOTSPOT, Switch.ON), en("turn on hotspot"))
        assertEquals(Action.Toggle(Thing.LOCATION, Switch.OFF), en("turn off location"))
        assertEquals(Action.Toggle(Thing.DND, Switch.ON), en("turn on do not disturb"))
        assertEquals(Action.Toggle(Thing.DARK_MODE, Switch.TOGGLE), en("toggle dark mode"))
        assertEquals(Action.Toggle(Thing.MOBILE_DATA, Switch.OFF), en("turn off mobile data"))
    }

    // ---------------- Time-based ----------------

    @Test fun alarms() {
        assertEquals(Action.Alarm(6, 30, null), en("set an alarm for 6:30 am"))
        assertEquals(Action.Alarm(6, 30, null), en("Set an alarm for 6:30 a.m."))
        assertEquals(Action.Alarm(19, 0, null), en("wake me up at 7 pm"))
        assertEquals(Action.Alarm(7, 15, null), en("alarm 7 15"))
        assertEquals(Action.Alarm(21, 0, null), en("set an alarm for 9 at night"))
        assertEquals(Action.Alarm(0, 0, null), en("set an alarm for 12 am"))
        assertEquals(Action.Alarm(12, 0, null), en("set an alarm for 12 pm"))
    }

    @Test fun timers() {
        assertEquals(Action.Timer(600, null), en("set a timer for 10 minutes"))
        assertEquals(Action.Timer(60, null), en("timer for one minute"))
        assertEquals(Action.Timer(30, null), en("set a timer for 30 seconds"))
        assertEquals(Action.Timer(7200, null), en("set a timer for 2 hours"))
        assertEquals(Action.Timer(1800, null), en("set a timer for half an hour"))
        assertEquals(Action.Timer(300, null), en("5 minute timer"))
    }

    @Test fun reminders() {
        assertEquals(Action.Alarm(17, 0, "call ahmed"), en("remind me to call Ahmed at 5 pm"))
        assertEquals(Action.Alarm(8, 30, "take my medicine"), en("remind me at 8:30 am to take my medicine"))
        assertEquals(Action.Timer(1200, "check the oven"), en("remind me in 20 minutes to check the oven"))
        assertEquals(Action.Timer(3600, "move the car"), en("remind me to move the car in 1 hour"))
    }

    // ---------------- Communication ----------------

    @Test fun calls() {
        assertEquals(Action.Call("mom"), en("call mom"))
        assertEquals(Action.Call("0300 1234567"), en("call 0300 1234567"))
        assertEquals(Action.Call("ali"), en("Jarvis, can you call Ali now"))
    }

    @Test fun sms() {
        assertEquals(Action.Sms("ali", "i'm on my way"), en("send a message to Ali saying I'm on my way"))
        assertEquals(Action.Sms("mom", "i'll be late"), en("text mom that I'll be late"))
        assertEquals(Action.Sms("sara", "dinner is ready"), en("tell Sara that dinner is ready"))
    }

    @Test fun tellMeIsNotSms() {
        assertTrue(en("tell me that joke again") is Action.Question)
        assertTrue(en("tell me a joke") is Action.Question)
    }

    @Test fun whatsapp() {
        assertEquals(Action.WhatsApp("sara", "see you at 8"), en("WhatsApp Sara saying see you at 8"))
        assertEquals(Action.WhatsApp("ahmed", "call me"), en("send a whatsapp message to Ahmed saying call me"))
    }

    // ---------------- Apps, media, maps, web ----------------

    @Test fun youtube() {
        assertEquals(Action.YouTube("believer"), en("play Believer on YouTube"))
        assertEquals(Action.YouTube("lo-fi music"), en("youtube lo-fi music"))
        assertEquals(Action.YouTube("despacito"), en("play despacito"))
    }

    @Test fun maps() {
        assertEquals(Action.Navigate("the airport"), en("navigate to the airport"))
        assertEquals(Action.Navigate("lahore"), en("take me to Lahore"))
        assertEquals(Action.Navigate("the mall"), en("how do I get to the mall"))
        assertEquals(Action.ShowOnMap("times square"), en("show Times Square on the map"))
    }

    @Test fun camera() {
        check(Action.Camera(true), "take a selfie", "selfie")
        check(Action.Camera(false), "take a photo", "open the camera", "take a picture")
    }

    @Test fun search() {
        assertEquals(Action.Search("best pizza near me"), en("search for best pizza near me"))
        assertEquals(Action.Search("weather"), en("google weather"))
    }

    @Test fun settingsPages() {
        assertEquals(Action.SettingsPage("bluetooth"), en("open bluetooth settings"))
        assertEquals(Action.SettingsPage("wifi"), en("open wifi settings"))
    }

    @Test fun openApps() {
        assertEquals(Action.OpenApp("instagram"), en("open Instagram"))
        assertEquals(Action.OpenApp("whatsapp"), en("launch WhatsApp"))
        assertEquals(Action.OpenApp("google maps"), en("open the Google Maps app"))
        assertEquals(Action.OpenApp("settings"), en("open settings"))
    }

    @Test fun questions() {
        for (q in listOf(
            "who won the match last night", "what's the weather tomorrow", "how far is the moon",
            "explain black holes simply", "is it going to rain", "tell me a joke", "translate thank you to french"
        )) assertTrue("\"$q\"", en(q) is Action.Question)
    }

    @Test fun unknownFallsThrough() {
        assertEquals(Action.Unknown("spotify"), en("spotify"))
    }

    // ---------------- Hindi / Urdu ----------------

    @Test fun hindiCommands() {
        check(Action.Torch(true), "टॉर्च जलाओ", "टॉर्च चालू करो", parse = ::hi)
        check(Action.Torch(false), "टॉर्च बंद करो", parse = ::hi)
        check(Action.VolumeStep(true), "आवाज़ बढ़ाओ", parse = ::hi)
        check(Action.VolumeStep(false), "आवाज़ कम करो", parse = ::hi)
        check(Action.Time, "टाइम क्या हुआ है", "वक़्त क्या है", parse = ::hi)
        check(Action.Battery, "बैटरी कितनी है", parse = ::hi)
        check(Action.Screenshot, "स्क्रीनशॉट लो", parse = ::hi)
        check(Action.LockScreen, "फोन लॉक करो", parse = ::hi)
        check(Action.GlobalAction(Global.BACK), "वापस जाओ", parse = ::hi)
        check(Action.ReadNotifications, "मेरे मैसेज पढ़ो", "नोटिफिकेशन पढ़ो", parse = ::hi)
        check(Action.DescribeScreen(null), "स्क्रीन पर क्या है", parse = ::hi)
        check(Action.StopListening, "सो जाओ", parse = ::hi)
        assertEquals(Action.OpenApp("youtube"), hi("YouTube खोलो"))
        assertEquals(Action.YouTube("अरिजीत के गाने"), hi("अरिजीत के गाने यूट्यूब पर चलाओ"))
        assertEquals(Action.Remember("चाबी दराज़ में है"), hi("याद रखो कि चाबी दराज़ में है"))
    }

    @Test fun urduCommands() {
        check(Action.Torch(true), "ٹارچ جلاؤ", parse = ::hi)
        check(Action.Torch(false), "ٹارچ بند کرو", parse = ::hi)
        check(Action.VolumeStep(true), "آواز بڑھاؤ", parse = ::hi)
        check(Action.Time, "وقت کیا ہوا ہے", parse = ::hi)
        check(Action.Battery, "بیٹری کتنی ہے", parse = ::hi)
        check(Action.ReadNotifications, "میرے میسج پڑھو", parse = ::hi)
        check(Action.Greet, "السلام علیکم", parse = ::hi)
        assertEquals(Action.OpenApp("whatsapp"), hi("WhatsApp کھولو"))
        assertEquals(Action.Remember("گاڑی بیسمنٹ میں ہے"), hi("یاد رکھو کہ گاڑی بیسمنٹ میں ہے"))
    }

    @Test fun romanHinglish() {
        assertEquals(Action.OpenApp("youtube"), hi("youtube kholo"))
        check(Action.Torch(true), "torch jalao", parse = ::hi)
        check(Action.VolumeStep(true), "awaz badhao", parse = ::hi)
        check(Action.GlobalAction(Global.BACK), "wapas jao", parse = ::hi)
    }

    @Test fun unmappedHindiGoesToAi() {
        assertTrue(hi("कल मौसम कैसा रहेगा") is Action.Ask)
        assertTrue(en("अम्मी को कॉल करो") is Action.Ask) // Devanagari name: the AI transliterates it
    }

    @Test fun englishQuestionAboutLockingDoesNotLock() {
        // In English mode, Hindi keyword mapping must not hijack ordinary English.
        assertTrue(en("how do I lock my phone screen") is Action.Question)
    }
}
