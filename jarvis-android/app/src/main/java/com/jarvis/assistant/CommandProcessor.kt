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
import com.jarvis.assistant.CommandParser.Action
import com.jarvis.assistant.CommandParser.Global
import com.jarvis.assistant.CommandParser.Media
import com.jarvis.assistant.CommandParser.Ringer
import com.jarvis.assistant.CommandParser.Switch
import com.jarvis.assistant.CommandParser.Thing

/** Turns a spoken sentence into an action on the phone and a spoken reply. */
class CommandProcessor(private val ctx: Context) {

    enum class Control { NONE, STOP_LISTENING, RELOCK, NEW_CHAT }

    data class Result(
        val reply: String,
        val control: Control = Control.NONE,
        /** Runs after Jarvis has finished speaking (e.g. lock screen, screenshot). */
        val after: (() -> Unit)? = null,
        /** When set, the service asks the AI brain this question and speaks its answer. */
        val ask: String? = null,
        /** Send a screenshot of the current screen along with [ask]. */
        val withScreen: Boolean = false
    )

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val sir: String get() = prefs.title
    private val hasBrain: Boolean get() = prefs.apiKey.isNotBlank()
    private val a11y: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    private val needA11y: Result
        get() = Result("I need phone control for that, $sir. Open my app and enable it under Setup, step two.")

    fun handle(input: String): Result {
        val action = CommandParser.parse(input, nonEnglishMode = !prefs.language.startsWith("en"))
        return try {
            execute(action)
        } catch (e: SecurityException) {
            Result("I don't have permission for that yet, $sir. Please grant it in Setup.")
        } catch (e: Exception) {
            Result("Something went wrong, $sir. ${e.message ?: ""}".trim())
        }
    }

