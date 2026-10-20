package com.jarvis.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The living core of the interface: a softly glowing orb that breathes when idle,
 * ripples with your voice while listening, spins while thinking and pulses while
 * speaking. Pure Canvas drawing, no images.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var state: JarvisBus.State = JarvisBus.State.OFFLINE
        set(value) {
            field = value
            invalidate()
        }

    private var targetLevel = 0f
    private var level = 0f
    private var energy = 0f // eases between states

    private val accent = Color.parseColor("#6FD3FF")
    private val deep = Color.parseColor("#2B6BFF")

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val arcRect = RectF()
    private val density = resources.displayMetrics.density

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.46f

        val active = state == JarvisBus.State.LISTENING || state == JarvisBus.State.THINKING || state == JarvisBus.State.SPEAKING
        energy += ((if (active) 1f else 0f) - energy) * 0.08f
        if (state != JarvisBus.State.LISTENING) targetLevel *= 0.9f
        level += (targetLevel - level) * 0.25f

        val breathSpeed = when (state) {
            JarvisBus.State.SPEAKING -> 5.2f
            JarvisBus.State.THINKING -> 2.4f
            else -> 1.1f
        }
        val breath = (sin(t * breathSpeed) + 1f) / 2f
        val dim = if (state == JarvisBus.State.OFFLINE) 0.55f else 1f
        val radius = base * (1f + 0.035f * breath + 0.05f * energy + 0.16f * level)

        // Glow
        val glowRadius = radius * (2.1f + 0.25f * energy + 0.4f * level)
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(withAlpha(accent, (0.32f * dim * (0.7f + 0.3f * breath)).toAlphaInt()), withAlpha(deep, (0.10f * dim).toAlphaInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // Ripples while listening / speaking
        for (i in 0 until 3) {
            val phase = ((t * (0.35f + 0.25f * energy) + i / 3f) % 1f)
            val r = radius * (1.08f + phase * (0.55f + 0.5f * level))
            ringPaint.strokeWidth = density * (1.2f - phase * 0.6f)
            ringPaint.alpha = ((1f - phase) * (0.10f + 0.35f * energy) * dim * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // Spinning arcs while thinking
        if (state == JarvisBus.State.THINKING) {
            arcPaint.strokeWidth = density * 2f
            for (i in 0 until 2) {
                val r = radius * (1.22f + i * 0.16f)
                arcRect.set(cx - r, cy - r, cx + r, cy + r)
                val start = (t * (140f + i * 70f) * (if (i == 0) 1 else -1)) % 360f
                arcPaint.alpha = (if (i == 0) 200 else 110)
                canvas.drawArc(arcRect, start, 70f, false, arcPaint)
                canvas.drawArc(arcRect, start + 180f, 40f, false, arcPaint)
            }
        }

        // Core
        val shimmer = (sin(t * 0.7f + PI.toFloat() / 3f) + 1f) / 2f
        corePaint.shader = RadialGradient(
            cx - radius * 0.28f * shimmer, cy - radius * 0.32f, radius * 1.25f,
            intArrayOf(
                withAlpha(Color.parseColor("#E8FAFF"), (255 * dim).toInt()),
                withAlpha(accent, (255 * dim).toInt()),
                withAlpha(deep, (255 * dim).toInt()),
                withAlpha(Color.parseColor("#0B1E4A"), (255 * dim).toInt())
            ),
            floatArrayOf(0f, 0.35f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, corePaint)

        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun Float.toAlphaInt() = (this * 255).toInt().coerceIn(0, 255)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
