package com.jarvis.assistant

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets Jarvis read your notifications ("any new messages?") and, if you switch it on,
 * announce incoming chat messages out loud. Only keeps the latest few in memory.
 */
class JarvisNotificationListener : NotificationListenerService() {

    data class Item(val key: String, val app: String, val title: String, val text: String, val time: Long)

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set

        private val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "org.telegram.messenger", "com.facebook.orca", "com.instagram.android", "com.snapchat.android",
            "com.android.mms", "com.google.android.gm", "com.microsoft.teams", "com.Slack", "com.discord", "com.viber.voip",
            "com.imo.android.imoim", "jp.naver.line.android", "com.skype.raider"
        )
    }

    private val items = ArrayList<Item>()
    private var speaker: Speaker? = null

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val item = Item(sbn.key, appLabel(sbn.packageName), title, text, sbn.postTime)
        synchronized(items) {
            items.removeAll { it.key == item.key }
            items.add(item)
            while (items.size > 30) items.removeAt(0)
        }
        if (sbn.packageName in CHAT_APPS && Prefs(this).announceMessages) {
            val s = speaker ?: Speaker(this).also { speaker = it }
            val who = if (title.isNotBlank()) "from $title" else ""
            s.speak("New ${item.app} message $who. ${text.take(120)}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(items) { items.removeAll { it.key == sbn.key } }
    }

    /** Newest first, from the last 12 hours. */
    fun recent(limit: Int): List<Item> {
        val cutoff = System.currentTimeMillis() - 12 * 3600_000L
        return synchronized(items) { items.filter { it.time >= cutoff }.sortedByDescending { it.time }.take(limit) }
    }

    fun markAllRead() = synchronized(items) { items.clear() }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
