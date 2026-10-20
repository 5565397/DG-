package com.jarvis.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Understands phone commands spoken in Urdu or Hindi — Urdu script, Devanagari, or Roman
 * letters ("youtube kholo") — fully offline, no AI needed. Each command is rewritten as the
 * English command [CommandParser] already knows. Names, messages and search terms are kept
 * in their original script; [Translit] matches them to apps and contacts later.
 */
object Hindustani {

    private val devanagari = Regex("[\\u0900-\\u097F]")
    private val arabicScript = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    fun isDevanagari(text: String) = devanagari.containsMatchIn(text)
    fun isUrduScript(text: String) = arabicScript.containsMatchIn(text)
    fun isNonLatin(text: String) = isDevanagari(text) || isUrduScript(text)

    // ---------------------------------------------------------------- word lists
    // Each list: Devanagari | Urdu script | Roman.

    private val torch = listOf("टॉर्च", "टोर्च", "फ्लैशलाइट", "फ्लैश लाइट", "ٹارچ", "فلیش لائٹ", "torch", "flashlight")
    private val on = listOf("चालू", "ऑन", "जला", "जलाओ", "जला दो", "چالو", "آن", "جلا", "جلاؤ", "جلا دو", "on", "chalu", "jala", "jalao", "jala do")
    private val off = listOf("बंद", "ऑफ", "बुझा", "बुझाओ", "بند", "آف", "بجھا", "بجھاؤ", "band", "off", "bujha", "bujhao")
    private val volume = listOf("आवाज़", "आवाज", "वॉल्यूम", "वॉल्युम", "आवाज़", "آواز", "والیوم", "awaz", "awaaz", "aawaz", "volume")
    private val brightness = listOf("ब्राइटनेस", "रोशनी", "चमक", "برائٹنس", "روشنی", "چمک", "brightness", "roshni")
    private val up = listOf("बढ़ा", "बढ़ाओ", "बढाओ", "बढ़ा दो", "तेज़", "तेज", "ज़्यादा", "ज्यादा", "ऊपर", "بڑھا", "بڑھاؤ", "بڑھا دو", "تیز", "زیادہ", "اونچی", "اوپر",
        "badha", "badhao", "barhao", "barha do", "tez", "zyada", "ziada", "upar", "oonchi")
    private val down = listOf("कम", "धीमी", "धीमा", "नीचे", "کم", "دھیمی", "آہستہ", "نیچے", "kam", "dheemi", "dheema", "neeche", "aahista")
    private val mute = listOf("म्यूट", "चुप", "میوٹ", "mute")
    private val percent = "फ़ीसदी|फीसदी|प्रतिशत|परसेंट|فیصد|پرسنٹ|percent|fisad|%"
    private val time = listOf("टाइम", "समय", "वक़्त", "वक्त", "ٹائم", "وقت", "time", "waqt", "samay")
    private val what = listOf("क्या", "कितने", "कितना", "कितनी", "कौन सा", "कौनसा", "बताओ", "کیا", "کتنے", "کتنا", "کتنی", "کون سا", "بتاؤ",
        "kya", "kitne", "kitna", "kitni", "kaun sa", "batao")
    private val today = listOf("आज", "آج", "aaj")
    private val date = listOf("तारीख", "तारीख़", "दिनांक", "दिन", "تاریخ", "دن", "tareekh", "tarikh", "din")
    private val battery = listOf("बैटरी", "चार्ज", "بیٹری", "چارج", "battery", "charge")
    private val screenshot = listOf("स्क्रीनशॉट", "स्क्रीन शॉट", "اسکرین شاٹ", "سکرین شاٹ", "screenshot", "screen shot")
    private val phone = listOf("फोन", "फ़ोन", "मोबाइल", "स्क्रीन", "فون", "موبائل", "اسکرین", "phone", "mobile", "screen")
    private val lock = listOf("लॉक", "لاک", "lock")
    private val scroll = listOf("स्क्रॉल", "سکرول", "اسکرول", "scroll")
    private val home = listOf("होम", "ہوم", "home")
    private val back = listOf("वापस", "पीछे", "واپس", "پیچھے", "wapas", "wapis", "peeche")
    private val go = listOf("जाओ", "चलो", "جاؤ", "چلو", "jao", "chalo")
    private val sleep = listOf("सो जाओ", "चुप हो जाओ", "बंद हो जाओ", "سو جاؤ", "خاموش ہو جاؤ", "بند ہو جاؤ", "so jao", "chup ho jao", "band ho jao")
    private val hello = listOf("नमस्ते", "नमस्कार", "हेलो", "हैलो", "सलाम", "अस्सलाम", "السلام", "سلام", "ہیلو", "namaste", "salam", "assalam", "hello")
    private val goodMorning = listOf("सुप्रभात", "गुड मॉर्निंग", "صبح بخیر", "گڈ مارننگ", "subah bakhair", "good morning")
    private val whoAreYou = listOf("कौन हो", "कौन हैं", "تم کون ہو", "آپ کون ہیں", "کون ہو", "kaun ho", "kon ho")
    private val thanks = listOf("धन्यवाद", "शुक्रिया", "थैंक्स", "شکریہ", "تھینکس", "shukriya", "dhanyavad", "thanks")
    private val help = listOf("मदद", "हेल्प", "क्या कर सकते हो", "مدد", "ہیلپ", "کیا کر سکتے ہو", "madad", "help")
    private val newChat = listOf("नई बात", "नयी बात", "سب بھول جاؤ", "نئی بات", "सब भूल जाओ", "nayi baat", "nai baat", "sab bhool jao")
    private val recall = listOf("क्या याद है", "کیا یاد ہے", "kya yaad hai")
    private val notifications = listOf("नोटिफिकेशन", "नोटिफिकेशंस", "نوٹیفکیشن", "نوٹیفیکیشن", "notification", "notifications")
    private val messages = listOf("मैसेज", "मेसेज", "संदेश", "میسج", "میسیج", "مسیج", "پیغام", "message", "messages", "msg")
    private val readVerbs = listOf("पढ़ो", "पढ़ कर", "पढ़कर", "सुनाओ", "बताओ", "پڑھو", "پڑھ کر", "سناؤ", "بتاؤ", "padho", "parho", "sunao", "batao")
    private val showVerbs = listOf("खोलो", "दिखाओ", "کھولو", "دکھاؤ", "kholo", "dikhao")
    private val screenWords = listOf("स्क्रीन", "اسکرین", "سکرین", "screen")
    private val camera = listOf("कैमरा", "फोटो", "तस्वीर", "تصویر", "کیمرا", "کیمرہ", "فوٹو", "camera", "photo", "tasveer")
    private val selfie = listOf("सेल्फी", "سیلفی", "selfie")
    private val takeVerbs = listOf("खोलो", "खींचो", "लो", "کھولو", "کھینچو", "لو", "kholo", "khencho", "lo")
    private val silent = listOf("साइलेंट", "سائلنٹ", "خاموش موڈ", "silent")
    private val vibrate = listOf("वाइब्रेट", "وائبریٹ", "vibrate")
    private val song = listOf("गाना", "गाने", "म्यूज़िक", "म्यूजिक", "संगीत", "वीडियो", "گانا", "گانے", "میوزک", "موسیقی", "ویڈیو", "gaana", "gana", "gaane", "music", "song", "video")
    private val stopVerbs = listOf("रोको", "रोक दो", "बंद करो", "روکو", "روک دو", "بند کرو", "roko", "rok do", "band karo", "pause")
    private val playWords = listOf("चलाओ", "शुरू करो", "चला दो", "چلاؤ", "شروع کرو", "چلا دو", "chalao", "shuru karo", "chala do")
    private val next = listOf("अगला", "اگلا", "agla")
    private val previous = listOf("पिछला", "پچھلا", "pichla")
    private val alarmWords = listOf("अलार्म", "الارم", "الارم", "alarm", "alaram")
    private val wakeMeUp = listOf("उठा देना", "उठाना", "जगा देना", "जगाना", "اٹھا دینا", "اٹھانا", "جگا دینا", "جگانا", "utha dena", "uthana", "jaga dena", "jagana")
    private val timerWords = listOf("टाइमर", "ٹائمر", "timer")
    private val remindWords = "याद दिलाना|याद दिला देना|याद दिलाओ|याद दिला दो|یاد دلانا|یاد دلا دینا|یاد دلاؤ|یاد دلا دو|yaad dilana|yaad dila dena|yaad dilao|yaad dila do"
    private val rememberWords = "याद रखो|याद रखना|याद कर लो|یاد رکھو|یاد رکھنا|یاد کر لو|yaad rakho|yaad rakhna|yaad kar lo"
    private val that = "कि|के|که|کہ|کے|ki|ke"
    /** Only real "that" words split a reminder's task (کے / के also mean "of"). */
    private val thatTask = "कि|که|کہ|ki|keh"

