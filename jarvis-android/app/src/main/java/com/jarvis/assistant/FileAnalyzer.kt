package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Turns a file the user attached into something the AI can understand:
 * pictures -> images, PDFs and Office files -> text (or page images for scans),
 * audio -> a transcript, video -> a transcript plus a few frames.
 */
object FileAnalyzer {

    enum class Kind { IMAGE, PDF, DOCUMENT, TEXT, AUDIO, VIDEO, OTHER }

    /** A copy of the picked file kept in the app's own storage, so it can be shown and re-read later. */
    data class Attachment(val path: String, val name: String, val mime: String, val size: Long) {
        val kind: Kind get() = kindOf(mime, name)
    }

    /** What the AI gets: text, images, and a short note for the chat. */
    data class Prepared(
        val text: String = "",
        val images: List<String> = emptyList(),
        val note: String = "",
        val error: String? = null,
        val truncated: Boolean = false
    )

    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    fun kindOf(mime: String, name: String): Kind {
        val m = mime.lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") -> Kind.IMAGE
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "ogg", "opus", "aac", "flac", "amr") -> Kind.AUDIO
            m.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "3gp", "mov") -> Kind.VIDEO
            ext in setOf("docx", "xlsx", "pptx", "odt") || m.contains("officedocument") -> Kind.DOCUMENT
            m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("csv") ||
                ext in setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts", "c", "cpp", "srt", "vtt") -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /** Copies a picked file into app storage. Returns null if it can't be read or is too large. */
    fun importUri(context: Context, uri: Uri): Attachment? {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        return try {
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
            if (out.length() > MAX_BYTES) {
                out.delete()
                return null
            }
            Attachment(out.absolutePath, name, mime, out.length())
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    /** Reads the file for the AI. Slow (network for audio) — call off the main thread. [maxChars] caps the text. */
    fun prepare(context: Context, a: Attachment, maxChars: Int, groq: GroqClient?): Prepared {
        val file = File(a.path)
        if (!file.exists()) return Prepared(error = "The file is no longer on the phone.")
        return try {
            when (a.kind) {
                Kind.IMAGE -> {
                    val img = imageFile(file) ?: return Prepared(error = "I couldn't open that picture.")
                    Prepared(images = listOf(img), note = "Picture")
                }
                Kind.PDF -> pdf(context, file, maxChars)
                Kind.DOCUMENT -> limit(officeText(file, a.name), maxChars, "Document")
                Kind.TEXT -> limit(file.readText(Charsets.UTF_8), maxChars, "Text file")
                Kind.AUDIO -> {
                    if (groq == null) return Prepared(error = "Listening to audio needs a Groq key (it uses Groq's Whisper). Add it in Settings → AI Brain.")
                    if (file.length() > MAX_AUDIO_BYTES) return Prepared(error = "That audio is over 25 MB — please send a shorter clip.")
                    val transcript = groq.transcribe(file)
                    limit("Transcript of the audio:\n$transcript", maxChars, "Audio transcript")
                }
                Kind.VIDEO -> video(file, maxChars, groq)
                Kind.OTHER -> Prepared(error = "I can't read this type of file yet. Try a picture, PDF, Word, Excel, PowerPoint, text, audio or video file.")
            }
        } catch (e: GroqClient.GroqException) {
            Prepared(error = "Groq couldn't process the file (error ${e.code}). ${e.message.orEmpty().take(120)}")
        } catch (e: Exception) {
            Prepared(error = "I couldn't read that file. ${e.message.orEmpty().take(120)}")
        }
    }

    private fun limit(text: String, maxChars: Int, note: String): Prepared {
        val clean = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.isBlank()) return Prepared(error = "That file has no readable text.")
        return if (clean.length > maxChars) Prepared(text = clean.take(maxChars), note = note, truncated = true)
        else Prepared(text = clean, note = note)
    }

    // ---------------------------------------------------------------- images

    /** Thumbnail-safe decode, scaled to at most 1280 px, as base64 JPEG. */
    fun imageFile(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return toJpeg(bmp)
    }

    private fun toJpeg(source: Bitmap): String {
        val scale = minOf(1f, 1280f / maxOf(source.width, source.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (bmp !== source) bmp.recycle()
        source.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- PDF

    private fun pdf(context: Context, file: File, maxChars: Int): Prepared {
        PDFBoxResourceLoader.init(context.applicationContext)
        val text = PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = minOf(doc.numberOfPages, 60)
            "PDF with ${doc.numberOfPages} pages.\n" + stripper.getText(doc)
        }
        if (text.lines().drop(1).joinToString("").isNotBlank()) return limit(text, maxChars, "PDF")
        // Scanned PDF (pictures of pages): send the first pages as images instead.
        val pages = ArrayList<String>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, 3)) {
                    renderer.openPage(i).use { page ->
                        val scale = 1400f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(toJpeg(bmp))
                    }
                }
            }
        }
        return Prepared(images = pages, note = "Scanned PDF (first ${pages.size} pages)")
    }

    // ---------------------------------------------------------------- Office files

    /** Text from .docx / .xlsx / .pptx / .odt — they're zip files of XML. */
    private fun officeText(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        ZipFile(file).use { zip ->
            fun read(entry: String) = zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
            fun xmlText(xml: String, paragraph: String) = xml
                .replace(Regex("</$paragraph>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            return when (ext) {
                "docx" -> xmlText(read("word/document.xml").orEmpty(), "w:p")
                "pptx" -> zip.entries().toList()
                    .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.name.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    .mapIndexed { i, e -> "Slide ${i + 1}:\n" + xmlText(zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }, "a:p") }
                    .joinToString("\n\n")
                "xlsx" -> {
                    val shared = Regex("<si>([\\s\\S]*?)</si>").findAll(read("xl/sharedStrings.xml").orEmpty())
                        .map { it.groupValues[1].replace(Regex("<[^>]+>"), "") }.toList()
                    zip.entries().toList()
                        .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                        .sortedBy { it.name }
                        .joinToString("\n\n") { e ->
                            val xml = zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                            "Sheet ${e.name.filter { it.isDigit() }}:\n" + Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(xml).joinToString("\n") { row ->
                                Regex("<c([^>]*)>(?:<f>[\\s\\S]*?</f>)?<v>([\\s\\S]*?)</v>").findAll(row.groupValues[1]).joinToString(" | ") { c ->
                                    val v = c.groupValues[2]
                                    if (c.groupValues[1].contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1) ?: v else v
                                }
                            }
                        }
                }
                "odt" -> xmlText(read("content.xml").orEmpty(), "text:p")
                else -> ""
            }
        }
    }

    // ---------------------------------------------------------------- video

    private fun video(file: File, maxChars: Int, groq: GroqClient?): Prepared {
        val frames = ArrayList<String>()
        var seconds = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            seconds = ms / 1000
            // Four frames spread across the video.
            for (f in listOf(0.1, 0.35, 0.6, 0.85)) {
                val bmp = mmr.getFrameAtTime((ms * f * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                frames.add(toJpeg(bmp))
            }
        } finally {
            mmr.release()
        }
        var transcript = ""
        if (groq != null && file.length() <= MAX_AUDIO_BYTES) {
            transcript = try {
                groq.transcribe(file)
            } catch (e: Exception) {
                ""
            }
        }
        val text = "Video, about $seconds seconds long. ${frames.size} frames from it are attached in order." +
            if (transcript.isNotBlank()) "\nTranscript of what is said in the video:\n$transcript" else "\n(No speech transcript available.)"
        if (frames.isEmpty() && transcript.isBlank()) return Prepared(error = "I couldn't read that video.")
        return Prepared(text = text.take(maxChars), images = frames, note = "Video", truncated = text.length > maxChars)
    }
}
