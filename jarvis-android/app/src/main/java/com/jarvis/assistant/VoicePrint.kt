package com.jarvis.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A lightweight, fully offline "voiceprint".
 *
 * It records a few seconds of speech, turns it into MFCC statistics (the classic
 * features used in speaker recognition) and compares them with the enrolled samples
 * using cosine similarity. It is good enough to stop other people casually giving
 * Jarvis orders, but it is not bank-grade biometrics: a recording of your voice or a
 * very similar voice can fool it.
 */
object VoicePrint {
    const val RECORD_MS = 3500
    private const val RATE = 16000
    private const val FRAME = 400 // 25 ms
    private const val HOP = 160 // 10 ms
    private const val NFFT = 512
    private const val MELS = 26
    private const val CEPS = 13

    fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
            Thread.sleep(220)
            tone.release()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun record(ms: Int = RECORD_MS): ShortArray {
        val total = RATE * ms / 1000
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096) * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone is busy")
        }
        val out = ShortArray(total)
        var read = 0
        rec.startRecording()
        try {
            while (read < total) {
                val n = rec.read(out, read, min(2048, total - read))
                if (n <= 0) break
                read += n
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return out.copyOf(read)
    }

    /** Beep, record, and score the voice against the enrolled samples. Returns 0..1. */
    fun verify(prefs: Prefs): Float {
        beep()
        val f = features(record()) ?: return 0f
        return score(f, prefs.voiceprints)
    }

    fun score(f: FloatArray, enrolled: List<FloatArray>): Float {
        if (enrolled.isEmpty()) return 0f
        return enrolled.map { similarity(f, it) }.average().toFloat()
    }

    /** MFCC mean + standard deviation over the voiced frames, or null if there was no speech. */
    fun features(pcm: ShortArray): FloatArray? {
        if (pcm.size < FRAME * 20) return null

        val x = DoubleArray(pcm.size)
        var prev = 0.0
        for (i in pcm.indices) {
            val s = pcm[i] / 32768.0
            x[i] = s - 0.97 * prev
            prev = s
        }

        val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val power = DoubleArray(NFFT / 2 + 1)
        val frames = ArrayList<DoubleArray>()
        val energies = ArrayList<Double>()

        var start = 0
        while (start + FRAME <= x.size) {
            re.fill(0.0)
            im.fill(0.0)
            var e = 0.0
            for (i in 0 until FRAME) {
                val v = x[start + i] * window[i]
                re[i] = v
                e += v * v
            }
            fft(re, im)
            for (k in power.indices) power[k] = (re[k] * re[k] + im[k] * im[k]) / NFFT
            val logMel = DoubleArray(MELS) { m ->
                var s = 0.0
                val f = filterbank[m]
                for (k in power.indices) s += f[k] * power[k]
                ln(s + 1e-10)
            }
            val ceps = DoubleArray(CEPS) { n ->
                var s = 0.0
                for (m in 0 until MELS) s += logMel[m] * cos(PI * n * (m + 0.5) / MELS)
                s
            }
            frames.add(ceps)
            energies.add(ln(e + 1e-10))
            start += HOP
        }
        if (frames.isEmpty()) return null

        val maxE = energies.maxOrNull() ?: return null
        if (maxE < ln(0.02)) return null // basically silence
        val floorE = max(maxE - 4.0, ln(0.003))
        val voiced = frames.indices.filter { energies[it] > floorE }
        if (voiced.size < 40) return null // less than ~0.4 s of speech

        val dims = CEPS - 1 // drop c0 (loudness)
        val lifter = DoubleArray(CEPS) { 1 + 11.0 * sin(PI * it / 22.0) }
        val mean = DoubleArray(dims)
        val sq = DoubleArray(dims)
        for (idx in voiced) {
            val c = frames[idx]
            for (d in 0 until dims) {
                val v = c[d + 1] * lifter[d + 1]
                mean[d] += v
                sq[d] += v * v
            }
        }
        val n = voiced.size.toDouble()
        val out = FloatArray(dims * 2)
        for (d in 0 until dims) {
            val m = mean[d] / n
            out[d] = m.toFloat()
            out[dims + d] = sqrt(max(0.0, sq[d] / n - m * m)).toFloat()
        }
        return out
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private val filterbank: Array<DoubleArray> by lazy {
        val low = hzToMel(80.0)
        val high = hzToMel(7600.0)
        val bins = IntArray(MELS + 2) {
            val hz = melToHz(low + (high - low) * it / (MELS + 1))
            floor((NFFT + 1) * hz / RATE).toInt()
        }
        Array(MELS) { m ->
            val f = DoubleArray(NFFT / 2 + 1)
            val l = bins[m]
            val c = bins[m + 1]
            val r = bins[m + 2]
            for (k in l until c) f[k] = (k - l).toDouble() / (c - l)
            for (k in c until r) f[k] = (r - k).toDouble() / (r - c)
            if (c in f.indices && f[c] == 0.0) f[c] = 1.0
            f
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