    private val things = linkedMapOf(
        "bluetooth" to listOf("ब्लूटूथ", "بلوٹوتھ", "بلو ٹوتھ", "bluetooth"),
        "wifi" to listOf("वाईफाई", "वाई फाई", "वाईफ़ाई", "وائی فائی", "وائیفائی", "wifi", "wi-fi", "wi fi"),
        "hotspot" to listOf("हॉटस्पॉट", "ہاٹ سپاٹ", "ہاٹ اسپاٹ", "hotspot"),
        "airplane mode" to listOf("एयरप्लेन", "फ्लाइट मोड", "हवाई जहाज़ मोड", "ایئرپلین", "ایروپلین", "فلائٹ موڈ", "airplane", "flight mode"),
        "location" to listOf("लोकेशन", "जीपीएस", "لوکیشن", "جی پی ایس", "location", "gps"),
        "mobile data" to listOf("मोबाइल डेटा", "इंटरनेट", "موبائل ڈیٹا", "انٹرنیٹ", "mobile data", "internet"),
        "dark mode" to listOf("डार्क मोड", "ڈارک موڈ", "dark mode"),
        "do not disturb" to listOf("डू नॉट डिस्टर्ब", "ڈو ناٹ ڈسٹرب", "do not disturb"),
        "battery saver" to listOf("बैटरी सेवर", "بیٹری سیور", "battery saver")
    )

