package com.jarvis.assistant

import android.content.Context

/** Small wrapper around SharedPreferences for every Jarvis setting. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) = sp.edit().putBoolean("lock_enabled", v).apply()

    /** Minimum similarity (0..1) a voice needs to unlock Jarvis. */
    var threshold: Float
        get() = sp.getFloat("threshold", 0.80f)
        set(v) = sp.edit().putFloat("threshold", v).apply()

    var unlockMinutes: Int
        get() = sp.getInt("unlock_minutes", 5)
        set(v) = sp.edit().putInt("unlock_minutes", v).apply()

    var language: String
        get() = sp.getString("language", "en-US") ?: "en-US"
        set(v) = sp.edit().putString("language", v).apply()

    var title: String
        get() = sp.getString("title", "sir") ?: "sir"
        set(v) = sp.edit().putString("title", v).apply()

    var countryCode: String
        get() = sp.getString("country_code", "") ?: ""
        set(v) = sp.edit().putString("country_code", v).apply()

    /** Enrolled voice samples, stored as "a,b,c;d,e,f". */
    var voiceprints: List<FloatArray>
        get() {
            val raw = sp.getString("voiceprints", "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split(";").mapNotNull { sample ->
                val values = sample.split(",").mapNotNull { it.toFloatOrNull() }
                if (values.isEmpty()) null else values.toFloatArray()
            }
        }
        set(v) = sp.edit().putString("voiceprints", v.joinToString(";") { it.joinToString(",") }).apply()

    fun hasVoiceprint() = voiceprints.isNotEmpty()
}
