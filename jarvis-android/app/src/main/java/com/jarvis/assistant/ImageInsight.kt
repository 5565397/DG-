package com.jarvis.assistant

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device picture reading (no internet, no key): the text written in a picture and the things in it.
 * Runs on a background thread. Gives the AI something to work with even when its model can't see images,
 * and gives the user an answer when the AI is switched off.
 */
object ImageInsight {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }

    data class Result(val text: String, val labels: List<String>) {
        val isEmpty get() = text.isBlank() && labels.isEmpty()

        /** Plain description for the AI prompt or the chat. */
        fun describe(): String = buildString {
            if (labels.isNotEmpty()) append("Things detected in the picture: ").append(labels.joinToString(", ")).append('.')
            if (text.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("Text written in the picture:\n").append(text)
            }
        }
    }

    fun read(bmp: Bitmap): Result {
        val image = InputImage.fromBitmap(bmp, 0)
        val latinText = await { latin.process(image) }?.text.orEmpty()
        // Hindi text comes out as garbage from the Latin reader; read it with the Devanagari one too.
        val hindiText = await { devanagari.process(image) }?.text.orEmpty()
        val text = when {
            hindiText.any { it in 'ऀ'..'ॿ' } && hindiText.length >= latinText.length / 2 -> hindiText
            else -> latinText
        }.trim()
        val labels = await { labeler.process(image) }.orEmpty()
            .sortedByDescending { it.confidence }
            .take(8)
            .map { "${it.text.lowercase()} (${(it.confidence * 100).toInt()}%)" }
        return Result(text, labels)
    }

    private fun <T> await(task: () -> com.google.android.gms.tasks.Task<T>): T? = try {
        Tasks.await(task(), 20, TimeUnit.SECONDS)
    } catch (e: Exception) {
        null
    }
}