    private const val openVerbs = "खोलो|खोल दो|खोलिए|खोल|ओपन करो|ओपन कर दो|चालू करो|کھولو|کھول دو|کھولیں|کھول|اوپن کرو|اوپن کر دو|kholo|khol do|kholiye|khol|open karo|open kar do"
    private const val openFront = "ओपन|اوپن|open"
    private const val callVerbs = "(?:को |کو |ko )?(?:कॉल|फोन|फ़ोन|کال|فون|call|phone) (?:करो|कर दो|मिलाओ|लगाओ|کرو|کر دو|ملاؤ|لگاؤ|karo|kar do|milao|lagao)"
    private const val msgNoun = "मैसेज|मेसेज|संदेश|एसएमएस|میسج|میسیج|مسیج|پیغام|ایس ایم ایس|message|msg|sms"
    private const val sendVerbs = "करो|कर दो|भेजो|भेज दो|लिखो|सेंड करो|کرو|کر دو|بھیجو|بھیج دو|لکھو|سینڈ کرو|karo|kar do|bhejo|bhej do|likho|send karo|send kar do|karna|bhejna"
    /** Every way speech recognizers and people write "WhatsApp". */
    private const val whatsapp = "व्हाट्सएप|व्हाट्सऐप|वॉट्सऐप|वॉट्सएप|वाट्सएप|वाट्सऐप|व्हाट्सअप|वॉट्सअप|व्हाट्स ऐप|व्हाट्स एप|व्हाट्स अप|" +
        "واٹس ایپ|واٹس اپ|واٹس آپ|واٹساپ|واٹسایپ|وٹس ایپ|وٹس اپ|وٹساپ|واٹزایپ|واٹز ایپ|" +
        "whatsapp|whats app|what's app|whatsap|watsapp|watsap|wattsapp|whatapp|watsaap|wtsp"
    private const val to = "को|کو|ko"
    private const val youtube = "यूट्यूब|यू ट्यूब|یوٹیوب|یو ٹیوب|youtube"
    private const val onWord = "पर|पे|में|پر|پے|میں|par|pe|mein"
    private const val playVerbs = "चलाओ|लगाओ|बजाओ|चला दो|लगा दो|چلاؤ|لگاؤ|بجاؤ|چلا دو|لگا دو|chalao|lagao|bajao|chala do|laga do"
    private const val searchVerbs = "सर्च करो|खोजो|ढूंढो|ढूँढो|सर्च कर दो|سرچ کرو|تلاش کرو|ڈھونڈو|سرچ کر دو|search karo|khojo|dhundo|dhoondo|talash karo"
    private const val routeWords = "रास्ता|डायरेक्शन|راستہ|ڈائریکشن|rasta|raasta"
    private const val ofWord = "का|की|के|کا|کی|کے|ka|ki|ke"
    private const val showVerb = "दिखाओ|बताओ|دکھاؤ|بتاؤ|dikhao|batao"
    private const val takeMe = "ले चलो|ले जाओ|لے چلو|لے جاؤ|le chalo|le jao"

