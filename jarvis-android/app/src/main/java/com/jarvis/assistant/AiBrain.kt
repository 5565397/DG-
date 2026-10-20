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
 * Voxa's online "brain": answers any question using the engine picked in Settings —
 * Groq (fast, free tier) or Claude (with web search and the strongest vision). Keeps a
 * short memory of the conversation so follow-ups ("and how tall is he?") make sense.
 * With the engine set to offline it is never called.
 *
 * Network call — always run [ask] off the main thread.
 */
class AiBrain(context: Context) {

    /** What Claude said: either something to speak, or a phone command to run. */
    data class Reply(val speech: String, val command: String? = null, val failed: Boolean = false)

    private val prefs = Prefs(context)
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    companion object {
        /** Alternating user / assistant turns, shared by every AiBrain so one conversation continues across screens. */
        private val history = ArrayList<Pair<String, String>>()

        fun forgetAll() = synchronized(history) { history.clear() }
    }

    val hasKey: Boolean get() = prefs.aiActive
    private val groq = GroqClient(prefs)

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
            You are Voxa, a personal voice assistant on the user's Android phone: calm, warm, quick-witted, always respectful, with a light touch of humor. When speaking Hindi or Urdu, Voxa uses feminine grammar for herself (for example "کر رہی ہوں", "कर रही हूँ"). Address the user as "${prefs.title}" occasionally, not in every sentence.

            Your reply is read aloud by text-to-speech, so:
            - Answer in plain spoken language. No markdown, bullet points, headings, emojis, URLs, or citation marks.
            - Keep it short: usually one to three sentences. Go longer only if the user asks for detail, a story, or step-by-step help, and even then stay under about 150 words.
            - Lead with the answer. Write numbers and units the way a person would say them.
            - ${webRule()}
            - If you're not sure, say so briefly instead of guessing.

            ${languageRule()}

            Questions and requests for information: answer them yourself, directly and helpfully — facts, explanations, advice, translations, maths, writing, and what is publicly known about people, places, companies and events. Never send the user off to search; you are the answer. For a public figure, share what is publicly known. You have no access to private people's personal records or contact details; if asked, say so in one sentence and offer what you can do instead.

            Phone actions: only when the user clearly wants you to operate the phone (open an app, call or message someone, play something, set an alarm or timer, toggle a setting, navigate somewhere, take a photo or screenshot, scroll, go back, and so on), reply with exactly one line and nothing else:
            CMD: <the request as a short English command>
            Examples: "CMD: open YouTube", "CMD: call Ammi", "CMD: send a message to Ali saying I am on my way", "CMD: WhatsApp Sara saying see you at 8", "CMD: open WhatsApp chat with Ali","CMD: set an alarm for 6:30 am", "CMD: set a timer for 10 minutes", "CMD: turn on bluetooth", "CMD: play Atif Aslam songs on YouTube", "CMD: navigate to Lahore airport". Write names of people, apps and places in English letters. Never use CMD for questions or for searching — answer those yourself.

