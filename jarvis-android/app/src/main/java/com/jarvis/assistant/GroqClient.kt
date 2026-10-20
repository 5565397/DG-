package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Groq's OpenAI-compatible chat API. Picks the best model the account can use
 * (Groq retires models from time to time, so the list is fetched instead of hard-coded),
 * remembers the choice, and switches automatically if that model disappears.
 */
class GroqClient(private val prefs: Prefs) {

    class GroqException(val code: Int, message: String) : IOException(message)

    companion object {
        private const val BASE = "https://api.groq.com/openai/v1"
        private const val MODEL_CACHE_MS = 24 * 3600_000L

        /** Best general chat models first. */
        private val CHAT_PREFERENCE = listOf(
            "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct", "qwen/qwen3-32b", "openai/gpt-oss-20b",
            "meta-llama/llama-4-scout-17b-16e-instruct", "llama-3.1-8b-instant"
        )
        private val VISION_PREFERENCE = listOf(
            "meta-llama/llama-4-maverick-17b-128e-instruct", "meta-llama/llama-4-scout-17b-16e-instruct"
        )
        private val WEB_PREFERENCE = listOf("groq/compound", "groq/compound-mini")
        private val NOT_CHAT = Regex("whisper|tts|guard|playai|orpheus|distil|embed|prompt-guard|safeguard", RegexOption.IGNORE_CASE)
    }

    data class Msg(val role: String, val text: String)

    /** Sends a chat and returns the reply text. [imageJpeg] makes it a vision request; [needsWeb] prefers a web-search model. */
    fun chat(system: String, history: List<Msg>, question: String, imageJpeg: String?, needsWeb: Boolean): String {
        val model = when {
            imageJpeg != null -> pickModel(VISION_PREFERENCE, vision = true) ?: pickModel(CHAT_PREFERENCE)
            needsWeb -> pickModel(WEB_PREFERENCE, exact = true) ?: pickModel(CHAT_PREFERENCE)
            else -> pickModel(CHAT_PREFERENCE)
        } ?: throw GroqException(404, "No Groq chat model available")
        val canSeeImage = imageJpeg != null && model in VISION_PREFERENCE
        return try {
            send(model, system, history, question, if (canSeeImage) imageJpeg else null)
        } catch (e: GroqException) {
            // Model retired or not allowed: refresh the list once and try the next best one.
            if (e.code == 404 || (e.code == 400 && e.message.orEmpty().contains("model", ignoreCase = true))) {
                prefs.groqModels = emptyList()
                val retry = pickModel(CHAT_PREFERENCE) ?: throw e
                send(retry, system, history, question, null)
            } else {
                throw e
            }
        }
    }

    /** All chat-capable models on this account (cached for a day). */
    fun models(forceRefresh: Boolean = false): List<String> {
        val cached = prefs.groqModels
        if (!forceRefresh && cached.isNotEmpty() && System.currentTimeMillis() - prefs.groqModelsAt < MODEL_CACHE_MS) return cached
        val body = request("GET", "$BASE/models", null)
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
            .filter { it.isNotBlank() && !NOT_CHAT.containsMatchIn(it) }
            .sorted()
        prefs.groqModels = ids
        prefs.groqModelsAt = System.currentTimeMillis()
        return ids
    }

    /** The model normal questions go to: the user's choice if still available, else the best available. */
    fun currentModel(): String? = pickModel(CHAT_PREFERENCE)

    private fun pickModel(preference: List<String>, vision: Boolean = false, exact: Boolean = false): String? {
        val available = try {
            models()
        } catch (e: Exception) {
            prefs.groqModels // offline / error: use whatever we knew
        }
        if (!vision && !exact) {
            val chosen = prefs.groqModel
            if (chosen.isNotBlank() && (available.isEmpty() || chosen in available)) return chosen
        }
        preference.firstOrNull { it in available }?.let { return it }
        if (exact || vision) return null
        // Unknown future lineup: any chat model, larger ones first.
        return available.sortedByDescending { sizeOf(it) }.firstOrNull()
            ?: if (available.isEmpty()) preference.first() else null
    }

    private fun sizeOf(id: String): Int = Regex("(\\d+)b", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun send(model: String, system: String, history: List<Msg>, question: String, imageJpeg: String?): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for (m in history) messages.put(JSONObject().put("role", m.role).put("content", m.text))
        if (imageJpeg != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", question))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageJpeg")))
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", question))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 1024)
        val body = request("POST", "$BASE/chat/completions", payload.toString())
        val choice = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
        return choice?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun request(method: String, url: String, json: String?): String {
        val key = prefs.groqKey.trim()
        if (key.isEmpty()) throw GroqException(401, "No Groq key")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                } catch (e: Exception) {
                    text.take(200)
                }
                throw GroqException(code, message.ifBlank { "HTTP $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