    // ---------------------------------------------------------------- numbers & time

    private val NUMBERS = mapOf(
        // Urdu
        "ایک" to 1.0, "دو" to 2.0, "تین" to 3.0, "چار" to 4.0, "پانچ" to 5.0, "چھ" to 6.0, "چھے" to 6.0, "سات" to 7.0, "آٹھ" to 8.0,
        "نو" to 9.0, "دس" to 10.0, "گیارہ" to 11.0, "بارہ" to 12.0, "تیرہ" to 13.0, "چودہ" to 14.0, "پندرہ" to 15.0, "سولہ" to 16.0,
        "سترہ" to 17.0, "اٹھارہ" to 18.0, "انیس" to 19.0, "بیس" to 20.0, "پچیس" to 25.0, "تیس" to 30.0, "پینتیس" to 35.0, "چالیس" to 40.0,
        "پینتالیس" to 45.0, "پچاس" to 50.0, "پچپن" to 55.0, "ساٹھ" to 60.0, "ستر" to 70.0, "اسی" to 80.0, "نوے" to 90.0, "سو" to 100.0,
        "آدھا" to 0.5, "آدھے" to 0.5, "ڈیڑھ" to 1.5, "ڈھائی" to 2.5,
        // Hindi
        "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0, "पांच" to 5.0, "पाँच" to 5.0, "छह" to 6.0, "छः" to 6.0, "छे" to 6.0, "सात" to 7.0,
        "आठ" to 8.0, "नौ" to 9.0, "दस" to 10.0, "ग्यारह" to 11.0, "बारह" to 12.0, "तेरह" to 13.0, "चौदह" to 14.0, "पंद्रह" to 15.0, "सोलह" to 16.0,
        "सत्रह" to 17.0, "अठारह" to 18.0, "उन्नीस" to 19.0, "बीस" to 20.0, "पच्चीस" to 25.0, "तीस" to 30.0, "पैंतीस" to 35.0, "चालीस" to 40.0,
        "पैंतालीस" to 45.0, "पचास" to 50.0, "पचपन" to 55.0, "साठ" to 60.0, "सत्तर" to 70.0, "अस्सी" to 80.0, "नब्बे" to 90.0, "सौ" to 100.0,
        "आधा" to 0.5, "आधे" to 0.5, "डेढ़" to 1.5, "ढाई" to 2.5,
        // Roman
        "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "char" to 4.0, "chaar" to 4.0, "paanch" to 5.0, "panch" to 5.0, "chhe" to 6.0, "chay" to 6.0,
        "saat" to 7.0, "aath" to 8.0, "nau" to 9.0, "das" to 10.0, "gyarah" to 11.0, "barah" to 12.0, "pandrah" to 15.0, "bees" to 20.0,
        "pachees" to 25.0, "tees" to 30.0, "chalees" to 40.0, "pachaas" to 50.0, "saath" to 60.0, "aadha" to 0.5, "dedh" to 1.5, "dhai" to 2.5
    )

