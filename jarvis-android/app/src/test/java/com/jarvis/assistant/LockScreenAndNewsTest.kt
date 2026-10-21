package com.jarvis.assistant

import com.jarvis.assistant.CommandParser.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sleep / switch-off, "what came in on <app>", notification summaries and the calendar. */
class LockScreenAndNewsTest {

    private fun en(text: String) = CommandParser.parse(text)
    private fun ur(text: String) = CommandParser.parse(text, nonEnglishMode = true)

    private fun newsFrom(action: Action): List<String>? {
        assertTrue("expected AppNews, got $action", action is Action.AppNews)
        return (action as Action.AppNews).app?.let { NotificationStore.packagesFor(it) }
    }

    @Test fun sleepAndSwitchOff() {
        assertEquals(Action.StopListening, en("go to sleep"))
        assertEquals(Action.StopListening, ur("so jao"))
        assertEquals(Action.StopListening, ur("سو جاؤ"))
        assertEquals(Action.StopListening, ur("bas karo"))
        assertEquals(Action.TurnOff, en("go offline"))
        assertEquals(Action.TurnOff, en("turn off"))
        assertEquals(Action.TurnOff, ur("band karo"))
        assertEquals(Action.TurnOff, ur("بند کر دو"))
    }

    @Test fun newsFromOneApp() {
        assertEquals(listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email"), newsFrom(en("any new emails")))
        assertEquals(listOf("com.whatsapp"), newsFrom(en("read my whatsapp messages")))
        assertEquals(listOf("com.google.android.apps.youtube.creator"), newsFrom(en("what's new on youtube studio")))
        assertEquals(listOf("com.google.android.youtube"), newsFrom(en("check my youtube notifications")))
        assertEquals(listOf("com.facebook.katana", "com.facebook.lite"), newsFrom(en("any facebook notifications")))
    }

    @Test fun newsInUrduAndHindi() {
        assertEquals(listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email"), newsFrom(ur("gmail mein kya aaya")))
        assertEquals(listOf("com.whatsapp"), newsFrom(ur("whatsapp ke messages sunao")))
        assertEquals(listOf("com.whatsapp"), newsFrom(ur("واٹس ایپ کے میسج سناؤ")))
        assertEquals(listOf("com.google.android.youtube"), newsFrom(ur("youtube ki notifications batao")))
        assertEquals(listOf("com.google.android.youtube"), newsFrom(ur("यूट्यूब पर क्या आया")))
    }

    @Test fun summaries() {
        assertEquals(Action.AppNews(null, true), en("summarize my notifications"))
        assertEquals(Action.AppNews(null, true), ur("aaj kya kya aaya"))
        assertEquals(Action.AppNews(null, true), ur("notifications ka khulasa batao"))
    }

    @Test fun plainNotificationsStayTheSame() {
        assertEquals(Action.ReadNotifications, en("read my notifications"))
        assertEquals(Action.ReadNotifications, en("any new messages?"))
        assertEquals(Action.ReadNotifications, ur("notifications parho"))
    }

    @Test fun calendar() {
        assertEquals(Action.Schedule(0), en("what's on my calendar"))
        assertEquals(Action.Schedule(1), en("do i have any meetings tomorrow"))
        assertEquals(Action.Schedule(0), en("my schedule today"))
        assertEquals(Action.Schedule(0), ur("aaj ka schedule batao"))
        assertEquals(Action.Schedule(1), ur("kal ki meetings batao"))
        assertEquals(Action.Schedule(1), ur("کل کا شیڈول بتاؤ"))
    }

    @Test fun notMistakenForNews() {
        assertTrue(en("open calendar") !is Action.Schedule)
        assertTrue(en("remind me to check my email in 10 minutes") !is Action.AppNews)
        assertTrue(en("remind me about the meeting in 10 minutes") !is Action.Schedule)
        assertTrue(en("send a message to ali saying any updates") !is Action.AppNews)
        assertTrue(en("write an email to my boss about leave") !is Action.AppNews)
        assertNull(NotificationStore.packagesFor("hello there"))
    }
}
