package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Turns the "Voxa" wake word back on after the phone restarts or the app updates, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.wakeEnabled || !Setup.hasMic(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_START_WAKE))
        } catch (_: Exception) {
        }
    }
}
