package com.jarvis.assistant

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
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

    /** What Claude said: either something to speak, or a phone command to run. */
    data class Reply(val speech: String, val command: String? = null)

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
            - Answer in plain spoken language. No markdown, bullet points, headings, emojis, URLs, or citation marks.
            - Keep it short: usually one to three sentences. Go longer only if the user asks for detail, a story, or step-by-step help, and even then stay under about 150 words.
            - Lead with the answer. Write numbers and units the way a person would say them.
            - Use web search for anything current or time-sensitive (news, sports scores, weather, prices, recent events), then give the answer in a sentence or two.
            - If you're not sure, say so briefly instead of guessing.

            ${languageRule()}

            Phone actions: if the user is asking you to operate the phone (open an app, call or message someone, play something, set an alarm or timer, toggle a setting, navigate somewhere, take a photo or screenshot, scroll, go back, and so on), don't answer conversationally. Reply with exactly one line and nothing else:
            CMD: <the request as a short English command>
            Examples: "CMD: open YouTube", "CMD: call Ammi", "CMD: send a message to Ali saying I am on my way", "CMD: WhatsApp Sara saying see you at 8", "CMD: set an alarm for 6:30 am", "CMD: set a timer for 10 minutes", "CMD: turn on bluetooth", "CMD: play Atif Aslam songs on YouTube", "CMD: navigate to Lahore airport". Write names of people, apps and places in English letters.

            Current date and time on the phone: $now.
        """.trimIndent() + personalContext().let { if (it.isEmpty()) "" else "\n\n$it" }
    }

    private fun languageRule(): String = when (Lang.mode(prefs)) {
        "hi" -> "Language: the user has chosen Hindi. Always reply in natural, conversational spoken Hindi (Hindustani) written in Devanagari script, whatever language the question is in. Common English words like phone, app or internet are fine, written in Devanagari. Call the user \"सर\" if their title is sir."
        "ur" -> "Language: the user has chosen Urdu. Always reply in natural, conversational spoken Urdu written in Urdu (Nastaliq) script, whatever language the question is in. Common English words like phone, app or internet are fine, written in Urdu script. Call the user \"سر\" if their title is sir."
        else -> "Language: reply in English. But if the user speaks or writes in Hindi or Urdu (in any script, including Roman letters), reply in that language: Hindi in Devanagari script, Urdu in Urdu script${if (Speaker.urduVoice) "" else " (this phone has no Urdu voice, so write Urdu replies in Devanagari script with Urdu vocabulary)"}. Never reply in Roman Hindi or Urdu, because the voice can't read it."
    }

    private fun personalContext(): String {
        val lines = ArrayList<String>()
        if (prefs.city.isNotBlank()) lines.add("The user lives in ${prefs.city}; use it for weather and anything local unless they name another place.")
        val memories = prefs.memories
        if (memories.isNotEmpty()) {
            lines.add("Things the user asked you to remember (use them when relevant):")
            memories.forEach { lines.add("- $it") }
        }
        return lines.joinToString("\n")
    }

    /** Returns what to speak, or a phone command to run. Never throws. [screenJpeg] is an optional base64 screenshot. */
    fun ask(question: String, screenJpeg: String? = null): Reply {
        if (!hasKey) return Reply(Lang.get(prefs, Lang.Line.NO_BRAIN))

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
        if (screenJpeg != null) {
            val image = ImageBlockParam.builder()
                .source(Base64ImageSource.builder().data(screenJpeg).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                .build()
            builder.addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText("This is a screenshot of my phone screen right now. $question")
                )
            )
        } else {
            builder.addUserMessage(question)
        }

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
                return Reply("I'm afraid I can't help with that one, ${prefs.title}.")
            }
            val raw = parts.toString().trim()
            val command = Regex("(?m)^\\s*CMD:\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim()
            val answer = if (command != null) "" else cleanForSpeech(raw)
            if (command == null && answer.isBlank()) return Reply("I came up empty on that one, ${prefs.title}.")

            synchronized(history) {
                history.add((if (screenJpeg != null) "[about my screen] $question" else question) to (if (command != null) "CMD: $command" else answer))
                while (history.size > 8) history.removeAt(0)
            }
            Reply(answer, command)
        } catch (e: UnauthorizedException) {
            Reply("My API key was rejected, ${prefs.title}. Please check it in my settings.")
        } catch (e: RateLimitException) {
            Reply("I'm being rate limited right now. Give me a moment and ask again.")
        } catch (e: AnthropicServiceException) {
            Reply("My AI service had a problem. Error ${e.statusCode()}.")
        } catch (e: Exception) {
            Reply("I couldn't reach my AI brain. Check the internet connection, ${prefs.title}.")
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
