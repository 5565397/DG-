package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Turns a spoken sentence into an action on the phone and a spoken reply. */
class CommandProcessor(private val ctx: Context) {

    enum class Control { NONE, STOP_LISTENING, RELOCK, NEW_CHAT }

    data class Result(
        val reply: String,
        val control: Control = Control.NONE,
        /** Runs after Jarvis has finished speaking (e.g. lock screen, screenshot). */
        val after: (() -> Unit)? = null,
        /** When set, the service asks the AI brain this question and speaks its answer. */
        val ask: String? = null
    )

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val sir: String get() = prefs.title
    private val hasBrain: Boolean get() = prefs.apiKey.isNotBlank()
    private val a11y: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    private val needA11y: Result
        get() = Result("I need phone control for that, $sir. Open my app and enable it under Setup, step two.")

    fun handle(input: String): Result {
        val c = input.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9:'%+@.\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
            .removeSuffix(" please")
            .replace(Regex("^(?:(?:please|can you|could you|would you|will you|jarvis) )+"), "")
        if (c.isEmpty()) return Result("Yes, $sir?")

        return try {
            route(c)
        } catch (e: SecurityException) {
            Result("I don't have permission for that yet, $sir. Please grant it in Setup.")
        } catch (e: Exception) {
            Result("Something went wrong, $sir. ${e.message ?: ""}".trim())
        }
    }

