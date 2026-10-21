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
        get() = sp.getString("language", Lang.AUTO) ?: Lang.AUTO
        set(v) = sp.edit().putString("language", v).apply()

    var title: String
        get() = sp.getString("title", "sir") ?: "sir"
        set(v) = sp.edit().putString("title", v).apply()

    /** Voxa understands Urdu/Hindi/English, but answers in Hinglish (Roman letters) when this is on. */
    var replyHinglish: Boolean
        get() = sp.getBoolean("reply_hinglish", true)
        set(v) = sp.edit().putBoolean("reply_hinglish", v).apply()

    var countryCode: String
        get() = sp.getString("country_code", "") ?: ""
        set(v) = sp.edit().putString("country_code", v).apply()

    /** Claude API key for the AI brain (console.anthropic.com). */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    /** Groq API key (console.groq.com). Stored only on this phone. */
    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v).apply()

    /** Groq model the user picked; blank = choose automatically. */
    var groqModel: String
        get() = sp.getString("groq_model", "") ?: ""
        set(v) = sp.edit().putString("groq_model", v).apply()

    var groqModels: List<String>
        get() = (sp.getString("groq_models", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("groq_models", v.joinToString(",")).apply()

    var groqModelsAt: Long
        get() = sp.getLong("groq_models_at", 0L)
        set(v) = sp.edit().putLong("groq_models_at", v).apply()

    /**
     * Where questions go: "offline" (no AI, local commands only), "groq" or "claude".
     * Existing users with a Claude key keep Claude until they switch.
     */
    var aiEngine: String
        get() = sp.getString("ai_engine", null) ?: if (apiKey.isNotBlank()) ENGINE_CLAUDE else ENGINE_OFFLINE
        set(v) = sp.edit().putString("ai_engine", v).apply()

    /** True when an online AI is switched on and has a key. */
    val aiActive: Boolean
        get() = when (aiEngine) {
            ENGINE_GROQ -> groqKey.isNotBlank()
            ENGINE_CLAUDE -> apiKey.isNotBlank()
            else -> false
        }

    /** Optional home city, for weather and local answers. */
    var city: String
        get() = sp.getString("city", "") ?: ""
        set(v) = sp.edit().putString("city", v).apply()

    /** Speak incoming chat messages out loud. */
    var announceMessages: Boolean
        get() = sp.getBoolean("announce_messages", false)
        set(v) = sp.edit().putBoolean("announce_messages", v).apply()

    /** Things the user asked Jarvis to remember. */
    var memories: List<String>
        get() = (sp.getString("memories", "") ?: "").split(SEP).filter { it.isNotBlank() }
        set(v) = sp.edit().putString("memories", v.joinToString(SEP)).apply()

    fun addMemory(fact: String) {
        val clean = fact.trim().replaceFirstChar { it.uppercase() }
        if (clean.isNotEmpty()) memories = (memories + clean).takeLast(50)
    }

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

    companion object {
        private const val SEP = "\u001F"
        const val ENGINE_OFFLINE = "offline"
        const val ENGINE_GROQ = "groq"
        const val ENGINE_CLAUDE = "claude"
    }
}
