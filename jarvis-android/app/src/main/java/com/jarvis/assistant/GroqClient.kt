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
        const val TTS_MODEL = "canopylabs/orpheus-v1-english"
        /** Groq's female voices for [TTS_MODEL] (name, description). */
        val FEMALE_VOICES = listOf("autumn" to "Autumn · warm", "diana" to "Diana · clear", "hannah" to "Hannah · friendly")
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
        /** Model ids that can take pictures, for lineups newer than [VISION_PREFERENCE]. */
        private val VISION_RX = Regex("llama-4|vision|scout|maverick|-vl\\b|llava|pixtral|gemma-3|qwen[^/]*vl|kimi-vl", RegexOption.IGNORE_CASE)
        fun isVisionModel(id: String) = id in VISION_PREFERENCE || VISION_RX.containsMatchIn(id)
        private val WEB_PREFERENCE = listOf("groq/compound", "groq/compound-mini")
        private val NOT_CHAT = Regex("whisper|tts|guard|playai|orpheus|distil|embed|prompt-guard|safeguard", RegexOption.IGNORE_CASE)
    }

    data class Msg(val role: String, val text: String)

    /** Sends a chat and returns the reply text. [imageJpeg] makes it a vision request; [needsWeb] prefers a web-search model. */
    fun chat(system: String, history: List<Msg>, question: String, images: List<String>, needsWeb: Boolean, maxTokens: Int = 1024): String {
        val imageJpeg = images.firstOrNull()
        val webModel = needsWeb && imageJpeg == null
        val model = when {
            imageJpeg != null -> visionModels().firstOrNull() ?: pickModel(CHAT_PREFERENCE)
            // Groq's compound models search the web themselves; try them even if the list didn't show them.
            webModel -> pickModel(WEB_PREFERENCE, exact = true) ?: WEB_PREFERENCE.first()
            else -> pickModel(CHAT_PREFERENCE)
        } ?: throw GroqException(404, "No Groq chat model available")
        val canSeeImage = imageJpeg != null && isVisionModel(model)
        if (canSeeImage) {
            // Try each picture model this key has; a retired one shouldn't cost the user the answer.
            var last: GroqException? = null
            for (m in visionModels().ifEmpty { listOf(model) }) {
                try {
                    return send(m, system, history, question, images.take(5), maxTokens)
                } catch (e: GroqException) {
                    if (e.code == 401 || e.code == 429) throw e
                    last = e
                }
            }
            throw last ?: GroqException(404, "No Groq vision model available")
        }
        return try {
            send(model, system, history, question, if (canSeeImage) images.take(5) else emptyList(), maxTokens)
        } catch (e: GroqException) {
            if (webModel && e.code != 401 && e.code != 429) {
                // Web model unavailable on this key: answer with the normal chat model instead.
                val fallback = pickModel(CHAT_PREFERENCE) ?: throw e
                send(fallback, system, history, question, emptyList(), maxTokens)
            } else if (e.code == 404 || (e.code == 400 && e.message.orEmpty().contains("model", ignoreCase = true))) {
                // Model retired or not allowed: refresh the list once and try the next best one.
                prefs.groqModels = emptyList()
                val retry = pickModel(CHAT_PREFERENCE) ?: throw e
                send(retry, system, history, question, emptyList(), maxTokens)
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

    /** True if this key can use a model that sees pictures. */
    fun hasVision(): Boolean = visionModels().isNotEmpty()

    /** Picture-capable models on this key, best first. If the model list can't be read, the known ones are tried. */
    private fun visionModels(): List<String> {
        val available = try {
            models()
        } catch (e: Exception) {
            prefs.groqModels
        }
        if (available.isEmpty()) return VISION_PREFERENCE
        val known = VISION_PREFERENCE.filter { it in available }
        val other = available.filter { it !in known && VISION_RX.containsMatchIn(it) }.sortedByDescending { sizeOf(it) }
        return known + other
    }

    /** Speech-to-text with Groq's Whisper (works for audio and video files up to 25 MB). */
    fun transcribe(file: java.io.File): String {
        val models = listOf("whisper-large-v3-turbo", "whisper-large-v3")
        var last: GroqException? = null
        for (model in models) {
            try {
                val body = multipart("$BASE/audio/transcriptions", model, file)
                return JSONObject(body).optString("text").trim()
            } catch (e: GroqException) {
                last = e
                if (e.code != 404 && e.code != 400) throw e
            }
        }
        throw last ?: GroqException(404, "No Whisper model available")
    }

    private fun multipart(url: String, model: String, file: java.io.File): String {
        val key = prefs.groqKey.trim()
        if (key.isEmpty()) throw GroqException(401, "No Groq key")
        val boundary = "----voxa" + System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                }
                field("model", model)
                field("response_format", "json")
                val fileName = file.name.substringAfter('_').ifBlank { "audio" }.replace("\"", "")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
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

    private fun send(model: String, system: String, history: List<Msg>, question: String, images: List<String>, maxTokens: Int): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for (m in history) messages.put(JSONObject().put("role", m.role).put("content", m.text))
        if (images.isNotEmpty()) {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", question))
            for (img in images) {
                content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$img")))
            }
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", question))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_completion_tokens", maxTokens)
        val body = request("POST", "$BASE/chat/completions", payload.toString())
        val choice = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
        return choice?.optJSONObject("message")?.optString("content").orEmpty()
    }

    /**
     * Speech from Groq's text-to-speech (Canopy Labs Orpheus): a natural, human-sounding voice.
     * Returns WAV audio. Keep [text] short (a sentence or two); longer replies are sent in pieces.
     */
    fun speech(text: String, voice: String): ByteArray {
        val key = prefs.groqKey.trim()
        if (key.isEmpty()) throw GroqException(401, "No Groq key")
        val body = JSONObject().put("model", TTS_MODEL).put("voice", voice).put("input", text).put("response_format", "wav")
        val conn = URL("$BASE/audio/speech").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text2 = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val message = try {
                    JSONObject(text2).optJSONObject("error")?.optString("message").orEmpty()
                } catch (e: Exception) {
                    text2.take(200)
                }
                throw GroqException(code, message.ifBlank { "HTTP $code" })
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
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
