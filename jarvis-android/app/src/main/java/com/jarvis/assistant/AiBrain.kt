package com.jarvis.assistant

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20260209
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jarvis's "brain": answers any question with Claude, and can search the web for
 * fresh facts (news, scores, weather). Keeps a short memory of the conversation so
 * follow-up questions ("and how tall is he?") make sense.
 *
 * Network call — always run [ask] off the main thread.
 */
class AiBrain(context: Context) {

    private val prefs = Prefs(context)
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Alternating user / assistant turns. */
    private val history = ArrayList<Pair<String, String>>()

    val hasKey: Boolean get() = prefs.apiKey.isNotBlank()

    fun forget() = synchronized(history) { history.clear() }

    private fun client(): AnthropicClient {
        val key = prefs.apiKey.trim()
        val existing = client
        if (existing != null && key == clientKey) return existing
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(Date())
        return """
            You are Jarvis, a personal voice assistant on the user's Android phone, in the style of Tony Stark's J.A.R.V.I.S.: calm, warm, quick-witted, a little dry British humor, always respectful. Address the user as "${prefs.title}" occasionally, not in every sentence.

            Your reply is read aloud by text-to-speech, so:
            - Answer in plain spoken English. No markdown, bullet points, headings, emojis, URLs, or citation marks.
            - Keep it short: usually one to three sentences. Go longer only if the user asks for detail, a story, or step-by-step help, and even then stay under about 150 words.
            - Lead with the answer. Write numbers and units the way a person would say them.
            - Use web search for anything current or time-sensitive (news, sports scores, weather, prices, recent events), then give the answer in a sentence or two.
            - If you're not sure, say so briefly instead of guessing.

            Current date and time on the phone: $now.
        """.trimIndent()
    }

    /** Returns the spoken answer, or a short spoken error. */
    fun ask(question: String): String {
        if (!hasKey) return "My AI brain isn't connected yet, ${prefs.title}. Add your Claude API key in my settings."

        val builder = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(4096L)
            .system(systemPrompt())
            // Quick spoken answers: low effort keeps latency and cost down.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
            // If a safety classifier declines, let the API retry on a suitable model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

        synchronized(history) {
            for ((q, a) in history) {
                builder.addUserMessage(q)
                builder.addAssistantMessage(a)
            }
        }
        builder.addUserMessage(question)

        return try {
            var response: Message = client().messages().create(builder.build())
            val parts = StringBuilder(textOf(response))
            // Web search can pause a long turn; send it back so Claude can finish.
            var rounds = 0
            while (response.stopReason().orElse(null) == StopReason.PAUSE_TURN && rounds < 3) {
                builder.addMessage(response)
                response = client().messages().create(builder.build())
                parts.append(' ').append(textOf(response))
                rounds++
            }

            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                return "I'm afraid I can't help with that one, ${prefs.title}."
            }
            val answer = cleanForSpeech(parts.toString())
            if (answer.isBlank()) return "I came up empty on that one, ${prefs.title}."

            synchronized(history) {
                history.add(question to answer)
                while (history.size > 8) history.removeAt(0)
            }
            answer
        } catch (e: UnauthorizedException) {
            "My API key was rejected, ${prefs.title}. Please check it in my settings."
        } catch (e: RateLimitException) {
            "I'm being rate limited right now. Give me a moment and ask again."
        } catch (e: AnthropicServiceException) {
            "My AI service had a problem. Error ${e.statusCode()}."
        } catch (e: Exception) {
            "I couldn't reach my AI brain. Check the internet connection, ${prefs.title}."
        }
    }

    private fun textOf(message: Message): String =
        message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")

    /** Strips anything that sounds bad when read aloud. */
    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#`>|]"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
