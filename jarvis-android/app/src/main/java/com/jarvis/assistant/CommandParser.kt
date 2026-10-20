package com.jarvis.assistant

import java.util.Locale

/**
 * Turns what the user said into an [Action]. Pure Kotlin with no Android calls, so every
 * phrase can be checked by unit tests (see CommandParserTest). [CommandProcessor] then
 * carries the action out on the phone.
 */
object CommandParser {

    enum class Global { HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER }
    enum class Ringer { SILENT, VIBRATE, NORMAL }
    enum class Media { PLAY, PAUSE, NEXT, PREVIOUS }
    enum class Thing { WIFI, BLUETOOTH, AIRPLANE, HOTSPOT, MOBILE_DATA, LOCATION, DND, ROTATION, BATTERY_SAVER, DARK_MODE, SOUND }
    enum class Switch { ON, OFF, TOGGLE }

    sealed class Action {
        object Empty : Action()
        object StopListening : Action()
        object Relock : Action()
        object NewChat : Action()
        object Greet : Action()
        object Briefing : Action()
        object WhoAreYou : Action()
        object Thanks : Action()
        object Help : Action()
        object Time : Action()
        object Date : Action()
        object Battery : Action()
        data class GlobalAction(val kind: Global) : Action()
        object Screenshot : Action()
        object LockScreen : Action()
        data class Scroll(val direction: String) : Action()
        data class Click(val target: String) : Action()
        data class Type(val text: String) : Action()
        object ReadScreen : Action()
        data class DescribeScreen(val question: String?) : Action()
        object ReadNotifications : Action()
        data class Remember(val fact: String) : Action()
        object Recall : Action()
        object ClearMemory : Action()
        data class Torch(val on: Boolean) : Action()
        data class VolumeStep(val up: Boolean) : Action()
        data class VolumeSet(val percent: Int) : Action()
        data class Mute(val on: Boolean) : Action()
        data class RingerMode(val mode: Ringer) : Action()
        data class MediaKey(val key: Media) : Action()
        data class BrightnessSet(val percent: Int) : Action()
        data class BrightnessStep(val up: Boolean) : Action()
        data class Toggle(val thing: Thing, val state: Switch) : Action()
        data class Alarm(val hour: Int, val minute: Int, val label: String?) : Action()
        data class Timer(val seconds: Int, val label: String?) : Action()
        data class WhatsApp(val who: String, val body: String) : Action()
        data class Sms(val who: String, val body: String) : Action()
        data class Call(val who: String) : Action()
        data class YouTube(val query: String) : Action()
        data class Navigate(val place: String) : Action()
        data class ShowOnMap(val place: String) : Action()
        data class Camera(val selfie: Boolean) : Action()
        data class Search(val query: String) : Action()
        data class SettingsPage(val name: String) : Action()
        data class OpenApp(val name: String) : Action()
        /** A question: the AI answers it (or the web, without an AI key). */
        data class Question(val text: String) : Action()
        /** Hindi/Urdu we couldn't map offline — only the AI can handle it. */
        data class Ask(val text: String) : Action()
        /** Nothing matched: maybe an app name, otherwise the AI. */
        data class Unknown(val text: String) : Action()
    }