    private fun execute(action: Action): Result = when (action) {
        Action.Empty -> Result(Lang.get(prefs, Lang.Line.YES))
        Action.StopListening -> Result("Going offline. Call me when you need me, $sir.", Control.STOP_LISTENING)
        Action.Relock -> Result("Voice lock engaged, $sir.", Control.RELOCK)
        Action.NewChat -> Result("Fresh start, $sir. What's on your mind?", Control.NEW_CHAT)
        Action.Greet -> Result("${greeting()}, $sir. At your service.")
        Action.Briefing -> briefing()
        Action.WhoAreYou -> Result("I am Jarvis. Just A Rather Very Intelligent System. Your personal assistant, $sir.")
        Action.Thanks -> Result("Always a pleasure, $sir.")
        Action.Help -> Result(HELP_SPOKEN)
        Action.Time -> Result("It's ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}, $sir.")
        Action.Date -> Result("Today is ${SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())}.")
        Action.Battery -> battery()
        is Action.GlobalAction -> when (action.kind) {
            Global.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Home.")
            Global.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Done.")
            Global.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.")
            Global.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Your notifications, $sir.")
            Global.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings.")
            Global.POWER -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Power menu.")
        }
        Action.Screenshot -> {
            val s = a11y
            when {
                s == null -> needA11y
                Build.VERSION.SDK_INT < 28 -> Result("Screenshots need Android 9 or newer, $sir.")
                else -> Result("Screenshot.", after = { main.postDelayed({ s.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600) })
            }
        }
        Action.LockScreen -> {
            val s = a11y
            when {
                s == null -> needA11y
                Build.VERSION.SDK_INT < 28 -> Result("Locking needs Android 9 or newer, $sir.")
                else -> Result("Locking the phone.", after = { s.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) })
            }
        }
        is Action.Scroll -> a11y?.let { if (it.scroll(action.direction)) Result("") else Result("I couldn't scroll here, $sir.") } ?: needA11y
        is Action.Click -> a11y?.let {
            if (it.clickText(action.target)) Result("") else Result("I can't find ${action.target} on the screen, $sir.")
        } ?: needA11y
        is Action.Type -> a11y?.let {
            if (it.typeText(action.text)) Result("Typed.") else Result("I can't find a text box on the screen, $sir.")
        } ?: needA11y
        Action.ReadScreen -> a11y?.let {
            val text = it.readScreen()
            Result(if (text.isBlank()) "I don't see any text on the screen, $sir." else text)
        } ?: needA11y
        is Action.DescribeScreen -> when {
            a11y == null -> needA11y
            !hasBrain -> a11y?.readScreen().orEmpty().let { Result(if (it.isBlank()) "I don't see any text on the screen, $sir." else it) }
            else -> Result("", ask = action.question ?: "Look at my screen. Briefly tell me what it shows and anything important I should notice.", withScreen = true)
        }
        Action.ReadNotifications -> readNotifications()
        is Action.Remember -> {
            prefs.addMemory(action.fact)
            Result("Got it, $sir. I'll remember that.")
        }
        Action.Recall -> {
            val memories = prefs.memories
            if (memories.isEmpty()) Result("You haven't asked me to remember anything yet, $sir.")
            else Result("Here's what I remember. " + memories.joinToString(". ") { it.trimEnd('.') } + ".")
        }
        Action.ClearMemory -> {
            prefs.memories = emptyList()
            Result("Done. My memory is wiped clean, $sir.")
        }
        is Action.Torch -> torch(action.on)
        is Action.VolumeStep -> volumeStep(action.up)
        is Action.VolumeSet -> volumeSet(action.percent)
        is Action.Mute -> mute(action.on)
        is Action.RingerMode -> when (action.mode) {
            Ringer.SILENT -> ringer(AudioManager.RINGER_MODE_SILENT, "Silent mode.")
            Ringer.VIBRATE -> ringer(AudioManager.RINGER_MODE_VIBRATE, "Vibrate mode.")
            Ringer.NORMAL -> ringer(AudioManager.RINGER_MODE_NORMAL, "Ringer on.")
        }
        is Action.MediaKey -> when (action.key) {
            Media.PLAY -> media(KeyEvent.KEYCODE_MEDIA_PLAY, "")
            Media.PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, "")
            Media.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT, "")
            Media.PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "")
        }
        is Action.BrightnessSet -> brightness(action.percent)
        is Action.BrightnessStep -> brightness(if (action.up) -1 else -2)
        is Action.Toggle -> toggle(action.thing, action.state)
        is Action.Alarm -> alarm(action.hour, action.minute, action.label)
        is Action.Timer -> timer(action.seconds, action.label)
        is Action.WhatsApp -> whatsapp(action.who, action.body)
        is Action.Sms -> sms(action.who, action.body)
        is Action.Call -> call(action.who)
        is Action.YouTube -> youtube(action.query)
        is Action.Navigate -> navigate(action.place)
        is Action.ShowOnMap -> map(action.place)
        is Action.Camera -> camera(action.selfie)
        is Action.Search -> search(action.query)
        is Action.SettingsPage -> settingsPage(action.name)
        is Action.OpenApp -> openApp(action.name)
        is Action.Question -> if (hasBrain) Result("", ask = action.text) else search(action.text)
        is Action.Ask -> if (hasBrain) Result("", ask = action.text) else Result(Lang.get(prefs, Lang.Line.NO_BRAIN))
        is Action.Unknown -> {
            val app = findApp(action.text, loose = false)
            when {
                app != null -> launchApp(app.first, app.second)
                hasBrain -> Result("", ask = action.text)
                else -> Result("Sorry $sir, I don't know how to do that yet. Add a Claude API key in my settings and I can answer anything.")
            }
        }
    }

    private fun briefing(): Result {
        val now = Date()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val local = "${greeting()}, $sir. It's ${SimpleDateFormat("h:mm a", Locale.US).format(now)} on " +
            "${SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)}. Battery is at $level percent."
        if (!hasBrain) return Result(local)
        val where = prefs.city.ifBlank { "my area" }
        return Result(
            local,
            ask = "Give me a quick spoken briefing for $where: today's weather in one sentence, then the two most important news headlines in one sentence each. No greeting, I've already been greeted."
        )
    }

    private fun readNotifications(): Result {
        val listener = JarvisNotificationListener.instance
        if (listener == null) {
            launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return Result("I need notification access to read your messages, $sir. Turn on Jarvis on the page I've opened.")
        }
        val items = listener.recent(limit = 6)
        if (items.isEmpty()) return Result("No new notifications, $sir.")
        listener.markAllRead()
        val spoken = items.joinToString(" ") { n ->
            val body = n.text.take(140)
            if (n.title.isNotBlank()) "${n.app}, from ${n.title}: $body." else "${n.app}: $body."
        }
        val count = items.size
        return Result("You have $count new notification${if (count == 1) "" else "s"}. $spoken")
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
    private fun toggle(thing: Thing, state: Switch): Result = when (thing) {
        Thing.WIFI -> wifi(state)
        Thing.BLUETOOTH -> quickTile(listOf("bluetooth"), "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS)
        Thing.AIRPLANE -> quickTile(listOf("airplane", "aeroplane", "flight mode"), "Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Thing.HOTSPOT -> quickTile(listOf("hotspot"), "Hotspot", Settings.ACTION_WIRELESS_SETTINGS)
        Thing.MOBILE_DATA -> quickTile(listOf("mobile data", "data"), "Mobile data", Settings.ACTION_DATA_ROAMING_SETTINGS)
        Thing.LOCATION -> quickTile(listOf("location"), "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Thing.DND -> quickTile(listOf("do not disturb"), "Do not disturb", Settings.ACTION_SOUND_SETTINGS)
        Thing.ROTATION -> quickTile(listOf("rotate", "rotation"), "Auto rotate", Settings.ACTION_DISPLAY_SETTINGS)
        Thing.BATTERY_SAVER -> quickTile(listOf("battery saver", "power saving"), "Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS)
        Thing.DARK_MODE -> quickTile(listOf("dark"), "Dark mode", Settings.ACTION_DISPLAY_SETTINGS)
        Thing.SOUND -> mute(state == Switch.OFF)
    }

    @Suppress("DEPRECATION")
    private fun wifi(state: Switch): Result {
        if (Build.VERSION.SDK_INT < 29) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = state == Switch.ON || (state == Switch.TOGGLE && !wm.isWifiEnabled)
            return Result("Wi-Fi ${if (wm.isWifiEnabled) "on" else "off"}.")
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (state == Switch.ON && wm.isWifiEnabled) return Result("Wi-Fi is already on, $sir.")
        if (state == Switch.OFF && !wm.isWifiEnabled) return Result("Wi-Fi is already off, $sir.")
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

    // ----- Alarms, reminders & timers -----
    private fun alarm(hour: Int, minute: Int, label: String?): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.replaceFirstChar { it.uppercase() } ?: "Jarvis alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return Result("I couldn't find a clock app, $sir.")
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val time = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
        return Result(if (label != null) "I'll remind you to $label at $time, $sir." else "Alarm set for $time, $sir.")
    }

    private fun timer(seconds: Int, label: String?): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_399))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.replaceFirstChar { it.uppercase() } ?: "Jarvis timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return Result("I couldn't find a clock app, $sir.")
        val span = spokenDuration(seconds)
        return Result(if (label != null) "I'll remind you to $label in $span." else "Timer set for $span.")
    }

    private fun spokenDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hour${if (h == 1) "" else "s"}")
        if (m > 0) parts.add("$m minute${if (m == 1) "" else "s"}")
        if (s > 0 || parts.isEmpty()) parts.add("$s second${if (s == 1) "" else "s"}")
        return parts.joinToString(" and ")
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

    companion object {
        private const val HELP_SPOKEN = "I can answer any question, look at your screen, read your notifications, open apps, " +
            "make calls, send texts and WhatsApp messages, remember things for you, set reminders, alarms and timers, " +
            "control the flashlight, volume, brightness and quick settings, play YouTube, navigate, take screenshots, " +
            "lock the phone, and tap, scroll and type on screen. In English, Hindi or Urdu. Just ask."
    }
}
