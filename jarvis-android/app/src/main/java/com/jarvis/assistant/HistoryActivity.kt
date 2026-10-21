package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Every conversation saved on the phone: a list, and each chat in full, to read, continue or delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout
    /** The chat being shown, or null for the list. */
    private var openId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), 0, dp(16), 0)
        val back = ImageButton(this)
        back.setImageResource(R.drawable.ic_back)
        back.imageTintList = ContextCompat.getColorStateList(this, R.color.text_primary)
        back.setBackgroundResource(android.R.color.transparent)
        back.contentDescription = "Back"
        back.setOnClickListener { goBack() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = TextView(this)
        title.textSize = 18f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), 0, dp(16), dp(24))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = LinearLayout(this)
        footer.setPadding(dp(16), dp(8), dp(16), dp(16))
        footer.gravity = Gravity.END
        root.addView(footer)

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        showList()
    }

    private fun goBack() {
        if (openId != null) showList() else finish()
    }

    private fun showList() {
        openId = null
        title.text = "Chat history"
        content.removeAllViews()
        footer.removeAllViews()
        footer.visibility = View.GONE
        val sessions = ChatStore.sessions(this)
        if (sessions.isEmpty()) {
            content.addView(label("No saved chats yet. Everything you say to Voxa is saved here, on this phone.", 15f, R.color.text_secondary).apply {
                setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }
        for (s in sessions) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setBackgroundResource(R.drawable.card)
            card.foreground = ContextCompat.getDrawable(this, R.drawable.row_ripple)
            card.setPadding(dp(18), dp(14), dp(18), dp(14))
            card.isClickable = true
            card.setOnClickListener { showChat(s.id) }
            val name = label(s.title, 15.5f, R.color.text_primary)
            name.maxLines = 2
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            card.addView(name)
            val when_ = DateUtils.formatDateTime(this, s.end, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            val sub = (if (ChatStore.isCurrent(s.id)) "Current chat · " else "") + "$when_ · ${s.count} messages"
            card.addView(label(sub, 12.5f, R.color.text_tertiary).apply { setPadding(0, dp(4), 0, 0) })
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun showChat(id: Long) {
        openId = id
        val messages = ChatStore.session(this, id)
        title.text = messages.firstOrNull { it.role == ChatStore.Role.USER }?.text?.take(60) ?: "Chat"
        content.removeAllViews()
        val renderer = ChatRenderer(this, content)
        messages.forEach { renderer.add(it) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        footer.removeAllViews()
        footer.visibility = View.VISIBLE
        val delete = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        delete.text = "Delete"
        delete.setTextColor(ContextCompat.getColor(this, R.color.danger))
        delete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete this chat?")
                .setMessage("It is removed from this phone, with any files attached to it.")
                .setPositiveButton("Delete") { _, _ ->
                    ChatStore.deleteSession(this, id)
                    showList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        footer.addView(delete)
        if (!ChatStore.isCurrent(id)) {
            val cont = MaterialButton(this)
            cont.text = "Continue this chat"
            cont.setOnClickListener {
                ChatStore.resume(this, id)
                AiBrain.forgetAll()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            footer.addView(cont, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(this@HistoryActivity, color))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