            Current date and time on the phone: $now.
        """.trimIndent() + personalContext().let { if (it.isEmpty()) "" else "\n\n$it" }
    }

    private fun webRule(): String =
        if (prefs.aiEngine == Prefs.ENGINE_CLAUDE) "Use web search for anything current or time-sensitive (news, sports scores, weather, prices, recent events), then give the answer in a sentence or two."
        else "For anything current or time-sensitive (news, scores, weather, prices), use web results if you have them; if you don't, say briefly that you can't check live information right now."

    private fun t(en: String, hi: String, ur: String) = when (Lang.mode(prefs)) {
        "hi" -> hi
        "ur" -> ur
        else -> en
    }

    /** Questions that are better answered with live web results when the engine has them. */
    private val needsWebRegex = Regex(
        "weather|news|today|tonight|tomorrow|latest|score|match|price|rate|stock|who won|current|right now|who is|what is|" +
            "information|info|details|data|about|biography|net worth|age of|how old|population|" +
            "موسم|خبر|آج|کل|میچ|قیمت|ریٹ|سکور|اسکور|تازہ|کون ہے|کون ہیں|کے بارے|معلومات|ڈیٹا|تفصیل|" +
            "मौसम|खबर|ख़बर|आज|कल|मैच|कीमत|स्कोर|ताज़ा|कौन है|कौन हैं|के बारे|जानकारी|डेटा|" +
            "mausam|khabar|aaj|kaun hai|kon hai|bare mein|baare mein|maloomat|jankari",
        RegexOption.IGNORE_CASE
    )

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
    fun ask(question: String, screenJpeg: String? = null, remember: Boolean = true): Reply {
        if (!hasKey) return Reply(Lang.get(prefs, Lang.Line.NO_BRAIN), failed = true)
        val raw = when (prefs.aiEngine) {
            Prefs.ENGINE_GROQ -> askGroq(question, screenJpeg)
            else -> askClaude(question, screenJpeg)
        }
        if (raw.error != null) return Reply(raw.error, failed = true)
        var text = stripThinking(raw.text)
        var command = commandIn(text)
        // The model chose "search" for something the user wanted answered: ask it to answer.
        if (command != null && Regex("^(search|google|look up|find|browse)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            val retry = when (prefs.aiEngine) {
                Prefs.ENGINE_GROQ -> askGroq("$question\n\n(Answer this yourself in plain speech. Do not reply with CMD.)", screenJpeg, forceWeb = true)
                else -> askClaude("$question\n\n(Answer this yourself in plain speech. Do not reply with CMD.)", screenJpeg)
            }
            if (retry.error == null) {
                text = stripThinking(retry.text)
                command = commandIn(text)?.takeUnless { Regex("^(search|google|look up|find|browse)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                if (command == null) text = text.replace(Regex("(?m)^\\s*CMD:.*$"), "").trim()
            }
        }
        val answer = if (command != null) "" else cleanForSpeech(text)
        if (command == null && answer.isBlank()) return Reply(t("I came up empty on that one, ${prefs.title}.", "इसका जवाब नहीं मिला।", "اس کا جواب نہیں ملا۔"))

        if (remember) synchronized(history) {
            history.add((if (screenJpeg != null) "[about my screen] $question" else question) to (if (command != null) "(Done on the phone: $command)" else answer))
            while (history.size > 8) history.removeAt(0)
        }
        return Reply(answer, command)
    }

    private class Raw(val text: String = "", val error: String? = null)

    private fun stripThinking(text: String) = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

    private fun commandIn(text: String) = Regex("(?m)^\\s*CMD:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()?.trim('"', '.')

    private fun askGroq(question: String, screenJpeg: String?, forceWeb: Boolean = false): Raw {
        val past = synchronized(history) {
            history.flatMap { (q, a) -> listOf(GroqClient.Msg("user", q), GroqClient.Msg("assistant", a)) }
        }
        val prompt = if (screenJpeg != null) "This is a screenshot of my phone screen right now. $question" else question
        return try {
            Raw(groq.chat(systemPrompt(), past, prompt, screenJpeg, needsWeb = forceWeb || needsWebRegex.containsMatchIn(question)))
        } catch (e: GroqClient.GroqException) {
            Raw(error = when (e.code) {
                401, 403 -> t("My Groq key was rejected, ${prefs.title}. Please check it in Settings.", "Groq की key काम नहीं कर रही। सेटिंग्स में देख लीजिए।", "Groq کی key کام نہیں کر رہی۔ سیٹنگز میں دیکھ لیجیے۔")
                429 -> t("Groq says I'm asking too fast. Give me a moment and try again.", "Groq की लिमिट पूरी हो गई। थोड़ी देर बाद पूछिए।", "Groq کی حد پوری ہو گئی۔ تھوڑی دیر بعد پوچھیے۔")
                else -> t("My Groq service had a problem. Error ${e.code}.", "Groq में दिक़्क़त आई। एरर ${e.code}।", "Groq میں مسئلہ آیا۔ ایرر ${e.code}۔")
            })
        } catch (e: Exception) {
            Raw(error = t("I couldn't reach Groq. Check the internet connection, ${prefs.title}.", "Groq से कनेक्ट नहीं हो पाई। इंटरनेट देख लीजिए।", "Groq سے رابطہ نہیں ہو سکا۔ انٹرنیٹ دیکھ لیجیے۔"))
        }
    }

    private fun askClaude(question: String, screenJpeg: String?): Raw {
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
                Raw(error = t("I'm afraid I can't help with that one, ${prefs.title}.", "माफ़ कीजिए, इसमें मदद नहीं कर सकती।", "معاف کیجیے، اس میں مدد نہیں کر سکتی۔"))
            } else {
                Raw(parts.toString())
            }
        } catch (e: UnauthorizedException) {
            Raw(error = t("My Claude key was rejected, ${prefs.title}. Please check it in Settings.", "Claude की key काम नहीं कर रही।", "Claude کی key کام نہیں کر رہی۔"))
        } catch (e: RateLimitException) {
            Raw(error = t("I'm being rate limited right now. Give me a moment and ask again.", "अभी लिमिट पूरी हो गई। थोड़ी देर बाद पूछिए।", "ابھی حد پوری ہو گئی۔ تھوڑی دیر بعد پوچھیے۔"))
        } catch (e: AnthropicServiceException) {
            Raw(error = t("My AI service had a problem. Error ${e.statusCode()}.", "AI में दिक़्क़त आई। एरर ${e.statusCode()}।", "اے آئی میں مسئلہ آیا۔ ایرر ${e.statusCode()}۔"))
        } catch (e: Exception) {
            Raw(error = t("I couldn't reach my AI brain. Check the internet connection, ${prefs.title}.", "AI से कनेक्ट नहीं हो पाई। इंटरनेट देख लीजिए।", "اے آئی سے رابطہ نہیں ہو سکا۔ انٹرنیٹ دیکھ لیجیے۔"))
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
