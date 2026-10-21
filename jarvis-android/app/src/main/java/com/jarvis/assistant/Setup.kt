package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** The one-time steps that give Voxa its powers, with a live "done" check for each. */
object Setup {

    enum class Step(val title: String, val subtitle: String) {
        PERMISSIONS("Microphone, contacts, calls, SMS & calendar", "So Voxa can hear you, reach people and read your schedule"),
        PHONE_CONTROL("Phone control", "Tap, scroll, type, screenshots, screen vision"),
        NOTIFICATIONS("Notification access", "Read and announce your messages"),
        OVERLAY("Display over other apps", "Voxa's window over apps and the lock screen"),
        BRIGHTNESS("Modify system settings", "Brightness control"),
        BACKGROUND("Run in the background", "Keeps the wake word alive"),
        ASSISTANT("Default assistant", "Power / home long-press and the smart key open Voxa, even when locked"),
        LOCK_WINDOW("Show on the lock screen", "Lets Voxa's window appear over the lock screen")
    }

    fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isDone(context: Context, step: Step): Boolean = when (step) {
        Step.PERMISSIONS -> runtimePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        Step.PHONE_CONTROL -> JarvisAccessibilityService.instance != null || accessibilityEnabled(context)
        Step.NOTIFICATIONS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        Step.OVERLAY -> Settings.canDrawOverlays(context)
        Step.BRIGHTNESS -> Settings.System.canWrite(context)
        Step.BACKGROUND -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        Step.ASSISTANT -> Recognizers.isDefaultAssistant(context)
        Step.LOCK_WINDOW -> Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun doneCount(context: Context) = Step.values().count { isDone(context, it) }

    /** Opens the system page for a step. PERMISSIONS is handled by the caller's permission launcher. */
    fun open(activity: Activity, step: Step) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val intent = when (step) {
            Step.PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Step.PHONE_CONTROL -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Step.NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            Step.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Step.BRIGHTNESS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            Step.BACKGROUND -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Step.ASSISTANT -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            Step.LOCK_WINDOW -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Some phones keep "Default apps" elsewhere.
            try {
                activity.startActivity(Intent(if (step == Step.ASSISTANT) Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
