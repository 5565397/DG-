package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The conversation log shown in the chat: what you said or typed, what Voxa heard,
 * did, saw and answered. Every conversation is saved on the phone as it happens
 * (files/chats/<id>.json), so old chats stay in History. A new chat starts when you
 * ask for one, or by itself after a few quiet hours.
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

    /** One saved conversation, for the History list. */
    data class Session(val id: Long, val title: String, val start: Long, val end: Long, val count: Int)

    private const val DIR = "chats"
    private const val CURRENT = "current.txt"
    private const val OLD_FILE = "chat.json"
    private const val MAX = 400
    private const val MAX_SESSIONS = 300
    /** A message after this long a silence starts a new conversation. */
    private const val NEW_SESSION_AFTER_MS = 4 * 3600_000L

    private val messages = ArrayList<Message>()
    private var currentId = 0L
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
        val now = System.currentTimeMillis()
        val last = messages.lastOrNull()
        if (last != null && now - last.time > NEW_SESSION_AFTER_MS) {
            startNew(context)
            JarvisBus.chatCleared()
        }
        val msg = Message(role, clean, now, typed, file?.path, file?.name, file?.mime)
        messages.add(msg)
        while (messages.size > MAX) messages.removeAt(0)
        save(context)
        JarvisBus.chat(msg)
    }

    /** Starts a fresh conversation; the current one stays in History. */
    @Synchronized
    fun newSession(context: Context) {
        load(context)
        if (messages.isEmpty()) return
        startNew(context)
        JarvisBus.chatCleared()
    }

    /** Deletes every saved conversation and attachment. */
    @Synchronized
    fun clear(context: Context) {
        messages.clear()
        dir(context).listFiles()?.forEach { it.delete() }
        File(context.filesDir, "attachments").listFiles()?.forEach { it.delete() }
        loaded = true
        startNew(context)
        JarvisBus.chatCleared()
    }

    /** Saved conversations, newest first (the current one included if it has messages). */
    @Synchronized
    fun sessions(context: Context): List<Session> {
        load(context)
        return dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
            val list = read(f)
            if (list.isEmpty()) return@mapNotNull null
            val title = list.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.text
                ?: list.firstOrNull { it.role == Role.USER }?.fileName ?: list.first().text
            Session(id, title.take(80), list.first().time, list.last().time, list.size)
        }.sortedByDescending { it.end }
    }

    @Synchronized
    fun session(context: Context, id: Long): List<Message> {
        load(context)
        if (id == currentId) return ArrayList(messages)
        return read(File(dir(context), "$id.json"))
    }

    @Synchronized
    fun isCurrent(id: Long) = id == currentId

    @Synchronized
    fun deleteSession(context: Context, id: Long) {
        load(context)
        val list = session(context, id)
        File(dir(context), "$id.json").delete()
        // Pictures and files that belonged only to this chat.
        list.mapNotNull { it.filePath }.forEach { File(it).delete() }
        if (id == currentId) {
            messages.clear()
            startNew(context)
            JarvisBus.chatCleared()
        }
    }

    /** Makes an old conversation the current one again, to continue it. */
    @Synchronized
    fun resume(context: Context, id: Long) {
        load(context)
        if (id == currentId) return
        val list = read(File(dir(context), "$id.json"))
        if (list.isEmpty()) return
        if (messages.isEmpty()) File(dir(context), "$currentId.json").delete()
        messages.clear()
        messages.addAll(list)
        currentId = id
        File(context.filesDir, CURRENT).writeText(id.toString())
        JarvisBus.chatCleared()
    }

    // ---------------------------------------------------------------- storage

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun startNew(context: Context) {
        messages.clear()
        currentId = System.currentTimeMillis()
        File(context.filesDir, CURRENT).writeText(currentId.toString())
        trimSessions(context)
    }

    private fun trimSessions(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }
        files.drop(MAX_SESSIONS).forEach { it.delete() }
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val old = File(context.filesDir, OLD_FILE)
            if (old.exists()) {
                // Chats from before History existed become the first saved conversation.
                val list = parse(JSONArray(old.readText()))
                if (list.isNotEmpty()) write(File(dir(context), "${list.first().time}.json"), list)
                old.delete()
            }
            currentId = File(context.filesDir, CURRENT).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            if (currentId == 0L) {
                currentId = System.currentTimeMillis()
                File(context.filesDir, CURRENT).writeText(currentId.toString())
            }
            messages.addAll(read(File(dir(context), "$currentId.json")))
        } catch (e: Exception) {
            messages.clear()
        }
    }

    private fun read(file: File): List<Message> = try {
        if (file.exists()) parse(JSONArray(file.readText())) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun parse(arr: JSONArray): List<Message> {
        val out = ArrayList<Message>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = try { Role.valueOf(o.optString("r")) } catch (e: Exception) { continue }
            out.add(
                Message(
                    role, o.optString("t"), o.optLong("at"), o.optBoolean("ty"),
                    o.optString("fp").ifBlank { null }, o.optString("fn").ifBlank { null }, o.optString("fm").ifBlank { null }
                )
            )
        }
        return out
    }

    private fun save(context: Context) = write(File(dir(context), "$currentId.json"), messages)

    private fun write(file: File, list: List<Message>) {
        try {
            val arr = JSONArray()
            for (m in list) {
                val o = JSONObject().put("r", m.role.name).put("t", m.text).put("at", m.time).put("ty", m.typed)
                if (m.filePath != null) o.put("fp", m.filePath).put("fn", m.fileName.orEmpty()).put("fm", m.fileMime.orEmpty())
                arr.put(o)
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
        }
    }
}