    private fun route(c: String): Result {
        fun m(pattern: String) = Regex(pattern).matchEntire(c)
        var r: MatchResult?

        // ----- Jarvis itself -----
        if (m("(stop listening|go to sleep|sleep|go offline|goodbye|bye|shut ?down|stop|that's all)") != null)
            return Result("Going offline. Call me when you need me, $sir.", Control.STOP_LISTENING)
        if (m("(lock|secure) (yourself|jarvis|voice|assistant)|voice lock") != null)
            return Result("Voice lock engaged, $sir.", Control.RELOCK)
        if (m("(new conversation|new chat|start over|forget (that|it|everything|our conversation)|clear (the )?(chat|conversation))") != null)
            return Result("Fresh start, $sir. What's on your mind?", Control.NEW_CHAT)
        if (m("(hello|hi|hey|good (morning|afternoon|evening)|you there|are you there|wake up)") != null)
            return Result("${greeting()}, $sir. At your service.")
        if (m("(who are you|what is your name|what's your name|introduce yourself)") != null)
            return Result("I am Jarvis. Just A Rather Very Intelligent System. Your personal assistant, $sir.")
        if (m("(thank you|thanks|thank you jarvis|good job|well done)") != null)
            return Result("Always a pleasure, $sir.")
        if (m("(help|what can you do|commands|show commands)") != null)
            return Result("I can answer any question, open apps, make calls, send texts and WhatsApp messages, control the flashlight, volume and brightness, set alarms and timers, search the web, play YouTube, navigate, take screenshots, lock the phone, and tap, scroll and type on screen. Just ask.")

        // ----- Time, date, battery -----
        if (m("(what('s| is) the )?time( is it)?( now)?|what time is it|tell me the time|current time") != null)
            return Result("It's ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}, $sir.")
        if (m("(what('s| is) )?(the |today's )?(date|day)( today)?|what day is it( today)?|what is today") != null)
            return Result("Today is ${SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())}.")
        if (m("(what('s| is) (the |my )?)?battery( level| status| percentage)?|how much (battery|charge)( is left| do i have)?") != null)
            return battery()

        // ----- Screen / navigation (needs Accessibility) -----
        if (m("(go )?home|(go to |show )?(the )?home ?screen") != null) return global(AccessibilityService.GLOBAL_ACTION_HOME, "Home.")
        if (m("(go )?back|go back") != null) return global(AccessibilityService.GLOBAL_ACTION_BACK, "Done.")
        if (m("(show |open )?(the )?(recent apps|recents|recent|app switcher|multitasking)") != null)
            return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.")
        if (m("(show |open |pull down )?(the |my )?notifications?( panel)?") != null)
            return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Your notifications, $sir.")
        if (m("(show |open )?(the )?quick settings") != null)
            return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings.")
        if (m("(show |open )?(the )?power (menu|options|dialog)") != null)
            return global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Power menu.")
        if (m("(take|capture|grab) (a )?screen ?shot|screen ?shot") != null) {
            val s = a11y ?: return needA11y
            if (Build.VERSION.SDK_INT < 28) return Result("Screenshots need Android 9 or newer, $sir.")
            return Result("Screenshot.") { main.postDelayed({ s.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600) }
        }
        if (m("lock (the |my )?(phone|screen|device|mobile)|lock it|turn off (the )?screen|screen off") != null) {
            val s = a11y ?: return needA11y
            if (Build.VERSION.SDK_INT < 28) return Result("Locking needs Android 9 or newer, $sir.")
            return Result("Locking the phone.") { s.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        }
        r = m("scroll (up|down|left|right)|(?:swipe|go) (up|down|left|right)|page (up|down)")
        if (r != null) {
            val s = a11y ?: return needA11y
            val dir = r.groupValues.drop(1).first { it.isNotEmpty() }
            s.scroll(dir)
            return Result("")
        }
        r = m("(?:click|tap|press|select|hit)(?: on)?(?: the)? (.+?)(?: button)?")
        if (r != null) {
            val s = a11y ?: return needA11y
            val target = r.groupValues[1]
            return if (s.clickText(target)) Result("") else Result("I can't find $target on the screen, $sir.")
        }
        r = m("(?:type|write|enter)(?: in)? (.+)")
        if (r != null) {
            val s = a11y ?: return needA11y
            return if (s.typeText(r.groupValues[1])) Result("Typed.") else Result("I can't find a text box on the screen, $sir.")
        }
        if (m("read (the |this |my )?(screen|page)|what('s| is) on (the |my )?screen") != null) {
            val s = a11y ?: return needA11y
            val text = s.readScreen()
            return Result(if (text.isBlank()) "I don't see any text on the screen, $sir." else text)
        }

        // ----- Flashlight -----
        r = m("(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch|flash)|(?:turn |switch )?(?:the )?(?:flash ?light|torch|flash) (on|off)")
        if (r != null) {
            val g = r.groupValues
            return torch(g[1].ifEmpty { g[2] } == "on")
        }
        if (m("(flash ?light|torch|light on)") != null) return torch(true)
        if (m("(light off)") != null) return torch(false)

        // ----- Volume / media -----
        if (m("(volume up|increase (the )?volume|turn (it |the volume )?up|turn up (the )?(volume|sound)|louder|raise (the )?volume)") != null) return volumeStep(true)
        if (m("(volume down|decrease (the )?volume|turn (it |the volume )?down|turn down (the )?(volume|sound)|quieter|lower (the )?volume|reduce (the )?volume)") != null) return volumeStep(false)
        r = m("(?:set |change )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?")
        if (r != null) return volumeSet(r.groupValues[1].toInt())
        if (m("(max|maximum|full) volume|volume (max|maximum|full)") != null) return volumeSet(100)
        if (m("mute|mute (the )?(phone|volume|sound|media)") != null) return mute(true)
        if (m("unmute|unmute (the )?(phone|volume|sound|media)") != null) return mute(false)
        if (m("(silent|silence) mode|(put|set) (the |my )?phone (on|to) silent|silence (the |my )?phone") != null) return ringer(AudioManager.RINGER_MODE_SILENT, "Silent mode.")
        if (m("vibrat(e|ion) mode|(put|set) (the |my )?phone (on|to) vibrat(e|ion)") != null) return ringer(AudioManager.RINGER_MODE_VIBRATE, "Vibrate mode.")
        if (m("(normal|ring|sound|general) mode|ringer on") != null) return ringer(AudioManager.RINGER_MODE_NORMAL, "Ringer on.")
        if (m("(pause|stop)( the)?( music| song| video| playback)?") != null) return media(KeyEvent.KEYCODE_MEDIA_PAUSE, "")
        if (m("(resume|play)( the)? (music|song|playback)|resume|continue( playing)?") != null) return media(KeyEvent.KEYCODE_MEDIA_PLAY, "")
        if (m("(next|skip)( the)?( song| track| video)?|skip (this )?(song|track)") != null) return media(KeyEvent.KEYCODE_MEDIA_NEXT, "")
        if (m("(previous|last|go back to the previous)( song| track| video)") != null) return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "")

        // ----- Brightness -----
        r = m("(?:set |change )?(?:the )?brightness (?:to |at )?(\\d{1,3})(?: ?%| percent)?")
        if (r != null) return brightness(r.groupValues[1].toInt())
        if (m("(brightness up|increase (the )?brightness|brighter|more brightness|max(imum)? brightness|full brightness)") != null)
            return brightness(if (c.contains("max") || c.contains("full")) 100 else -1)
        if (m("(brightness down|decrease (the )?brightness|dimmer|dim (the )?screen|less brightness|lower (the )?brightness|min(imum)? brightness)") != null)
            return brightness(if (c.contains("min")) 1 else -2)

        // ----- Wi-Fi, Bluetooth, other toggles -----
        r = m("(?:turn|switch) (on|off) (?:the |my )?(.+)|(?:turn|switch) (?:the |my )?(.+?) (on|off)|(enable|disable|toggle|start|stop) (?:the |my )?(.+)")
        if (r != null) {
            val g = r.groupValues
            val (state, thing) = when {
                g[1].isNotEmpty() -> g[1] to g[2]
                g[4].isNotEmpty() -> g[4] to g[3]
                else -> (if (g[5] == "enable" || g[5] == "start") "on" else if (g[5] == "toggle") "toggle" else "off") to g[6]
            }
            toggle(thing, state)?.let { return it }
        }

        // ----- Alarms & timers -----
        r = m("(?:set (?:an |the |my )?alarm|wake me up|alarm)(?: for| at)? (\\d{1,2})(?::(\\d{2})| (\\d{2}))?(?: ?([ap])\\.? ?m\\.?)?(?: (?:in the )?(morning|evening|night|afternoon|tonight))?.*")
        if (r != null) return alarm(r)
        r = m("(?:set (?:a |the )?timer|timer|remind me in|countdown)(?: for)? (\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|sixty) (second|sec|minute|min|hour)s?.*")
        if (r != null) return timer(r.groupValues[1], r.groupValues[2])

        // ----- Calls, SMS, WhatsApp -----
        r = m("(?:send )?(?:a )?whats ?app(?: message)?(?: to)? (.+?) (?:saying|that says|that|message) (.+)")
        if (r != null) return whatsapp(r.groupValues[1], r.groupValues[2])
        r = m("(?:send (?:a |an )?(?:message|text|sms|text message) to|text|message|sms) (.+?) (?:saying|that says|that) (.+)")
        if (r != null) return sms(r.groupValues[1], r.groupValues[2])
        r = m("(?:call|dial|phone|ring) (.+?)(?: now)?")
        if (r != null) return call(r.groupValues[1])

        // ----- Web, YouTube, Maps, camera -----
        r = m("(?:play|search|watch|find) (.+) on youtube|youtube (?:search )?(?:for )?(.+)|(?:open )?youtube and (?:play|search)(?: for)? (.+)")
        if (r != null) return youtube(r.groupValues.drop(1).first { it.isNotEmpty() })
        r = m("play (?:the )?(?:song |video |music )?(.+)")
        if (r != null) return youtube(r.groupValues[1])
        r = m("(?:navigate|directions|take me|drive|route|get me|show me the way) (?:to )?(.+)")
        if (r != null) return navigate(r.groupValues[1])
        r = m("(?:where is|show me|find) (.+?) on (?:the )?map|(?:show|open) (.+?) (?:on|in) (?:google )?maps")
        if (r != null) return map(r.groupValues.drop(1).first { it.isNotEmpty() })
        if (m("(take a |take |click a )?(selfie|front camera)") != null) return camera(true)
        if (m("(take a |take |click a |capture a )?(photo|picture|pic)|(open |start )?(the )?camera") != null) return camera(false)
        r = m("(?:search|google|look up|search for|search google for|google search) (?:for )?(.+)")
        if (r != null) return search(r.groupValues[1])
        if (m("(?:what|who|whom|whose|when|where|why|which|how|is|are|can|could|should|would|will|do|does|did)(?:'s| ).+|(?:tell me|explain|describe|define|give me|write|translate|calculate|suggest|recommend|summarize|compare|ask (?:claude|ai|the ai)) .+") != null)
            return if (hasBrain) Result("", ask = c) else search(c)

        // ----- Settings pages -----
        r = m("open (?:the )?(wi-?fi|wifi|bluetooth|display|sound|battery|location|app|apps|storage|security|accessibility|developer|date|language|notification|data|mobile data|hotspot) settings?")
        if (r != null) return settingsPage(r.groupValues[1])

        // ----- Open apps -----
        r = m("(?:open|launch|start|run|go to|show me|show) (?:the |my )?(.+?)(?: app| application)?")
        if (r != null) return openApp(r.groupValues[1])

        // Maybe the user just said an app name.
        findApp(c, loose = false)?.let { return launchApp(it.first, it.second) }

        // Anything else goes to the AI brain.
        if (hasBrain) return Result("", ask = c)
        return Result("Sorry $sir, I don't know how to do that yet. Add a Claude API key in my settings and I can answer anything.")
    }

    // =================================================================
    // Helpers
    // =================================================================

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Burning the midnight oil"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Starts an activity, using the accessibility service's context when possible so it works from the background. */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (a11y ?: ctx).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun global(action: Int, reply: String): Result {
        val s = a11y ?: return needA11y
        s.global(action)
        return Result(reply)
    }

