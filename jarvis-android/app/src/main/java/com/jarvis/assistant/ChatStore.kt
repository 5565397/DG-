package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The conversation log shown in the chat: what you said or typed, what Voxa heard,
 * did, saw and answered. Kept on the phone (last 300 entries) so it survives restarts.
 */
object ChatStore {

    enum class Role { USER, VOXA, NOTE }

    data class Message(
        val role: Role,
        val text: String,
        val time: Long,
        val typed: Boolean = false,
        /** An attached file (path in app storage), its original name and type. */
        val filePath: String? = null,
        val fileName: String? = null,
        val fileMime: String? = null
    )

    private const val FILE = "chat.json"
    private const val MAX = 300

    private val messages = ArrayList<Message>()
    private var loaded = false

    @Synchronized
    fun all(context: Context): List<Message> {
        load(context)
        return ArrayList(messages)
    }

    @Synchronized
    fun add(context: Context, role: Role, text: String, typed: Boolean = false, file: FileAnalyzer.Attachment? = null) {
        val clean = text.trim()
        if (clean.isEmpty() && file == null) return
        load(context)
        val msg = Message(role, clean, System.currentTimeMillis(), typed, file?.path, file?.name, file?.mime)
        messages.add(msg)
        while (messages.size > MAX) messages.removeAt(0)
        save(context)
        JarvisBus.chat(msg)
    }

    @Synchronized
    fun clear(context: Context) {
        messages.clear()
        // Attached files live only for the chat; remove them too.
        java.io.File(context.filesDir, "attachments").listFiles()?.forEach { it.delete() }
        loaded = true
        save(context)
        JarvisBus.chatCleared()
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = try { Role.valueOf(o.optString("r")) } catch (e: Exception) { continue }
                messages.add(
                    Message(
                        role, o.optString("t"), o.optLong("at"), o.optBoolean("ty"),
                        o.optString("fp").ifBlank { null }, o.optString("fn").ifBlank { null }, o.optString("fm").ifBlank { null }
                    )
                )
            }
        } catch (e: Exception) {
            messages.clear()
        }
    }

    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (m in messages) {
                val o = JSONObject().put("r", m.role.name).put("t", m.text).put("at", m.time).put("ty", m.typed)
                if (m.filePath != null) o.put("fp", m.filePath).put("fn", m.fileName.orEmpty()).put("fm", m.fileMime.orEmpty())
                arr.put(o)
            }
            File(context.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
        }
    }
}
