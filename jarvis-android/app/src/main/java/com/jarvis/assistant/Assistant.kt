package com.jarvis.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Lets Voxa be the phone's default digital assistant, so holding the power / home button or
 * pressing a "smart" / assistant key opens Voxa — on the lock screen too, like Google Assistant.
 */
class VoxaInteractionService : VoiceInteractionService()

class VoxaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VoxaSession(this)
}

/** Opens Voxa's window and starts listening straight away. */
class VoxaSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, VoxaWindowActivity::class.java).putExtra(VoxaWindowActivity.EXTRA_LISTEN, true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        hide()
    }
}

/**
 * Android requires an assistant to name a speech recognizer. Voxa itself always uses Google's
 * (see [Recognizers]), so this one only answers anyone else with "not available".
 */
class VoxaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

object Recognizers {
    /**
     * Google's speech recognizer, named explicitly: once Voxa is the default assistant, the phone's
     * "default recognizer" points at Voxa's placeholder above and would hear nothing.
     */
    fun create(context: Context): SpeechRecognizer {
        // The phone's own default recognizer is the most reliable — use it unless it is Voxa's placeholder.
        val default = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let { ComponentName.unflattenFromString(it) }
        if (default != null && default.packageName != context.packageName) return SpeechRecognizer.createSpeechRecognizer(context)
        val google = best(context)
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(context, google) else SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun available(context: Context) = best(context) != null || SpeechRecognizer.isRecognitionAvailable(context)

    private fun best(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return services.firstOrNull { it.className.endsWith("GoogleRecognitionService") }
            ?: services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") }
            ?: services.firstOrNull()
    }

    /** True if Voxa is the phone's default digital assistant. */
    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val current = Settings.Secure.getString(context.contentResolver, "assistant") ?: return false
        return current.startsWith(context.packageName + "/")
    }
}
