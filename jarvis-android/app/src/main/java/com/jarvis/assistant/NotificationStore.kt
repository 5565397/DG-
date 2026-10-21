package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Every notification the phone received (WhatsApp, Gmail, YouTube, Studio, Facebook, …) for the
 * last few days, kept only on this phone, so Voxa can tell you what came in and summarise it.
 */
object NotificationStore {

    data class Item(val pkg: String, val app: String, val title: String, val text: String, val time: Long, val read: Boolean = false)

    private const val FILE = "notifications.json"
    private const val MAX = 800
    private const val KEEP_MS = 4 * 24 * 3600_000L

    /** Spoken app names (English, Urdu, Hindi, common misspellings) -> package name prefixes. */
    private val APPS: List<Pair<Regex, List<String>>> = listOf(
        "youtube studio|yt studio|studio|یوٹیوب سٹوڈیو|स्टूडियो" to listOf("com.google.android.apps.youtube.creator"),
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "whats ?app|watsapp|واٹس ایپ|واٹس اپ|व्हाट्सएप|व्हाट्सअप" to listOf("com.whatsapp"),
        "gmail|g mail|e-?mails?|mail|جی میل|ای میل|जीमेल|ईमेल" to listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email"),
        "youtube|you tube|یوٹیوب|यूट्यूब" to listOf("com.google.android.youtube"),
        "facebook|fb|فیس بک|फेसबुक" to listOf("com.facebook.katana", "com.facebook.lite"),
        "messenger|میسنجر|मैसेंजर" to listOf("com.facebook.orca", "com.facebook.mlite"),
        "instagram|insta|انسٹاگرام|इंस्टाग्राम" to listOf("com.instagram.android"),
        "calendar|کیلنڈر|कैलेंडर" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
        "tiktok|tik tok|ٹک ٹاک|टिकटॉक" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "telegram|ٹیلیگرام|टेलीग्राम" to listOf("org.telegram.messenger"),
        "snapchat|snap|سنیپ چیٹ|स्नैपचैट" to listOf("com.snapchat.android"),
        "twitter|x app|ٹویٹر|ट्विटर" to listOf("com.twitter.android"),
        "linkedin|لنکڈ ان|लिंक्डइन" to listOf("com.linkedin.android"),
        "sms|text messages|ایس ایم ایس|एसएमएस" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
        "drive|google drive" to listOf("com.google.android.apps.docs"),
        "bank|banking|بینک|बैंक" to listOf("bank", "com.hbl", "com.meezan", "pk.com.telenor", "com.easypaisa", "com.techlogix.mobilinkcustomer")
    ).map { (words, pkgs) -> Regex("(?:^|[^\\p{L}])(?:$words)(?:$|[^\\p{L}])", RegexOption.IGNORE_CASE) to pkgs }

    private val items = ArrayList<Item>()
    private var loaded = false

    @Synchronized
    fun add(context: Context, item: Item) {
        load(context)
        // Chat apps update the same notification as messages arrive; keep the newest text once.
        items.removeAll { it.pkg == item.pkg && it.title == item.title && it.text == item.text }
        items.add(item)
        prune()
        save(context)
    }

    /** Package prefixes for an app named in [spoken], or null if no known app is named. */
    fun packagesFor(spoken: String): List<String>? = APPS.firstOrNull { it.first.containsMatchIn(spoken.lowercase(Locale.ROOT)) }?.second

    /** A display name for the app named in [spoken] (e.g. "gmail" -> "Gmail"). */
    fun appNameFor(spoken: String): String? {
        val pkgs = packagesFor(spoken) ?: return null
        return when (pkgs.first()) {
            "com.google.android.apps.youtube.creator" -> "YouTube Studio"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "com.whatsapp" -> "WhatsApp"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.youtube" -> "YouTube"
            "com.facebook.katana" -> "Facebook"
            "com.facebook.orca" -> "Messenger"
            "com.instagram.android" -> "Instagram"
            "com.google.android.calendar" -> "Calendar"
            "com.google.android.apps.messaging" -> "Messages"
            else -> null
        }
    }

    /** Newest first. [packages] limits it to some apps; [sinceMs] to a time; [unreadOnly] to ones Voxa hasn't read out. */
    @Synchronized
    fun query(context: Context, packages: List<String>? = null, sinceMs: Long = 0L, unreadOnly: Boolean = false, limit: Int = 60): List<Item> {
        load(context)
        prune()
        return items.asReversed().asSequence()
            .filter { it.time >= sinceMs }
            .filter { !unreadOnly || !it.read }
            .filter { packages == null || packages.any { p -> it.pkg.startsWith(p) || (p == "bank" && it.pkg.contains("bank")) } }
            .take(limit)
            .toList()
    }

    @Synchronized
    fun markRead(context: Context, read: List<Item>) {
        load(context)
        val keys = read.map { Triple(it.pkg, it.time, it.title) }.toSet()
        for (i in items.indices) {
            val it = items[i]
            if (Triple(it.pkg, it.time, it.title) in keys) items[i] = it.copy(read = true)
        }
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        items.clear()
        loaded = true
        save(context)
    }

    /** Plain lines for the AI or the chat: "10:42 · WhatsApp · Asad: Keys mili ya ni". */
    fun format(list: List<Item>, maxChars: Int = 9000): String {
        val sb = StringBuilder()
        val fmt = java.text.SimpleDateFormat("EEE h:mm a", Locale.US)
        for (n in list) {
            val line = "${fmt.format(java.util.Date(n.time))} · ${n.app}" +
                (if (n.title.isNotBlank()) " · ${n.title}" else "") + ": " + n.text.replace('\n', ' ').take(300)
            if (sb.length + line.length > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        items.removeAll { it.time < cutoff }
        while (items.size > MAX) items.removeAt(0)
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(Item(o.optString("p"), o.optString("a"), o.optString("ti"), o.optString("tx"), o.optLong("at"), o.optBoolean("r")))
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (n in items) {
                arr.put(JSONObject().put("p", n.pkg).put("a", n.app).put("ti", n.title).put("tx", n.text).put("at", n.time).put("r", n.read))
            }
            File(context.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
        }
    }
}
