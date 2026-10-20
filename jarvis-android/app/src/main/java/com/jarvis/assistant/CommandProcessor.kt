package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
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
import com.jarvis.assistant.CommandParser.Action
import com.jarvis.assistant.CommandParser.Global
import com.jarvis.assistant.CommandParser.Media
import com.jarvis.assistant.CommandParser.Ringer
import com.jarvis.assistant.CommandParser.Switch
import com.jarvis.assistant.CommandParser.Thing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Carries out a parsed [Action] on the phone and returns what Voxa should say —
 * in English, Hindi or Urdu, whichever the user picked. Everything here works
 * without the AI key; only questions and screen vision use it.
 */
class CommandProcessor(private val ctx: Context) {

    enum class Control { NONE, STOP_LISTENING, RELOCK, NEW_CHAT }

    data class Result(
        val reply: String,
        val control: Control = Control.NONE,
        /** Runs after Voxa has finished speaking (e.g. lock screen, screenshot). */
        val after: (() -> Unit)? = null,
        /** When set, the service asks the AI brain this question and speaks its answer. */
        val ask: String? = null,
        /** Send a screenshot of the current screen along with [ask]. */
        val withScreen: Boolean = false
    )

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    /** An online AI (Groq or Claude) is switched on; otherwise Voxa works fully offline. */
    private val hasBrain: Boolean get() = prefs.aiActive
    private val a11y: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    // ---------------------------------------------------------------- language

    private val mode: String get() = Lang.mode(prefs)
    private val sir: String get() = prefs.title
    private val sirHi: String get() = if (prefs.title.equals("sir", true)) "सर" else prefs.title
    private val sirUr: String get() = if (prefs.title.equals("sir", true)) "سر" else prefs.title

    /** Picks the line in the language Voxa is speaking. */
    private fun L(en: String, hi: String, ur: String) = when (mode) {
        "hi" -> hi
        "ur" -> ur
        else -> en
    }

    private val needA11y: Result
        get() = Result(
            L(
                "I need phone control for that, $sir. Turn it on in Settings, under Setup.",
                "इसके लिए मुझे फ़ोन कंट्रोल चाहिए, $sirHi। सेटिंग्स में सेटअप के अंदर इसे चालू कर दीजिए।",
                "اس کے لیے مجھے فون کنٹرول چاہیے، $sirUr۔ سیٹنگز میں سیٹ اپ کے اندر اسے آن کر دیجیے۔"
            )
        )

    private val noClockApp get() = Result(L("I couldn't find a clock app, $sir.", "घड़ी वाली ऐप नहीं मिली, $sirHi।", "گھڑی والی ایپ نہیں ملی، $sirUr۔"))

    // ---------------------------------------------------------------- entry

    fun handle(input: String): Result {
        val action = CommandParser.parse(input, nonEnglishMode = Lang.hindustaniInput(prefs))
        return try {
            execute(action)
        } catch (e: SecurityException) {
            Result(L(
                "I don't have permission for that yet, $sir. Please grant it in Settings.",
                "इसकी इजाज़त अभी नहीं मिली, $sirHi। सेटिंग्स में दे दीजिए।",
                "اس کی اجازت ابھی نہیں ملی، $sirUr۔ سیٹنگز میں دے دیجیے۔"
            ))
        } catch (e: Exception) {
            Result(L("Something went wrong, $sir.", "कुछ गड़बड़ हो गई, $sirHi।", "کچھ گڑبڑ ہو گئی، $sirUr۔"))
        }
    }

