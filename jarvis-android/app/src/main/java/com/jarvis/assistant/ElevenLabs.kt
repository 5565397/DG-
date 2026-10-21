package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ElevenLabs text-to-speech (elevenlabs.io): very natural, human-sounding voices that also speak
 * Hindi, Urdu and Hinglish. Needs the user's own API key; calls are made off the main thread.
 */
object ElevenLabs {

    private const val BASE = "https://api.elevenlabs.io/v1"

    /** "Sarah" — a warm, natural female voice from ElevenLabs' default library. */
    const val DEFAULT_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"
    const val DEFAULT_VOICE_NAME = "Sarah"

    /** Low-latency model that speaks 30+ languages, Hindi included; Roman Hinglish reads naturally. */
    private const val MODEL = "eleven_flash_v2_5"

    class ElevenException(val code: Int, message: String) : Exception(message)

    data class Voice(val id: String, val name: String, val description: String)

    /** MP3 audio of [text] spoken by [voiceId]. */
    fun speech(key: String, voiceId: String, text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL)
            .put(
                "voice_settings",
                JSONObject().put("stability", 0.45).put("similarity_boost", 0.8).put("style", 0.25).put("use_speaker_boost", true)
            )
        val conn = open("$BASE/text-to-speech/$voiceId?output_format=mp3_44100_128", key, "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/mpeg")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return read(conn)
    }

    /** The voices on this account (premade and the user's own), female ones first. */
    fun voices(key: String): List<Voice> {
        val json = JSONObject(String(read(open("$BASE/voices", key, "GET")), Charsets.UTF_8))
        val arr = json.optJSONArray("voices") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val labels = v.optJSONObject("labels")
            val bits = listOfNotNull(
                labels?.optString("gender")?.takeIf { it.isNotBlank() },
                labels?.optString("accent")?.takeIf { it.isNotBlank() },
                labels?.optString("description")?.takeIf { it.isNotBlank() }
                    ?: labels?.optString("descriptive")?.takeIf { it.isNotBlank() }
            )
            Voice(v.optString("voice_id"), v.optString("name"), bits.joinToString(" · "))
        }
        return list.sortedByDescending { it.description.startsWith("female", ignoreCase = true) }
    }

    private fun open(url: String, key: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("xi-api-key", key)
        return conn
    }

    private fun read(conn: HttpURLConnection): ByteArray {
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val detail = try {
                    JSONObject(err).optJSONObject("detail")?.optString("message") ?: JSONObject(err).optString("detail")
                } catch (e: Exception) {
                    err
                }
                throw ElevenException(code, detail.take(200))
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