    /** Lower-cases and strips filler so patterns only deal with the essence. */
    fun normalize(input: String): String = input.lowercase(Locale.US)
        // Keep letters of every script, so Hindi/Urdu names and song titles survive.
        .replace(Regex("[^\\p{L}\\p{M}0-9:'%+@.\\- ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ' ', '?', '!')
        .removeSuffix(" please")
        .replace(Regex("^(?:(?:please|can you|could you|would you|will you|jarvis|hey|ok|okay|now) )+"), "")
        .trim()
        .let { if (it == "please" || it == "jarvis") "" else it }

    fun parse(input: String, nonEnglishMode: Boolean = false): Action {
        val translated = if (nonEnglishMode || Hindustani.isNonLatin(input)) Hindustani.toEnglish(input) else null
        if (translated == null && Hindustani.isNonLatin(input)) return Action.Ask(input.trim())
        val c = normalize(translated ?: input)
        if (c.isEmpty()) return Action.Empty
        return route(c)
    }

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50,
        "sixty" to 60, "ninety" to 90, "half an" to 30, "half a" to 30
    )
    private const val AMOUNT = "(\\d+|half an|half a|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty five|forty|fifty|sixty|ninety)"
    private const val UNIT = "(second|sec|minute|min|hour|hr)s?"
    private const val CLOCK = "(\\d{1,2})(?::(\\d{2})| (\\d{2}))?(?: ?([ap])\\.? ?m\\.?)?(?: (?:in the |at )?(morning|evening|night|afternoon|tonight))?"

    private fun route(c: String): Action {
        fun m(pattern: String) = Regex(pattern).matchEntire(c)
        var r: MatchResult?

        // ----- Jarvis itself -----
        if (m("(stop listening|go to sleep|sleep|go offline|goodbye|bye|bye bye|shut ?down|stop|that's all|that is all)") != null) return Action.StopListening
        if (m("(lock|secure) (yourself|jarvis|voice|assistant)|voice lock|lock voice") != null) return Action.Relock
        if (m("(new conversation|new chat|start over|forget (that|it|our conversation)|clear (the )?(chat|conversation))") != null) return Action.NewChat
        if (m("(forget|clear|erase|delete) (everything you remember|your memory|(all )?(your )?memories|what you remember)|clear memory") != null) return Action.ClearMemory
        if (m("(what do you remember|what did i (tell|ask) you to remember|what have i told you|(show|list|read) (your |my )?memor(y|ies))") != null) return Action.Recall
        r = m("(?:remember|note|make a note|save a note|don't forget)(?: that| this)?:? (.+)")
        if (r != null) return Action.Remember(r.groupValues[1])
        if (m("((daily |morning |my )?briefing|brief me|good morning( jarvis)?|morning report|what's my day( look like)?)") != null) return Action.Briefing
        if (m("(hello|hi|hey|yo|hello jarvis|hi jarvis|good (afternoon|evening)|you there|are you there|wake up|daddy's home)") != null) return Action.Greet
        if (m("(who are you|what is your name|what's your name|introduce yourself)") != null) return Action.WhoAreYou
        if (m("(thank you|thanks|thank you jarvis|thanks jarvis|good job|well done|nice work|great job)") != null) return Action.Thanks
        if (m("(help|what can you do|commands|show commands|what are your features)") != null) return Action.Help

        // ----- Time, date, battery -----
        if (m("(what('s| is) the )?time( is it)?( now)?|what time is it( now)?|tell me the time|current time") != null) return Action.Time
        if (m("(what('s| is) )?(the |today's )?(date|day)( today)?|what day is it( today)?|what is today|what's today|today's date") != null) return Action.Date
        if (m("(what('s| is) (the |my )?)?battery( level| status| percentage)?|how much (battery|charge)( is left| do i have| left)?|battery left") != null) return Action.Battery

        // ----- Notifications & screen understanding -----
        if (m("(read|check|show me|tell me)( out)? (my |the |new )?(notifications|messages|texts)|any (new )?(messages|notifications|texts)|what did i miss|do i have (any )?(new )?(messages|notifications)|who (texted|messaged) me") != null)
            return Action.ReadNotifications
        r = m("(?:what's|what is) on (?:my |the |this )?screen|describe (?:the |my |this )?screen|what am i looking at|explain (?:this|the|my) screen|help me with (?:this|the|my) screen|look at (?:my |the |this )?screen(?: and (.+))?")
        if (r != null) return Action.DescribeScreen(r.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() })
        if (m("read (the |this |my )?(screen|page)( out)?( to me)?|read it( out)?( to me)?") != null) return Action.ReadScreen

        // ----- Screen / navigation -----
        if (m("(go )?home|(go to |show )?(the )?home ?screen") != null) return Action.GlobalAction(Global.HOME)
        if (m("(go )?back|go back") != null) return Action.GlobalAction(Global.BACK)
        if (m("(show |open )?(the )?(recent apps|recents|recent|app switcher|multitasking)") != null) return Action.GlobalAction(Global.RECENTS)
        if (m("(show |open |pull down )?(the |my )?notifications?( panel| shade)?") != null) return Action.GlobalAction(Global.NOTIFICATIONS)
        if (m("(show |open )?(the )?quick settings") != null) return Action.GlobalAction(Global.QUICK_SETTINGS)
        if (m("(show |open )?(the )?power (menu|options|dialog)") != null) return Action.GlobalAction(Global.POWER)
        if (m("(take|capture|grab) (a )?screen ?shot|screen ?shot") != null) return Action.Screenshot
        if (m("lock (the |my )?(phone|screen|device|mobile)|lock it|turn off (the )?screen|screen off") != null) return Action.LockScreen
        r = m("scroll (up|down|left|right)|(?:swipe|go) (up|down|left|right)|page (up|down)|scroll")
        if (r != null) return Action.Scroll(r.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "down")
        r = m("(?:click|tap|press|select|hit)(?: on)?(?: the)? (.+?)(?: button)?")
        if (r != null) return Action.Click(r.groupValues[1])
        r = m("(?:type|write|enter)(?: in)? (.+)")
        if (r != null && !Regex("^(an?|the|me|my) (email|essay|story|poem|letter|message|note|caption|post)\\b.*").matches(r.groupValues[1]))
            return Action.Type(r.groupValues[1])

        // ----- Flashlight -----
        r = m("(?:turn |switch )?(on|off) (?:the |my )?(?:flash ?light|torch|flash|torch light)|(?:turn |switch )?(?:the |my )?(?:flash ?light|torch|flash|torch light) (on|off)")
        if (r != null) {
            val g = r.groupValues
            return Action.Torch(g[1].ifEmpty { g[2] } == "on")
        }
        if (m("(flash ?light|torch|light on|lights on)") != null) return Action.Torch(true)
        if (m("(light off|lights off)") != null) return Action.Torch(false)

        // ----- Volume / media -----
        if (m("(volume up|increase (the )?volume|turn (it |the volume )?up|turn up (the )?(volume|sound|music)|louder|raise (the )?volume)") != null) return Action.VolumeStep(true)
        if (m("(volume down|decrease (the )?volume|turn (it |the volume )?down|turn down (the )?(volume|sound|music)|quieter|lower (the )?volume|reduce (the )?volume)") != null) return Action.VolumeStep(false)
        r = m("(?:set |change |put )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?")
        if (r != null) return Action.VolumeSet(r.groupValues[1].toInt().coerceIn(0, 100))
        if (m("(max|maximum|full) volume|volume (max|maximum|full)") != null) return Action.VolumeSet(100)
        if (m("mute|mute (the )?(phone|volume|sound|media)") != null) return Action.Mute(true)
        if (m("unmute|unmute (the )?(phone|volume|sound|media)") != null) return Action.Mute(false)
        if (m("(silent|silence) mode|(put|set) (the |my )?phone (on|to) silent|silence (the |my )?phone") != null) return Action.RingerMode(Ringer.SILENT)
        if (m("vibrat(e|ion) mode|(put|set) (the |my )?phone (on|to) vibrat(e|ion)") != null) return Action.RingerMode(Ringer.VIBRATE)
        if (m("(normal|ring|sound|general) mode|ringer on") != null) return Action.RingerMode(Ringer.NORMAL)
        if (m("(pause|stop)( the)?( music| song| video| playback| audio)?") != null) return Action.MediaKey(Media.PAUSE)
        if (m("(resume|play)( the)? (music|song|playback)|resume|continue( playing)?|play") != null) return Action.MediaKey(Media.PLAY)
        if (m("(next|skip)( the)?( song| track| video)?|skip (this )?(song|track)|next one") != null) return Action.MediaKey(Media.NEXT)
        if (m("(previous|last|go back to the previous)( song| track| video)|previous") != null) return Action.MediaKey(Media.PREVIOUS)

        // ----- Brightness -----
        r = m("(?:set |change |put )?(?:the |screen )?brightness (?:to |at )?(\\d{1,3})(?: ?%| percent)?")
        if (r != null) return Action.BrightnessSet(r.groupValues[1].toInt().coerceIn(1, 100))
        if (m("(max(imum)?|full) brightness|brightness (max|maximum|full)") != null) return Action.BrightnessSet(100)
        if (m("(min(imum)?) brightness|brightness (min|minimum)") != null) return Action.BrightnessSet(1)
        if (m("(brightness up|increase (the )?brightness|brighter|more brightness|make (it|the screen) brighter)") != null) return Action.BrightnessStep(true)
        if (m("(brightness down|decrease (the )?brightness|dimmer|dim (the )?screen|less brightness|lower (the )?brightness|make (it|the screen) dimmer)") != null) return Action.BrightnessStep(false)

        // ----- Wi-Fi, Bluetooth and other toggles -----
        r = m("(?:turn|switch) (on|off) (?:the |my )?(.+)|(?:turn|switch) (?:the |my )?(.+?) (on|off)|(enable|disable|toggle|start|stop|activate|deactivate) (?:the |my )?(.+)")
        if (r != null) {
            val g = r.groupValues
            val (word, thing) = when {
                g[1].isNotEmpty() -> g[1] to g[2]
                g[4].isNotEmpty() -> g[4] to g[3]
                else -> g[5] to g[6]
            }
            val state = when (word) {
                "on", "enable", "start", "activate" -> Switch.ON
                "toggle" -> Switch.TOGGLE
                else -> Switch.OFF
            }
            if (thing.matches(Regex("(the )?(flash ?light|torch|flash|torch light)"))) return Action.Torch(state != Switch.OFF)
            thingOf(thing)?.let { return Action.Toggle(it, state) }
        }

        // ----- Reminders, alarms & timers -----
        r = m("remind me in $AMOUNT $UNIT (?:to |that |about )?(.+)")
        if (r != null) return Action.Timer(seconds(r.groupValues[1], r.groupValues[2]), r.groupValues[3])
        r = m("remind me (?:to |that |about )(.+?) (?:at|by) $CLOCK")
        if (r != null) return alarmOf(r.groupValues[2], r.groupValues[3], r.groupValues[4], r.groupValues[5], r.groupValues[6], r.groupValues[1])
        r = m("remind me (?:at|by) $CLOCK (?:to |that |about )(.+)")
        if (r != null) return alarmOf(r.groupValues[1], r.groupValues[2], r.groupValues[3], r.groupValues[4], r.groupValues[5], r.groupValues[6])
        r = m("remind me (?:to |that |about )(.+?) in $AMOUNT $UNIT")
        if (r != null) return Action.Timer(seconds(r.groupValues[2], r.groupValues[3]), r.groupValues[1])
        r = m("(?:set (?:an |the |my )?alarm|wake me up|alarm)(?: for| at)? $CLOCK.*")
        if (r != null) return alarmOf(r.groupValues[1], r.groupValues[2], r.groupValues[3], r.groupValues[4], r.groupValues[5], null)
        r = m("(?:set (?:a |the )?timer|timer|countdown|start a timer)(?: for)? $AMOUNT $UNIT.*")
        if (r != null) return Action.Timer(seconds(r.groupValues[1], r.groupValues[2]), null)
        r = m("$AMOUNT $UNIT timer")
        if (r != null) return Action.Timer(seconds(r.groupValues[1], r.groupValues[2]), null)

        // ----- Calls, SMS, WhatsApp -----
        r = m("(?:send )?(?:a )?whats ?app(?: message)?(?: to)? (.+?) (?:saying|that says|that|message|and say|to say) (.+)")
        if (r != null) return Action.WhatsApp(r.groupValues[1], r.groupValues[2])
        r = m("(?:send (?:a |an )?(?:message|text|sms|text message) to|text|message|sms|tell (?!me )(?!us ))(.+?) (?:saying|that says|that|to say) (.+)")
        if (r != null) return Action.Sms(r.groupValues[1], r.groupValues[2])
        r = m("(?:call|dial|phone|ring|make a call to|give a call to) (.+?)(?: now| please| for me)?")
        if (r != null) return Action.Call(r.groupValues[1])

        // ----- YouTube, maps, camera, web -----
        r = m("(?:play|search|watch|find|put on) (.+) on youtube|youtube (?:search )?(?:for )?(.+)|(?:open )?youtube and (?:play|search|search for) (.+)")
        if (r != null) return Action.YouTube(r.groupValues.drop(1).first { it.isNotEmpty() })
        r = m("play (?:the )?(?:song |video |music |some )?(.+)")
        if (r != null) return Action.YouTube(r.groupValues[1])
        r = m("(?:navigate|directions|take me|drive|route|get me|show me the way|how do i get) (?:to )?(.+)")
        if (r != null) return Action.Navigate(r.groupValues[1])
        r = m("(?:where is|show me|find) (.+?) on (?:the )?map|(?:show|open) (.+?) (?:on|in) (?:the )?(?:google )?maps?")
        if (r != null) return Action.ShowOnMap(r.groupValues.drop(1).first { it.isNotEmpty() })
        if (m("(take a |take |click a |open the )?(selfie|front camera)") != null) return Action.Camera(true)
        if (m("(take a |take |click a |capture a )?(photo|picture|pic)|(open |start )?(the )?camera") != null) return Action.Camera(false)
        r = m("(?:search|google|look up|search for|search google for|google search) (?:for )?(.+)")
        if (r != null) return Action.Search(r.groupValues[1])

        // ----- Settings pages -----
        r = m("open (?:the )?(wi-?fi|wifi|bluetooth|display|sound|battery|location|app|apps|storage|security|accessibility|developer|date|language|notification|data|mobile data|hotspot) settings?")
        if (r != null) return Action.SettingsPage(r.groupValues[1])

        // ----- Questions go to the AI -----
        if (m("(?:what|who|whom|whose|when|where|why|which|how|is|are|can|could|should|would|will|do|does|did|am|was|were)(?:'s| ).+|(?:tell me|explain|describe|define|give me|write|translate|calculate|suggest|recommend|summarize|compare|convert|ask (?:claude|ai|the ai)|i need|i want|let's|let us|help me) .+") != null)
            return Action.Question(c)

        // ----- Open apps -----
        r = m("(?:open|launch|start|run|go to|show me|show) (?:the |my )?(.+?)(?: app| application)?")
        if (r != null) return Action.OpenApp(r.groupValues[1])

        return Action.Unknown(c)
    }

