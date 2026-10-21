package com.jarvis.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws chat messages as bubbles into [list] — used by the main screen, the lock-screen window
 * and the chat history. New views go just before [anchor] (the live-text bubble), or at the end.
 */
class ChatRenderer(private val ctx: Context, private val list: LinearLayout, private val anchor: View? = null, widthFraction: Float = 0.80f) {

    private var lastRole: ChatStore.Role? = null
    private var lastDay = ""
    private val maxWidth = (ctx.resources.displayMetrics.widthPixels * widthFraction).toInt()

    /** Forget what was drawn (call after removing the bubbles). */
    fun reset() {
        lastRole = null
        lastDay = ""
    }

    fun add(m: ChatStore.Message) {
        val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(m.time))
        if (day != lastDay) {
            lastDay = day
            insert(note(day), topMargin = 18)
            lastRole = null
        }
        if (m.role == ChatStore.Role.NOTE) {
            insert(note(m.text), topMargin = 8)
            lastRole = ChatStore.Role.NOTE
            return
        }
        val user = m.role == ChatStore.Role.USER
        val sameSender = lastRole == m.role
        if (!user && !sameSender) {
            val label = TextView(ctx)
            label.text = "VOXA"
            label.letterSpacing = 0.25f
            label.textSize = 10.5f
            label.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            label.setPadding(dp(6), 0, 0, 0)
            insert(label, topMargin = 14)
        }
        lastRole = m.role
        if (m.filePath != null) {
            addFileBubble(m, user, sameSender)
            return
        }
        val bubble = TextView(ctx)
        bubble.text = m.text
        bubble.textSize = 15.5f
        bubble.setLineSpacing(dp(3).toFloat(), 1f)
        bubble.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        bubble.setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_voxa)
        bubble.setPadding(dp(16), dp(10), dp(16), dp(11))
        bubble.maxWidth = maxWidth
        bubble.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        bubble.setOnLongClickListener {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Voxa", m.text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(if (sameSender) 4 else if (user) 14 else 4)
        list.addView(bubble, position(), lp)
    }

    /** A message with a picture or file: the picture (or a file row) and the text under it. */
    private fun addFileBubble(m: ChatStore.Message, user: Boolean, sameSender: Boolean) {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_voxa)
        box.setPadding(dp(6), dp(6), dp(6), dp(6))
        val maxW = (maxWidth * 0.9f).toInt()
        val kind = FileAnalyzer.kindOf(m.fileMime.orEmpty(), m.fileName.orEmpty())
        val bmp = if (kind == FileAnalyzer.Kind.IMAGE && java.io.File(m.filePath!!).exists()) thumbnail(m.filePath, 700) else null
        if (bmp != null) {
            val img = ImageView(ctx)
            img.setImageBitmap(bmp)
            img.adjustViewBounds = true
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.maxWidth = maxW
            img.maxHeight = dp(320)
            img.clipToOutline = true
            img.setBackgroundResource(R.drawable.card)
            box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val row = TextView(ctx)
            row.text = m.fileName ?: "File"
            row.textSize = 14f
            row.maxWidth = maxW
            row.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            row.compoundDrawablePadding = dp(10)
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_file)?.mutate()
            icon?.setTint(ContextCompat.getColor(ctx, R.color.accent))
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            row.setPadding(dp(10), dp(8), dp(12), dp(8))
            box.addView(row)
        }
        if (m.text.isNotBlank()) {
            val tv = TextView(ctx)
            tv.text = m.text
            tv.textSize = 15.5f
            tv.maxWidth = maxW
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            tv.setPadding(dp(10), dp(8), dp(10), dp(5))
            tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            box.addView(tv)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(if (sameSender) 4 else 14)
        list.addView(box, position(), lp)
    }

    private fun note(text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 11.5f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        return tv
    }

    private fun insert(view: TextView, topMargin: Int) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topMargin)
        list.addView(view, position(), lp)
    }

    private fun position(): Int = anchor?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: list.childCount

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun thumbnail(path: String, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