    /** "5", "۵", "५", "پانچ", "पाँच", "paanch" -> 5.0 */
    fun number(token: String): Double? {
        val ascii = buildString {
            for (ch in token) append(
                when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '०'..'९' -> '0' + (ch - '०')
                    else -> ch
                }
            )
        }
        ascii.toDoubleOrNull()?.let { return it }
        return NUMBERS[nfc(token)] ?: NUMBERS.entries.firstOrNull { nfc(it.key) == nfc(token) }?.value
    }

    private const val MIN = "मिनट|منٹ|minat|mint|minute|minutes|min"
    private const val HOUR = "घंटे|घंटा|گھنٹے|گھنٹہ|گھنٹا|ghante|ghanta|hour|hours"
    private const val SEC = "सेकंड|سیکنڈ|second|seconds|sec"

    /** "10 منٹ", "ڈیڑھ گھنٹہ", "आधा घंटा" -> seconds */
    fun duration(t: String): Int? {
        val r = rx("(?:^| )(\\S+) ($MIN|$HOUR|$SEC)(?:$| )").find(nfc(t)) ?: return null
        val n = number(r.groupValues[1]) ?: return null
        val unit = r.groupValues[2]
        val seconds = when {
            rx(HOUR).matches(unit) -> n * 3600
            rx(MIN).matches(unit) -> n * 60
            else -> n
        }
        return seconds.toInt().takeIf { it > 0 }
    }

    private const val PART = "सुबह|सवेरे|दोपहर|शाम|रात|صبح|سویرے|دوپہر|شام|رات|subah|savere|dopahar|sham|shaam|raat"
    private const val MOD = "साढ़े|साढे|सवा|पौने|ساڑھے|سوا|پونے|sadhe|saade|sawa|paune"
    private const val OCLOCK = "बजे|بجے|baje|bje"

    /** "شام ساڑھے پانچ بجے", "सुबह 6:30 बजे", "raat 10 baje" -> (hour24, minute) */
    fun clock(t: String): Pair<Int, Int>? {
        val text = nfc(t)
        val r = rx("(?:($PART) (?:के |को |کے |کو |ke |ko )?)?(?:($MOD) )?(\\S+?)(?::(\\d{1,2}))? (?:$OCLOCK)").find(text) ?: return null
        val n = number(r.groupValues[3]) ?: return null
        var hour = n.toInt()
        var minute = r.groupValues[4].toIntOrNull() ?: ((n - hour) * 60).toInt()
        val mod = r.groupValues[2]
        when {
            oneOf(mod, "साढ़े", "साढे", "ساڑھے", "sadhe", "saade") -> minute = 30
            oneOf(mod, "सवा", "سوا", "sawa") -> minute = 15
            oneOf(mod, "पौने", "پونے", "paune") -> { hour -= 1; minute = 45 }
        }
        if (hour < 0) hour += 12
        val part = r.groupValues[1].ifEmpty { rx("(?:^| )($PART)(?:$| )").find(text)?.groupValues?.get(1).orEmpty() }
        hour = when {
            oneOf(part, "सुबह", "सवेरे", "صبح", "سویرے", "subah", "savere") -> if (hour == 12) 0 else hour
            oneOf(part, "दोपहर", "دوپہر", "dopahar") -> if (hour in 1..5) hour + 12 else hour
            oneOf(part, "शाम", "شام", "sham", "shaam") -> if (hour < 12) hour + 12 else hour
            oneOf(part, "रात", "رات", "raat") -> when {
                hour == 12 -> 0
                hour in 6..11 -> hour + 12
                else -> hour
            }
            else -> hour
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // ---------------------------------------------------------------- main entry

    /**
     * Only the "Ali ko … message karo" family, for Roman Urdu typed while Voxa thinks the user speaks English.
     * Needs "ko", which English never uses, so it can't hijack an English command.
     */
    fun messageToEnglish(input: String): String? = messageCommand(clean(input))

    private fun clean(input: String) = romanFix(
        nfc(input).lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}&&[^'&+:%-]]"), " ") // drop punctuation like । ؟ ، .
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    /** Returns an English command, or null if this isn't one of the known Urdu/Hindi commands. */
    fun toEnglish(input: String): String? {
        val t = clean(input)
        if (t.isEmpty()) return null
        fun has(words: List<String>) = words.any { containsWord(t, it) }
        fun m(p: String) = rx(p).matchEntire(t)
        fun words() = t.split(' ').size

        // ----- Free-text commands (names / messages / queries keep their script) -----
        messageCommand(t)?.let { return it }

        // Reminders, alarms, timers
        if (rx("(?:^| )(?:$remindWords)(?:$| )").containsMatchIn(t)) {
            val task = rx("(?:^| )(?:$thatTask) (.+)$").find(t)?.groupValues?.get(1)?.trim()
                ?.replace(rx("(?:^| )(?:$remindWords)$"), "")?.trim()?.ifEmpty { null } ?: "reminder"
            duration(t)?.let { return "remind me in $it seconds to $task" }
            clock(t)?.let { (h, mm) -> return "remind me at $h:${pad(mm)} to $task" }
        }
        if (has(alarmWords) || has(wakeMeUp)) {
            clock(t)?.let { (h, mm) -> return "set an alarm for $h:${pad(mm)}" }
        }
        if (has(timerWords)) {
            duration(t)?.let { return "set a timer for $it seconds" }
        }

        m("(?:$rememberWords)(?: (?:$that))? (.+)")?.let { return "remember ${it.groupValues[1]}" }
        m("(.+?) (?:$rememberWords)")?.let { return "remember ${it.groupValues[1]}" }
        m("(.+?) (?:$youtube) (?:$onWord) (?:$playVerbs|$searchVerbs)")?.let { return "play ${it.groupValues[1]} on youtube" }
        m("(?:$youtube) (?:$onWord) (.+?) (?:$playVerbs|$searchVerbs)")?.let { return "play ${it.groupValues[1]} on youtube" }
        m("(.+?) (?:$searchVerbs)")?.let { return "search for ${it.groupValues[1]}" }
        m("(.+?) (?:$ofWord) (?:$routeWords) (?:$showVerb)")?.let { return "navigate to ${it.groupValues[1]}" }
        m("(.+?) (?:$takeMe)")?.let { return "navigate to ${it.groupValues[1]}" }
        m("(.+?) (?:$callVerbs)")?.let { return "call ${it.groupValues[1]}" }

        // Songs: "عاطف اسلم کے گانے چلاؤ" -> YouTube; "گانا روکو" -> pause
        if (has(song)) {
            if (has(stopVerbs)) return "pause"
            if (has(next)) return "next song"
            if (has(previous)) return "previous song"
            m("(.+?) (?:के|की|का|کے|کی|کا|ke|ki|ka) (?:गाने|गाना|گانے|گانا|gaane|gaana|songs?) (?:$playVerbs)")
                ?.let { return "play ${it.groupValues[1]} songs on youtube" }
            if (has(playWords) || has(listOf("लगाओ", "بجاؤ", "لگاؤ", "बजाओ", "lagao", "bajao"))) return "play music"
        }
        if (has(next) && words() <= 3) return "next song"

        // ----- Fixed commands -----
        if (has(newChat)) return "new conversation"
        if (has(sleep)) return "stop listening"
        if (has(goodMorning)) return "good morning"
        if (has(whoAreYou)) return "who are you"
        if (has(recall)) return "what do you remember"
        if (has(thanks) && words() <= 4) return "thank you"
        if (has(torch)) return if (has(off)) "turn off the flashlight" else "turn on the flashlight"
        if (has(volume)) {
            rx("(\\S+) ?(?:$percent)").find(t)?.let { r -> number(r.groupValues[1])?.let { return "set volume to ${it.toInt()} percent" } }
            if (has(up)) return "volume up"
            if (has(down)) return "volume down"
            if (has(off) || has(mute)) return "mute"
        }
        if (has(brightness)) {
            rx("(\\S+) ?(?:$percent)").find(t)?.let { r -> number(r.groupValues[1])?.let { return "brightness ${it.toInt()} percent" } }
            if (has(up)) return "brighter"
            if (has(down)) return "dim the screen"
        }
        if (has(mute)) return "mute"
        for ((english, names) in things) {
            if (has(names)) {
                if (has(off)) return "turn off $english"
                if (has(on) || has(listOf("करो", "کرو", "karo", "कर दो", "کر دو"))) return "turn on $english"
            }
        }
        if (has(silent)) return "silent mode"
        if (has(vibrate)) return "vibrate mode"
        if (has(screenshot)) return "take a screenshot"
        if (has(lock) && has(phone)) return "lock the phone"
        if (has(scroll)) return if (has(listOf("ऊपर", "اوپر", "upar"))) "scroll up" else "scroll down"
        if (has(home) && (has(go) || words() <= 2)) return "go home"
        if (has(back) && has(go)) return "go back"
        if ((has(notifications) || has(messages)) && has(readVerbs)) return "read my notifications"
        if (has(notifications)) return "open notifications"
        if (has(screenWords) && has(what)) return "what's on my screen"
        if (has(screenWords) && has(readVerbs)) return "read the screen"
        if (has(selfie)) return "take a selfie"
        if (has(camera) && has(takeVerbs)) return "take a photo"
        if (has(time) && has(what)) return "what time is it"
        if (has(date) && (has(what) || has(today))) return "what's the date"
        if (has(battery) && (has(what) || words() <= 3)) return "battery level"
        if (has(help) && words() <= 5) return "help"
        if (words() <= 3 && has(hello)) return "hello"

        // ----- Open an app (name in any script; matched by sound later) -----
        m("(.+?) (?:$openVerbs)")?.let { return "open ${Translit.knownApp(it.groupValues[1]) ?: it.groupValues[1]}" }
        m("(?:$openFront) (.+?)(?: (?:$openVerbs))?")?.let { return "open ${Translit.knownApp(it.groupValues[1]) ?: it.groupValues[1]}" }
        return null
    }

    // ---------------------------------------------------------------- messages

    /** "whatsapp khol kar" / "واٹس ایپ کھول کر" / "व्हाट्सएप खोल कर" before the actual request. */
    private val openFirst =
        "(?:(?:$whatsapp)(?: (?:$to))? (?:open|khol|kholo|کھول|کھولو|اوپن|खोल|खोलो|ओपन)(?: (?:kar|karo|کر|کرو|कर|करो))?(?: (?:ke|kar|aur|کے|کر|اور|के|कर|और))? |" +
        "(?:$whatsapp) (?:$onWord) )?"

    /** "bol do ke …", "keh do ke …", "bata do ke …" — another way to give the message text. */
    private const val tellVerbs = "बोल दो|बोलो|कह दो|कहो|बता दो|بول دو|بولو|کہہ دو|کہو|بتا دو|bol do|bolo|keh do|kaho|kah do|bata do"

    /**
     * "Ali ko message karo ke main aa raha hoon", "WhatsApp khol kar Ali ko message karo",
     * "علی کو واٹس ایپ پر میسج کرو کہ ..." — WhatsApp if WhatsApp is mentioned, otherwise SMS.
     * Without a message text, Voxa opens the chat so you can type it yourself.
     */
    private fun messageCommand(t: String): String? {
        val r = rx(
            "$openFirst(.+?) (?:$to)(?: (?:$whatsapp))?(?: (?:$onWord))?(?: (?:$msgNoun))?(?: (?:$sendVerbs|$tellVerbs))?(?: (?:$that) (.+))?"
        ).matchEntire(t)
            // "Ali ko hello ka message bhejo" / "علی کو ہیلو کا میسج بھیجو" — the text comes before "ka message".
            ?: rx(
                "$openFirst(.+?) (?:$to)(?: (?:$whatsapp) (?:$onWord))? (.+?) (?:$ofWord)(?: (?:$whatsapp))? (?:$msgNoun) (?:$sendVerbs)"
            ).matchEntire(t)
            ?: return null
        val who = r.groupValues[1].trim()
        val body = r.groupValues[2].trim()
        val viaWhatsapp = rx("(?:^| )(?:$whatsapp)(?:$| )").containsMatchIn(t)
        val mentionsMessage = rx("(?:^| )(?:$msgNoun)(?:$| )").containsMatchIn(t)
        // Must actually be about messaging, and the name can't be a leftover verb.
        val tells = rx("(?:^| )(?:$tellVerbs) (?:$that) ").containsMatchIn(t)
        if (!viaWhatsapp && !mentionsMessage && !tells) return null
        if (!rx("(?:^| )(?:$sendVerbs)(?:$| )").containsMatchIn(t) && body.isEmpty() && !viaWhatsapp) return null
        if (who.isEmpty() || rx("(?:$whatsapp|$msgNoun)").matches(who)) return null
        return when {
            viaWhatsapp && body.isNotEmpty() -> "whatsapp $who saying $body"
            viaWhatsapp -> "open whatsapp chat with $who"
            body.isNotEmpty() -> "send a message to $who saying $body"
            else -> "compose a message to $who"
        }
    }

    // ---------------------------------------------------------------- Roman spelling

    private val WHATSAPP_ANYWHERE by lazy { rx("(?:^|[^\\p{L}\\p{M}])(?:$whatsapp)(?:$|[^\\p{L}\\p{M}])") }

    /** True if the text mentions WhatsApp in any script or spelling. */
    fun mentionsWhatsapp(text: String): Boolean = WHATSAPP_ANYWHERE.containsMatchIn(nfc(text).lowercase(Locale.ROOT))

    /** Common ways people type Roman Urdu/Hindi, mapped to one spelling so the patterns stay simple. */
    private val ROMAN = mapOf(
        "kro" to "karo", "kr" to "kar", "ker" to "kar", "krdo" to "kar do", "kardo" to "kar do", "krdein" to "kar do", "krna" to "karna",
        "k" to "ke", "kay" to "ke", "kh" to "keh", "py" to "par", "pr" to "par", "pay" to "par",
        "msg" to "message", "mesg" to "message", "massage" to "message", "msj" to "message", "mesage" to "message", "messege" to "message",
        "bhjo" to "bhejo", "bhejdo" to "bhej do", "bhjdo" to "bhej do", "bejho" to "bhejo", "bejo" to "bhejo", "bhijo" to "bhejo",
        "bhejho" to "bhejo", "bhaijo" to "bhejo", "bhejain" to "bhejo", "bhejen" to "bhejo", "bej" to "bhej", "bejdo" to "bhej do",
        "massege" to "message", "mesej" to "message", "mesij" to "message", "maseg" to "message",
        "boldo" to "bol do", "kehdo" to "keh do", "kahdo" to "keh do", "batado" to "bata do",
        "whatsap" to "whatsapp", "watsapp" to "whatsapp", "watsap" to "whatsapp", "whatapp" to "whatsapp", "wtsp" to "whatsapp", "watsaap" to "whatsapp",
        "mujy" to "mujhe", "mujhy" to "mujhe", "mjhe" to "mujhe", "mujhay" to "mujhe",
        "hy" to "hai", "hay" to "hai", "he" to "hai", "kon" to "kaun", "kaon" to "kaun", "kia" to "kya",
        "opn" to "open", "khool" to "khol", "kholdo" to "khol do"
    )

    private fun romanFix(t: String): String {
        if (!t.any { it in 'a'..'z' }) return t
        val joined = t.replace(Regex("\\bwhats app\\b"), "whatsapp")
        return joined.split(' ').joinToString(" ") { w ->
            // "he" is English too — only treat it as "hai" at the end of a Roman Urdu sentence.
            if (w == "he") w else ROMAN[w] ?: w
        }.let { if (it.endsWith(" he")) it.removeSuffix(" he") + " hai" else it }
    }

    private val QUESTION = Regex(
        "(?:^| )(?:kaun|kya|kahan|kab|kaise|kaisa|kaisi|kyun|kyon|kitna|kitni|kitne|کون|کیا|کہاں|کب|کیسے|کیسا|کیوں|کتنا|کتنی|کتنے|कौन|क्या|कहाँ|कहां|कब|कैसे|कैसा|क्यों|कितना|कितनी|कितने)(?: |$)"
    )
    private val TELL = Regex("(?:^| )(?:batao|bataiye|bata do|samjhao|بتاؤ|بتائیے|بتا دو|سمجھاؤ|बताओ|बताइए|बता दो|समझाओ)(?:$| )")

    /** A question or "tell me about…" in Urdu/Hindi (any script) — something to answer, not a phone command. */
    fun isQuestion(input: String): Boolean {
        val t = romanFix(nfc(input).lowercase(Locale.ROOT).replace(Regex("[\\p{P}&&[^'-]]"), " ").replace(Regex("\\s+"), " ").trim())
        return input.trim().endsWith("?") || input.trim().endsWith("؟") || QUESTION.containsMatchIn(t) || TELL.containsMatchIn(t)
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Regex with its literal words in the same Unicode form as the (normalized) input. */
    private fun rx(pattern: String) = Regex(nfc(pattern))

    private fun oneOf(value: String, vararg options: String) = options.any { nfc(it) == nfc(value) }

    /** Whole-word match that also works for Devanagari / Urdu (Java's \b is ASCII-only). */
    private fun containsWord(rawText: String, rawWord: String): Boolean {
        // Same letter can arrive precomposed (ज़) or as letter + nukta (ज़); compare one form.
        val text = nfc(rawText)
        val word = nfc(rawWord)
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