    private fun thingOf(t: String): Thing? {
        val s = t.trim()
        return when {
            s.matches(Regex("(the )?(wi-?fi|wifi|wi fi|internet|wireless)")) -> Thing.WIFI
            s.contains("bluetooth") -> Thing.BLUETOOTH
            s.contains("airplane") || s.contains("aeroplane") || s.contains("flight") -> Thing.AIRPLANE
            s.contains("hotspot") || s.contains("tethering") -> Thing.HOTSPOT
            s.contains("mobile data") || s == "data" || s.contains("cellular") -> Thing.MOBILE_DATA
            s.contains("location") || s == "gps" -> Thing.LOCATION
            s.contains("do not disturb") || s == "dnd" -> Thing.DND
            s.contains("rotat") -> Thing.ROTATION
            s.contains("battery saver") || s.contains("power sav") -> Thing.BATTERY_SAVER
            s.contains("dark mode") || s.contains("dark theme") || s.contains("night mode") -> Thing.DARK_MODE
            s == "sound" || s == "volume" || s == "ringer" -> Thing.SOUND
            else -> null
        }
    }

    fun seconds(amount: String, unit: String): Int {
        val n = amount.toIntOrNull() ?: NUMBER_WORDS[amount] ?: 1
        return when {
            unit.startsWith("h") -> if (amount.startsWith("half")) 1800 else n * 3600
            unit.startsWith("m") -> if (amount.startsWith("half")) 30 else n * 60
            else -> n
        }
    }

    /** Converts a spoken clock time to 24-hour hour/minute. */
    fun clock(hourText: String, minA: String, minB: String, ampm: String, part: String): Pair<Int, Int> {
        var hour = hourText.toInt()
        val minute = minA.ifEmpty { minB }.ifEmpty { "0" }.toInt().coerceIn(0, 59)
        val pm = ampm == "p" || (ampm.isEmpty() && part in listOf("evening", "night", "afternoon", "tonight"))
        if (pm && hour < 12) hour += 12
        if (ampm == "a" && hour == 12) hour = 0
        if (ampm.isEmpty() && part == "morning" && hour == 12) hour = 0
        return hour.coerceIn(0, 23) to minute
    }

    private fun alarmOf(h: String, minA: String, minB: String, ampm: String, part: String, label: String?): Action {
        val (hour, minute) = clock(h, minA, minB, ampm, part)
        return Action.Alarm(hour, minute, label?.takeIf { it.isNotBlank() })
    }
}