    private fun battery(): Result {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Result("Battery is at $level percent${if (charging) " and charging" else ""}, $sir.")
    }

    // ----- Flashlight -----
    private fun torch(on: Boolean): Result {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result("This phone doesn't have a flashlight, $sir.")
        cm.setTorchMode(id, on)
        return Result(if (on) "Flashlight on." else "Flashlight off.")
    }

    // ----- Audio -----
    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volumeStep(up: Boolean): Result {
        val am = audio
        repeat(3) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                if (it == 2) AudioManager.FLAG_SHOW_UI else 0
            )
        }
        return Result(if (up) "Volume up." else "Volume down.")
    }

    private fun volumeSet(percent: Int): Result {
        val am = audio
        val p = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max * p / 100, AudioManager.FLAG_SHOW_UI)
        return Result("Volume at $p percent.")
    }

    private fun mute(on: Boolean): Result {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return Result(if (on) "Muted." else "Sound is back on.")
    }

    private fun ringer(mode: Int, reply: String): Result {
        return try {
            audio.ringerMode = mode
            Result(reply)
        } catch (e: SecurityException) {
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Result("Android needs you to allow Do Not Disturb access for me first, $sir. I've opened the page.")
        }
    }

    private fun media(keyCode: Int, reply: String): Result {
        val am = audio
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return Result(reply)
    }

    // ----- Brightness -----
    /** percent 1..100, or -1 = brighter, -2 = dimmer. */
    private fun brightness(percent: Int): Result {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return Result("Please allow me to modify system settings first, $sir. I've opened the page.")
        }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val value = when (percent) {
            -1 -> current + 64
            -2 -> current - 64
            else -> percent.coerceIn(1, 100) * 255 / 100
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
        return Result("Brightness at ${value * 100 / 255} percent.")
    }

    // ----- Toggles -----
    private fun toggle(thing: String, state: String): Result? {
        val t = thing.trim()
        return when {
            t.matches(Regex("(the )?(flash ?light|torch|flash)")) -> torch(state != "off")
            t.matches(Regex("wi-?fi|wifi|wi fi|internet")) -> wifi(state)
            t.contains("bluetooth") -> quickTile(listOf("bluetooth"), "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS)
            t.contains("airplane") || t.contains("aeroplane") || t.contains("flight") ->
                quickTile(listOf("airplane", "aeroplane", "flight mode"), "Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            t.contains("hotspot") || t.contains("tethering") ->
                quickTile(listOf("hotspot"), "Hotspot", Settings.ACTION_WIRELESS_SETTINGS)
            t.contains("mobile data") || t == "data" || t.contains("cellular") ->
                quickTile(listOf("mobile data", "data"), "Mobile data", Settings.ACTION_DATA_ROAMING_SETTINGS)
            t.contains("location") || t.contains("gps") ->
                quickTile(listOf("location"), "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            t.contains("do not disturb") || t == "dnd" ->
                quickTile(listOf("do not disturb"), "Do not disturb", Settings.ACTION_SOUND_SETTINGS)
            t.contains("rotat") -> quickTile(listOf("rotate", "rotation"), "Auto rotate", Settings.ACTION_DISPLAY_SETTINGS)
            t.contains("battery saver") || t.contains("power sav") ->
                quickTile(listOf("battery saver", "power saving"), "Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS)
            t.contains("dark mode") || t.contains("dark theme") ->
                quickTile(listOf("dark"), "Dark mode", Settings.ACTION_DISPLAY_SETTINGS)
            t == "sound" || t == "volume" -> mute(state == "off")
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun wifi(state: String): Result {
        if (Build.VERSION.SDK_INT < 29) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = state == "on" || (state == "toggle" && !wm.isWifiEnabled)
            return Result("Wi-Fi ${if (wm.isWifiEnabled) "on" else "off"}.")
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (state == "on" && wm.isWifiEnabled) return Result("Wi-Fi is already on, $sir.")
        if (state == "off" && !wm.isWifiEnabled) return Result("Wi-Fi is already off, $sir.")
        launch(Intent(Settings.Panel.ACTION_WIFI))
        return Result("Android only lets you flip Wi-Fi yourself on this version. Here's the switch, $sir.")
    }

    private fun quickTile(labels: List<String>, name: String, fallbackAction: String): Result {
        val s = a11y
        if (s == null) {
            launch(Intent(fallbackAction))
            return Result("Opening $name settings, $sir.")
        }
        s.toggleQuickSetting(labels) { ok ->
            if (!ok) launch(Intent(fallbackAction))
        }
        return Result("Toggling $name.")
    }

    private fun settingsPage(name: String): Result {
        val action = when (name) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "app", "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "notification" -> Settings.ACTION_SETTINGS
            "data", "mobile data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> Settings.ACTION_WIRELESS_SETTINGS
        }
        if (!launch(Intent(action))) launch(Intent(Settings.ACTION_SETTINGS))
        return Result("Opening $name settings.")
    }

    // ----- Alarms & timers -----
    private fun alarm(r: MatchResult): Result {
        val g = r.groupValues
        var hour = g[1].toInt()
        val minute = (g[2].ifEmpty { g[3] }).ifEmpty { "0" }.toInt().coerceIn(0, 59)
        val ampm = g[4]
        val part = g[5]
        if ((ampm == "p" || part in listOf("evening", "night", "afternoon", "tonight")) && hour < 12) hour += 12
        if (ampm == "a" && hour == 12) hour = 0
        hour = hour.coerceIn(0, 23)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return Result("I couldn't find a clock app, $sir.")
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return Result("Alarm set for ${SimpleDateFormat("h:mm a", Locale.US).format(cal.time)}, $sir.")
    }

    private fun timer(amountWord: String, unit: String): Result {
        val words = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
            "twenty" to 20, "thirty" to 30, "forty five" to 45, "sixty" to 60
        )
        val amount = amountWord.toIntOrNull() ?: words[amountWord] ?: 1
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return Result("I couldn't find a clock app, $sir.")
        val label = when {
            unit.startsWith("h") -> "hour"
            unit.startsWith("m") -> "minute"
            else -> "second"
        }
        return Result("Timer set for $amount $label${if (amount == 1) "" else "s"}.")
    }

    // ----- Contacts, calls, messages -----
    private fun findContact(name: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { cur ->
            var first: Pair<String, String>? = null
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                if (n.equals(name, ignoreCase = true)) return n to num
                if (first == null) first = n to num
            }
            return first
        }
        return null
    }

    /** Returns (displayName, number) for a spoken name or a spoken number. */
    private fun resolveNumber(who: String): Pair<String, String>? {
        val digits = who.replace(Regex("[\\s-]"), "")
        if (digits.matches(Regex("\\+?\\d{3,}"))) return who to digits
        return findContact(who)
    }

    private fun call(who: String): Result {
        if (!granted(Manifest.permission.READ_CONTACTS) && !who.any { it.isDigit() })
            return Result("I need permission to read your contacts first, $sir.")
        val (name, number) = resolveNumber(who) ?: return Result("I couldn't find $who in your contacts, $sir.")
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
        return Result("Calling $name.")
    }

    @Suppress("DEPRECATION")
    private fun sms(who: String, body: String): Result {
        val (name, number) = resolveNumber(who) ?: return Result("I couldn't find $who in your contacts, $sir.")
        val text = body.replaceFirstChar { it.uppercase() }
        if (!granted(Manifest.permission.SEND_SMS)) {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))
            return Result("I've written the message to $name. Just hit send, $sir.")
        }
        val manager: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        manager.sendMultipartTextMessage(number, null, manager.divideMessage(text), null, null)
        return Result("Message sent to $name.")
    }

    private fun whatsapp(who: String, body: String): Result {
        val (name, number) = resolveNumber(who) ?: return Result("I couldn't find $who in your contacts, $sir.")
        var digits = number.filter { it.isDigit() }
        val cc = prefs.countryCode.filter { it.isDigit() }
        if (number.trim().startsWith("00")) digits = digits.drop(2)
        else if (!number.trim().startsWith("+") && cc.isNotEmpty()) {
            digits = cc + digits.trimStart('0')
        }
        val text = body.replaceFirstChar { it.uppercase() }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(text))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
            ?: return Result("WhatsApp isn't installed, $sir.")
        intent.setPackage(pkg)
        if (!launch(intent)) return Result("I couldn't open WhatsApp, $sir.")
        val s = a11y
        return if (s != null) {
            s.clickWhenAppears("$pkg:id/send", "Send")
            Result("Sending your WhatsApp message to $name.")
        } else {
            Result("Message ready for $name. Tap send, $sir.")
        }
    }

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ----- Web, YouTube, maps, camera -----
    private fun search(query: String): Result {
        val ok = launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        return Result(if (ok) "Searching for $query." else "I couldn't open a browser, $sir.")
    }

    private fun youtube(query: String): Result {
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query)
        val ok = launch(yt) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        return Result(if (ok) "Here's $query on YouTube." else "I couldn't open YouTube, $sir.")
    }

    private fun navigate(place: String): Result {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return Result(if (ok) "Starting navigation to $place." else "I couldn't find a maps app, $sir.")
    }

    private fun map(place: String): Result {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return Result(if (ok) "Here's $place on the map." else "I couldn't find a maps app, $sir.")
    }

    private fun camera(selfie: Boolean): Result {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return Result(if (launch(intent)) "Camera ready. Smile, $sir." else "I couldn't open the camera, $sir.")
    }

    // ----- Apps -----
    private fun findApp(spoken: String, loose: Boolean = true): Pair<String, String>? {
        val target = spoken.lowercase(Locale.US).replace(" ", "")
        if (target.isEmpty()) return null
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return apps.firstOrNull { norm(it.first) == target }
            ?: apps.firstOrNull { norm(it.first).startsWith(target) }
            ?: (if (loose) apps.firstOrNull { target.length >= 3 && norm(it.first).contains(target) } else null)
            ?: (if (loose) apps.firstOrNull { norm(it.first).length >= 3 && target.contains(norm(it.first)) } else null)
    }

    private fun launchApp(label: String, pkg: String): Result {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Result("I can't open $label, $sir.")
        return if (launch(intent)) Result("Opening $label.") else Result("I can't open $label, $sir.")
    }

    private fun openApp(name: String): Result {
        when (name) {
            "settings", "setting" -> {
                launch(Intent(Settings.ACTION_SETTINGS)); return Result("Opening settings.")
            }
            "camera" -> return camera(false)
            "dialer", "phone", "dial pad", "dialpad" -> {
                launch(Intent(Intent.ACTION_DIAL)); return Result("Opening the dialer.")
            }
            "browser", "internet", "chrome" -> if (findApp(name) == null) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))); return Result("Opening the browser.")
            }
        }
        val app = findApp(name) ?: return Result("I couldn't find an app called $name, $sir.")
        return launchApp(app.first, app.second)
    }
}