    private fun execute(action: Action): Result = when (action) {
        Action.Empty -> Result(Lang.get(prefs, Lang.Line.YES))
        Action.StopListening -> Result(
            L("Going offline. Call me when you need me, $sir.", "मैं आराम करता हूँ। ज़रूरत हो तो बुला लीजिए, $sirHi।", "میں آرام کرتا ہوں۔ ضرورت ہو تو بلا لیجیے، $sirUr۔"),
            Control.STOP_LISTENING
        )
        Action.Relock -> Result(L("Voice lock engaged, $sir.", "वॉइस लॉक चालू, $sirHi।", "وائس لاک آن، $sirUr۔"), Control.RELOCK)
        Action.NewChat -> Result(L("Fresh start, $sir. What's on your mind?", "नई शुरुआत, $sirHi। बताइए?", "نئی شروعات، $sirUr۔ بتائیے؟"), Control.NEW_CHAT)
        Action.Greet -> Result(L("${greeting()}, $sir. At your service.", "${greeting()}, $sirHi। हुक्म कीजिए।", "${greeting()}، $sirUr۔ حکم کیجیے۔"))
        Action.Briefing -> briefing()
        Action.WhoAreYou -> Result(L(
            "I'm Voxa, your personal assistant, $sir.",
            "मैं वोक्सा हूँ, आपकी पर्सनल असिस्टेंट, $sirHi।",
            "میں ووکسا ہوں، آپ کی پرسنل اسسٹنٹ، $sirUr۔"
        ))
        Action.Thanks -> Result(L("Always a pleasure, $sir.", "आपकी ख़िदमत में हमेशा, $sirHi।", "آپ کی خدمت میں ہمیشہ، $sirUr۔"))
        Action.Help -> Result(L(HELP_EN, HELP_HI, HELP_UR))
        Action.Time -> {
            val c = Calendar.getInstance()
            val t = spokenTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            Result(L("It's $t, $sir.", "अभी $t बजे हैं, $sirHi।", "ابھی $t بجے ہیں، $sirUr۔"))
        }
        Action.Date -> Result(L("Today is ${spokenDate()}.", "आज ${spokenDate()} है।", "آج ${spokenDate()} ہے۔"))
        Action.Battery -> battery()
        is Action.GlobalAction -> when (action.kind) {
            Global.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, L("Home.", "होम।", "ہوم۔"))
            Global.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "")
            Global.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "")
            Global.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "")
            Global.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "")
            Global.POWER -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "")
        }
        Action.Screenshot -> {
            val s = a11y
            when {
                s == null -> needA11y
                Build.VERSION.SDK_INT < 28 -> Result(L("Screenshots need Android 9 or newer, $sir.", "स्क्रीनशॉट के लिए Android 9 या नया चाहिए।", "اسکرین شاٹ کے لیے Android 9 یا نیا چاہیے۔"))
                else -> Result(L("Screenshot.", "स्क्रीनशॉट।", "اسکرین شاٹ۔"), after = { main.postDelayed({ s.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600) })
            }
        }
        Action.LockScreen -> {
            val s = a11y
            when {
                s == null -> needA11y
                Build.VERSION.SDK_INT < 28 -> Result(L("Locking needs Android 9 or newer, $sir.", "लॉक के लिए Android 9 या नया चाहिए।", "لاک کے لیے Android 9 یا نیا چاہیے۔"))
                else -> Result(L("Locking the phone.", "फ़ोन लॉक कर रही हूँ।", "فون لاک کر رہی ہوں۔"), after = { s.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) })
            }
        }
        is Action.Scroll -> a11y?.let {
            if (it.scroll(action.direction)) Result("") else Result(L("I couldn't scroll here, $sir.", "यहाँ स्क्रॉल नहीं हो रहा।", "یہاں اسکرول نہیں ہو رہا۔"))
        } ?: needA11y
        is Action.Click -> a11y?.let {
            if (it.clickText(action.target)) Result("")
            else Result(L("I can't find ${action.target} on the screen, $sir.", "स्क्रीन पर ${action.target} नहीं मिला।", "اسکرین پر ${action.target} نہیں ملا۔"))
        } ?: needA11y
        is Action.Type -> a11y?.let {
            if (it.typeText(action.text)) Result(L("Typed.", "लिख दिया।", "لکھ دیا۔"))
            else Result(L("I can't find a text box on the screen, $sir.", "स्क्रीन पर लिखने की जगह नहीं मिली।", "اسکرین پر لکھنے کی جگہ نہیں ملی۔"))
        } ?: needA11y
        Action.ReadScreen -> a11y?.let { readScreen(it) } ?: needA11y
        is Action.DescribeScreen -> {
            val s = a11y
            when {
                s == null -> needA11y
                !hasBrain -> readScreen(s)
                else -> Result("", ask = action.question ?: "Look at my screen. Briefly tell me what it shows and anything important I should notice.", withScreen = true)
            }
        }
        Action.ReadNotifications -> readNotifications()
        is Action.Remember -> {
            prefs.addMemory(action.fact)
            Result(L("Got it, $sir. I'll remember that.", "ठीक है $sirHi, मैं याद रखूँगी।", "ٹھیک ہے $sirUr، میں یاد رکھوں گی۔"))
        }
        Action.Recall -> {
            val memories = prefs.memories
            if (memories.isEmpty()) Result(L("You haven't asked me to remember anything yet, $sir.", "आपने अभी तक कुछ याद रखने को नहीं कहा।", "آپ نے ابھی تک کچھ یاد رکھنے کو نہیں کہا۔"))
            else Result(L("Here's what I remember. ", "मुझे यह याद है। ", "مجھے یہ یاد ہے۔ ") + memories.joinToString(". ") { it.trimEnd('.', '۔', '।') } + ".")
        }
        Action.ClearMemory -> {
            prefs.memories = emptyList()
            Result(L("Done. My memory is wiped clean, $sir.", "हो गया। सब भूल गई।", "ہو گیا۔ سب بھول گئی۔"))
        }
        is Action.Torch -> torch(action.on)
        is Action.VolumeStep -> volumeStep(action.up)
        is Action.VolumeSet -> volumeSet(action.percent)
        is Action.Mute -> mute(action.on)
        is Action.RingerMode -> when (action.mode) {
            Ringer.SILENT -> ringer(AudioManager.RINGER_MODE_SILENT, L("Silent mode.", "साइलेंट मोड।", "سائلنٹ موڈ۔"))
            Ringer.VIBRATE -> ringer(AudioManager.RINGER_MODE_VIBRATE, L("Vibrate mode.", "वाइब्रेट मोड।", "وائبریٹ موڈ۔"))
            Ringer.NORMAL -> ringer(AudioManager.RINGER_MODE_NORMAL, L("Ringer on.", "रिंगर चालू।", "رنگر آن۔"))
        }
        is Action.MediaKey -> when (action.key) {
            Media.PLAY -> media(KeyEvent.KEYCODE_MEDIA_PLAY)
            Media.PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
            Media.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            Media.PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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
        // Urdu/Hindi we couldn't map offline: the AI if there's a key, otherwise Google.
        is Action.Ask -> if (hasBrain) Result("", ask = action.text) else search(action.text)
        is Action.Unknown -> {
            val app = findApp(action.text, loose = false)
            when {
                app != null -> launchApp(app.first, app.second)
                hasBrain -> Result("", ask = action.text)
                else -> search(action.text)
            }
        }
    }

    // =================================================================
    // Helpers
    // =================================================================

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (mode) {
            "hi" -> when { h in 4..11 -> "सुप्रभात"; h in 12..16 -> "नमस्ते"; else -> "शुभ संध्या" }
            "ur" -> when { h in 4..11 -> "صبح بخیر"; h in 12..16 -> "السلام علیکم"; else -> "شام بخیر" }
            else -> when { h < 5 -> "Good evening"; h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
        }
    }

    /** "5:30 PM" / "شام 5:30" / "शाम 5:30" */
    private fun spokenTime(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val hm = "$h12:${minute.toString().padStart(2, '0')}"
        val part = when (hour) {
            in 4..11 -> 0
            in 12..15 -> 1
            in 16..18 -> 2
            else -> 3
        }
        return when (mode) {
            "hi" -> "${listOf("सुबह", "दोपहर", "शाम", "रात")[part]} $hm"
            "ur" -> "${listOf("صبح", "دوپہر", "شام", "رات")[part]} $hm"
            else -> "$hm ${if (hour < 12) "AM" else "PM"}"
        }
    }

    private fun spokenDate(): String = when (mode) {
        "hi" -> SimpleDateFormat("EEEE, d MMMM", Locale("hi", "IN")).format(Date())
        "ur" -> SimpleDateFormat("EEEE، d MMMM", Locale("ur", "PK")).format(Date())
        else -> SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
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

    private fun readScreen(s: JarvisAccessibilityService): Result {
        val text = s.readScreen()
        return Result(if (text.isBlank()) L("I don't see any text on the screen, $sir.", "स्क्रीन पर कोई लिखाई नहीं दिख रही।", "اسکرین پر کوئی تحریر نظر نہیں آ رہی۔") else text)
    }

    private fun batteryLevel(): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    private fun battery(): Result {
        val (level, charging) = batteryLevel()
        return Result(L(
            "Battery is at $level percent${if (charging) " and charging" else ""}, $sir.",
            "बैटरी $level प्रतिशत है${if (charging) " और चार्ज हो रही है" else ""}, $sirHi।",
            "بیٹری $level فیصد ہے${if (charging) " اور چارج ہو رہی ہے" else ""}، $sirUr۔"
        ))
    }

    private fun briefing(): Result {
        val c = Calendar.getInstance()
        val time = spokenTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val (level, _) = batteryLevel()
        val local = L(
            "${greeting()}, $sir. It's $time on ${spokenDate()}. Battery is at $level percent.",
            "${greeting()}, $sirHi। अभी $time बजे हैं, आज ${spokenDate()} है। बैटरी $level प्रतिशत है।",
            "${greeting()}، $sirUr۔ ابھی $time بجے ہیں، آج ${spokenDate()} ہے۔ بیٹری $level فیصد ہے۔"
        )
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
            return Result(L(
                "I need notification access to read your messages, $sir. Turn on Voxa on the page I've opened.",
                "मैसेज पढ़ने के लिए नोटिफिकेशन की इजाज़त चाहिए। खुले हुए पेज पर वोक्सा चालू कर दीजिए।",
                "میسج پڑھنے کے لیے نوٹیفکیشن کی اجازت چاہیے۔ کھلے ہوئے صفحے پر ووکسا آن کر دیجیے۔"
            ))
        }
        val items = listener.recent(limit = 6)
        if (items.isEmpty()) return Result(L("No new notifications, $sir.", "कोई नया नोटिफिकेशन नहीं है।", "کوئی نیا نوٹیفکیشن نہیں ہے۔"))
        listener.markAllRead()
        val spoken = items.joinToString(" ") { n ->
            val body = n.text.take(140)
            if (n.title.isNotBlank()) L("${n.app}, from ${n.title}: $body.", "${n.app}, ${n.title} की तरफ़ से: $body।", "${n.app}، ${n.title} کی طرف سے: $body۔")
            else "${n.app}: $body."
        }
        val count = items.size
        return Result(L(
            "You have $count new notification${if (count == 1) "" else "s"}. $spoken",
            "आपके $count नए नोटिफिकेशन हैं। $spoken",
            "آپ کے $count نئے نوٹیفکیشن ہیں۔ $spoken"
        ))
    }

    // ----- Flashlight -----
    private fun torch(on: Boolean): Result {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result(L("This phone doesn't have a flashlight, $sir.", "इस फ़ोन में टॉर्च नहीं है।", "اس فون میں ٹارچ نہیں ہے۔"))
        cm.setTorchMode(id, on)
        return Result(
            if (on) L("Flashlight on.", "टॉर्च चालू।", "ٹارچ آن۔")
            else L("Flashlight off.", "टॉर्च बंद।", "ٹارچ بند۔")
        )
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
        return Result(
            if (up) L("Volume up.", "आवाज़ बढ़ा दी।", "آواز بڑھا دی۔")
            else L("Volume down.", "आवाज़ कम कर दी।", "آواز کم کر دی۔")
        )
    }

    private fun volumeSet(percent: Int): Result {
        val am = audio
        val p = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max * p / 100, AudioManager.FLAG_SHOW_UI)
        return Result(L("Volume at $p percent.", "आवाज़ $p प्रतिशत।", "آواز $p فیصد۔"))
    }

    private fun mute(on: Boolean): Result {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return Result(
            if (on) L("Muted.", "म्यूट कर दिया।", "میوٹ کر دیا۔")
            else L("Sound is back on.", "आवाज़ वापस चालू।", "آواز واپس آن۔")
        )
    }

    private fun ringer(mode: Int, reply: String): Result {
        return try {
            audio.ringerMode = mode
            Result(reply)
        } catch (e: SecurityException) {
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Result(L(
                "Android needs you to allow Do Not Disturb access for me first, $sir. I've opened the page.",
                "पहले मुझे डू नॉट डिस्टर्ब की इजाज़त दीजिए। पेज खोल दिया है।",
                "پہلے مجھے ڈو ناٹ ڈسٹرب کی اجازت دیجیے۔ صفحہ کھول دیا ہے۔"
            ))
        }
    }

    private fun media(keyCode: Int): Result {
        val am = audio
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return Result("")
    }

    // ----- Brightness -----
    /** percent 1..100, or -1 = brighter, -2 = dimmer. */
    private fun brightness(percent: Int): Result {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return Result(L(
                "Please allow me to modify system settings first, $sir. I've opened the page.",
                "पहले मुझे सिस्टम सेटिंग्स बदलने की इजाज़त दीजिए। पेज खोल दिया है।",
                "پہلے مجھے سسٹم سیٹنگز بدلنے کی اجازت دیجیے۔ صفحہ کھول دیا ہے۔"
            ))
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
        val p = value * 100 / 255
        return Result(L("Brightness at $p percent.", "ब्राइटनेस $p प्रतिशत।", "برائٹنس $p فیصد۔"))
    }

    // ----- Toggles -----
    private fun toggle(thing: Thing, state: Switch): Result = when (thing) {
        Thing.WIFI -> wifi(state)
        Thing.BLUETOOTH -> quickTile(listOf("bluetooth"), L("Bluetooth", "ब्लूटूथ", "بلوٹوتھ"), Settings.ACTION_BLUETOOTH_SETTINGS)
        Thing.AIRPLANE -> quickTile(listOf("airplane", "aeroplane", "flight mode"), L("Airplane mode", "एयरप्लेन मोड", "ایئرپلین موڈ"), Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Thing.HOTSPOT -> quickTile(listOf("hotspot"), L("Hotspot", "हॉटस्पॉट", "ہاٹ سپاٹ"), Settings.ACTION_WIRELESS_SETTINGS)
        Thing.MOBILE_DATA -> quickTile(listOf("mobile data", "data"), L("Mobile data", "मोबाइल डेटा", "موبائل ڈیٹا"), Settings.ACTION_DATA_ROAMING_SETTINGS)
        Thing.LOCATION -> quickTile(listOf("location"), L("Location", "लोकेशन", "لوکیشن"), Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Thing.DND -> quickTile(listOf("do not disturb"), L("Do not disturb", "डू नॉट डिस्टर्ब", "ڈو ناٹ ڈسٹرب"), Settings.ACTION_SOUND_SETTINGS)
        Thing.ROTATION -> quickTile(listOf("rotate", "rotation"), L("Auto rotate", "ऑटो रोटेट", "آٹو روٹیٹ"), Settings.ACTION_DISPLAY_SETTINGS)
        Thing.BATTERY_SAVER -> quickTile(listOf("battery saver", "power saving"), L("Battery saver", "बैटरी सेवर", "بیٹری سیور"), Settings.ACTION_BATTERY_SAVER_SETTINGS)
        Thing.DARK_MODE -> quickTile(listOf("dark"), L("Dark mode", "डार्क मोड", "ڈارک موڈ"), Settings.ACTION_DISPLAY_SETTINGS)
        Thing.SOUND -> mute(state == Switch.OFF)
    }

    @Suppress("DEPRECATION")
    private fun wifi(state: Switch): Result {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT < 29) {
            wm.isWifiEnabled = state == Switch.ON || (state == Switch.TOGGLE && !wm.isWifiEnabled)
            return Result(if (wm.isWifiEnabled) L("Wi-Fi on.", "वाई-फ़ाई चालू।", "وائی فائی آن۔") else L("Wi-Fi off.", "वाई-फ़ाई बंद।", "وائی فائی بند۔"))
        }
        if (state == Switch.ON && wm.isWifiEnabled) return Result(L("Wi-Fi is already on, $sir.", "वाई-फ़ाई पहले से चालू है।", "وائی فائی پہلے سے آن ہے۔"))
        if (state == Switch.OFF && !wm.isWifiEnabled) return Result(L("Wi-Fi is already off, $sir.", "वाई-फ़ाई पहले से बंद है।", "وائی فائی پہلے سے بند ہے۔"))
        launch(Intent(Settings.Panel.ACTION_WIFI))
        return Result(L(
            "Android only lets you flip Wi-Fi yourself on this version. Here's the switch, $sir.",
            "इस Android पर वाई-फ़ाई आपको खुद बदलना होगा। स्विच खोल दिया है।",
            "اس Android پر وائی فائی آپ کو خود بدلنا ہوگا۔ سوئچ کھول دیا ہے۔"
        ))
    }

    private fun quickTile(labels: List<String>, name: String, fallbackAction: String): Result {
        val s = a11y
        if (s == null) {
            launch(Intent(fallbackAction))
            return Result(L("Opening $name settings, $sir.", "$name की सेटिंग खोल रही हूँ।", "$name کی سیٹنگ کھول رہی ہوں۔"))
        }
        s.toggleQuickSetting(labels) { ok ->
            if (!ok) launch(Intent(fallbackAction))
        }
        return Result(L("Switching $name.", "$name बदल रही हूँ।", "$name بدل رہی ہوں۔"))
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
        return Result(L("Opening $name settings.", "$name सेटिंग्स खोल रही हूँ।", "$name سیٹنگز کھول رہی ہوں۔"))
    }

    // ----- Alarms, reminders & timers -----
    private fun alarm(hour: Int, minute: Int, label: String?): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.replaceFirstChar { it.uppercase() } ?: "Voxa")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return noClockApp
        val time = spokenTime(hour, minute)
        return Result(
            if (label != null && label != "reminder") L(
                "I'll remind you to $label at $time, $sir.",
                "$time बजे याद दिला दूँगी: $label।",
                "$time بجے یاد دلا دوں گی: $label۔"
            ) else L("Alarm set for $time, $sir.", "$time बजे का अलार्म लग गया, $sirHi।", "$time بجے کا الارم لگ گیا، $sirUr۔")
        )
    }

    private fun timer(seconds: Int, label: String?): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_399))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.replaceFirstChar { it.uppercase() } ?: "Voxa")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return noClockApp
        val span = spokenDuration(seconds)
        return Result(
            if (label != null && label != "reminder") L(
                "I'll remind you to $label in $span.",
                "$span बाद याद दिला दूँगी: $label।",
                "$span بعد یاد دلا دوں گی: $label۔"
            ) else L("Timer set for $span.", "$span का टाइमर लग गया।", "$span کا ٹائمر لگ گیا۔")
        )
    }

    private fun spokenDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        when (mode) {
            "hi" -> {
                if (h > 0) parts.add("$h घंटे")
                if (m > 0) parts.add("$m मिनट")
                if (s > 0 || parts.isEmpty()) parts.add("$s सेकंड")
                return parts.joinToString(" ")
            }
            "ur" -> {
                if (h > 0) parts.add("$h گھنٹے")
                if (m > 0) parts.add("$m منٹ")
                if (s > 0 || parts.isEmpty()) parts.add("$s سیکنڈ")
                return parts.joinToString(" ")
            }
        }
        if (h > 0) parts.add("$h hour${if (h == 1) "" else "s"}")
        if (m > 0) parts.add("$m minute${if (m == 1) "" else "s"}")
        if (s > 0 || parts.isEmpty()) parts.add("$s second${if (s == 1) "" else "s"}")
        return parts.joinToString(" and ")
    }

    // ----- Contacts, calls, messages -----

    /** Finds a contact by name, also across scripts ("امی" finds "Ammi"). Returns (name, number). */
    private fun findContact(name: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        // 1) Plain text search (also matches contacts saved in Urdu/Hindi script).
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { cur ->
            var first: Pair<String, String>? = null
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                if (n.equals(name, ignoreCase = true)) return n to num
                if (first == null) first = n to num
            }
            if (first != null) return first
        }
        // 2) Match by sound across scripts.
        val all = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cur ->
            while (cur.moveToNext() && all.size < 5000) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                all.add(n to num)
            }
        }
        return Translit.bestMatch(name, all, { it.first })
    }

    /** Returns (displayName, number) for a spoken name or a spoken number. */
    private fun resolveNumber(who: String): Pair<String, String>? {
        val digits = who.replace(Regex("[\\s-]"), "")
        if (digits.matches(Regex("\\+?\\d{3,}"))) return who to digits
        return findContact(who.trim())
    }

    private fun notFound(who: String) = Result(L(
        "I couldn't find $who in your contacts, $sir.",
        "कॉन्टैक्ट्स में $who नहीं मिला, $sirHi।",
        "کانٹیکٹس میں $who نہیں ملا، $sirUr۔"
    ))

    private fun call(who: String): Result {
        if (!granted(Manifest.permission.READ_CONTACTS) && !who.any { it.isDigit() })
            return Result(L("I need permission to read your contacts first, $sir.", "पहले कॉन्टैक्ट्स की इजाज़त चाहिए।", "پہلے کانٹیکٹس کی اجازت چاہیے۔"))
        val (name, number) = resolveNumber(who) ?: return notFound(who)
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
        return Result(L("Calling $name.", "$name को कॉल कर रही हूँ।", "$name کو کال کر رہی ہوں۔"))
    }

    @Suppress("DEPRECATION")
    private fun sms(who: String, body: String): Result {
        val (name, number) = resolveNumber(who) ?: return notFound(who)
        val text = body.trim().replaceFirstChar { it.uppercase() }
        if (text.isEmpty()) {
            // No text yet: open the conversation so the user can type it.
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))))
            return Result(L("Here's your chat with $name. What should I write?", "$name की चैट खोल दी।", "$name کی چیٹ کھول دی۔"))
        }
        if (!granted(Manifest.permission.SEND_SMS)) {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))
            return Result(L("I've written the message to $name. Just hit send, $sir.", "$name के लिए मैसेज लिख दिया। बस भेज दीजिए।", "$name کے لیے میسج لکھ دیا۔ بس بھیج دیجیے۔"))
        }
        val manager: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        manager.sendMultipartTextMessage(number, null, manager.divideMessage(text), null, null)
        return Result(L("Message sent to $name.", "$name को मैसेज भेज दिया।", "$name کو میسج بھیج دیا۔"))
    }

    private fun whatsapp(who: String, body: String): Result {
        val (name, number) = resolveNumber(who) ?: return notFound(who)
        var digits = number.filter { it.isDigit() }
        val cc = prefs.countryCode.filter { it.isDigit() }
        if (number.trim().startsWith("00")) digits = digits.drop(2)
        else if (!number.trim().startsWith("+") && cc.isNotEmpty()) {
            digits = cc + digits.trimStart('0')
        }
        val text = body.trim().replaceFirstChar { it.uppercase() }
        val query = "phone=$digits" + if (text.isNotEmpty()) "&text=" + Uri.encode(text) else ""
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
            ?: return Result(L("WhatsApp isn't installed, $sir.", "व्हाट्सएप इंस्टॉल नहीं है।", "واٹس ایپ انسٹال نہیں ہے۔"))
        // Open the chat inside WhatsApp itself (never the browser).
        val opened = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?$query")).setPackage(pkg)) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?$query")).setPackage(pkg))
        if (!opened) return Result(L("I couldn't open WhatsApp, $sir.", "व्हाट्सएप नहीं खुला।", "واٹس ایپ نہیں کھلا۔"))
        if (text.isEmpty()) {
            return Result(L(
                "Here's your WhatsApp chat with $name. Tell me what to write, or type it there.",
                "$name की व्हाट्सएप चैट खोल दी। बताइए क्या लिखना है।",
                "$name کی واٹس ایپ چیٹ کھول دی۔ بتائیے کیا لکھنا ہے۔"
            ))
        }
        val s = a11y
        return if (s != null) {
            s.clickWhenAppears("$pkg:id/send", "Send")
            Result(L("Sending your WhatsApp message to $name.", "$name को व्हाट्सएप मैसेज भेज रही हूँ।", "$name کو واٹس ایپ میسج بھیج رہی ہوں۔"))
        } else {
            Result(L("Message ready for $name. Tap send, $sir.", "$name के लिए मैसेज तैयार है। भेज दीजिए।", "$name کے لیے میسج تیار ہے۔ بھیج دیجیے۔"))
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
        // A results URL loads straight away; the "web search" intent only fills the box on some phones.
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))) ||
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        if (!ok) return Result(L("I couldn't open a browser, $sir.", "ब्राउज़र नहीं खुला।", "براؤزر نہیں کھلا۔"))
        // AI is switched off but a key exists: say how to get a direct answer instead.
        if (!hasBrain && (prefs.groqKey.isNotBlank() || prefs.apiKey.isNotBlank())) {
            return Result(L(
                "AI is off, so here are Google's results. Turn on the AI chip for a direct answer.",
                "AI बंद है, इसलिए गूगल के नतीजे खोल दिए। सीधे जवाब के लिए AI चालू कर दीजिए।",
                "اے آئی بند ہے، اس لیے گوگل کے نتائج کھول دیے۔ سیدھے جواب کے لیے اے آئی آن کر دیجیے۔"
            ))
        }
        return Result(L("Here's what Google has on that.", "गूगल के नतीजे खोल दिए।", "گوگل کے نتائج کھول دیے۔"))
    }

    private fun youtube(query: String): Result {
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query)
        val ok = launch(yt) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        return Result(
            if (ok) L("Here's $query on YouTube.", "यूट्यूब पर $query।", "یوٹیوب پر $query۔")
            else L("I couldn't open YouTube, $sir.", "यूट्यूब नहीं खुला।", "یوٹیوب نہیں کھلا۔")
        )
    }

    private fun navigate(place: String): Result {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return Result(
            if (ok) L("Starting navigation to $place.", "$place का रास्ता शुरू कर रही हूँ।", "$place کا راستہ شروع کر رہی ہوں۔")
            else L("I couldn't find a maps app, $sir.", "मैप्स ऐप नहीं मिली।", "میپس ایپ نہیں ملی۔")
        )
    }

    private fun map(place: String): Result {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return Result(
            if (ok) L("Here's $place on the map.", "नक्शे पर $place।", "نقشے پر $place۔")
            else L("I couldn't find a maps app, $sir.", "मैप्स ऐप नहीं मिली।", "میپس ایپ نہیں ملی۔")
        )
    }

    private fun camera(selfie: Boolean): Result {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return Result(
            if (launch(intent)) L("Camera ready. Smile, $sir.", "कैमरा तैयार है। मुस्कुराइए।", "کیمرا تیار ہے۔ مسکرائیے۔")
            else L("I couldn't open the camera, $sir.", "कैमरा नहीं खुला।", "کیمرا نہیں کھلا۔")
        )
    }

    // ----- Apps -----

    /** Finds an installed app by spoken name — English, or Urdu/Hindi script matched by sound. */
    private fun findApp(spoken: String, loose: Boolean = true): Pair<String, String>? {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        val english = Translit.knownApp(spoken) ?: spoken
        fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{M}0-9]"), "")
        val target = norm(english)
        if (target.isEmpty()) return null
        apps.firstOrNull { norm(it.first) == target }?.let { return it }
        apps.firstOrNull { norm(it.first).startsWith(target) }?.let { return it }
        if (loose) {
            apps.firstOrNull { target.length >= 3 && norm(it.first).contains(target) }?.let { return it }
            apps.firstOrNull { norm(it.first).length >= 3 && target.contains(norm(it.first)) }?.let { return it }
        }
        // Urdu/Hindi names, or slightly misheard English ones: match by sound.
        if (loose || !Translit.isLatin(spoken)) {
            Translit.bestMatch(english, apps, { it.first }, threshold = 0.8f)?.let { return it }
        }
        return null
    }

    private fun launchApp(label: String, pkg: String): Result {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Result(L("I can't open $label, $sir.", "$label नहीं खुल रही।", "$label نہیں کھل رہی۔"))
        return if (launch(intent)) Result(L("Opening $label.", "$label खोल रही हूँ।", "$label کھول رہی ہوں۔"))
        else Result(L("I can't open $label, $sir.", "$label नहीं खुल रही।", "$label نہیں کھل رہی۔"))
    }

    private fun openApp(name: String): Result {
        when (Translit.knownApp(name) ?: name) {
            "settings", "setting" -> {
                launch(Intent(Settings.ACTION_SETTINGS))
                return Result(L("Opening settings.", "सेटिंग्स खोल रही हूँ।", "سیٹنگز کھول رہی ہوں۔"))
            }
            "camera" -> return camera(false)
            "dialer", "phone", "dial pad", "dialpad" -> {
                launch(Intent(Intent.ACTION_DIAL))
                return Result(L("Opening the dialer.", "डायलर खोल रही हूँ।", "ڈائلر کھول رہی ہوں۔"))
            }
            "browser", "internet", "chrome" -> if (findApp(name) == null) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                return Result(L("Opening the browser.", "ब्राउज़र खोल रही हूँ।", "براؤزر کھول رہی ہوں۔"))
            }
        }
        val app = findApp(name) ?: return Result(L(
            "I couldn't find an app called $name, $sir.",
            "$name नाम की कोई ऐप नहीं मिली।",
            "$name نام کی کوئی ایپ نہیں ملی۔"
        ))
        return launchApp(app.first, app.second)
    }

    companion object {
        private const val HELP_EN = "I can open apps, make calls, send texts and WhatsApp messages, read your notifications, " +
            "remember things, set reminders, alarms and timers, control the flashlight, volume, brightness and quick settings, " +
            "play YouTube, navigate, take screenshots, lock the phone, and tap, scroll and type on screen. " +
            "In English, Hindi or Urdu. With an AI key I can also answer any question and see your screen."
        private const val HELP_HI = "मैं ऐप खोल सकती हूँ, कॉल और मैसेज कर सकती हूँ, व्हाट्सएप भेज सकती हूँ, नोटिफिकेशन पढ़ सकती हूँ, " +
            "बातें याद रख सकती हूँ, अलार्म, टाइमर और रिमाइंडर लगा सकती हूँ, टॉर्च, आवाज़, ब्राइटनेस और ब्लूटूथ बदल सकती हूँ, " +
            "यूट्यूब चला सकती हूँ, रास्ता दिखा सकती हूँ, स्क्रीनशॉट ले सकती हूँ और फ़ोन लॉक कर सकती हूँ। बस बोलिए।"
        private const val HELP_UR = "میں ایپ کھول سکتی ہوں، کال اور میسج کر سکتی ہوں، واٹس ایپ بھیج سکتی ہوں، نوٹیفکیشن پڑھ سکتی ہوں، " +
            "باتیں یاد رکھ سکتی ہوں، الارم، ٹائمر اور یاد دہانی لگا سکتی ہوں، ٹارچ، آواز، برائٹنس اور بلوٹوتھ بدل سکتی ہوں، " +
            "یوٹیوب چلا سکتی ہوں، راستہ دکھا سکتی ہوں، اسکرین شاٹ لے سکتی ہوں اور فون لاک کر سکتی ہوں۔ بس بولیے۔"
    }
}
