package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * The "hands" of Jarvis. Once the user enables it in Accessibility settings, Jarvis can
 * press buttons, scroll, type, go home/back, take screenshots, lock the screen, and
 * launch apps even while it is running in the background.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun closeShade() {
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ---------- Finding things on screen ----------

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            windows.mapNotNullTo(list) { it.root }
        } catch (_: Exception) {
        }
        return list
    }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val parts = listOfNotNull(n.text, n.contentDescription)
        return parts.joinToString(" ").lowercase(Locale.US).trim()
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null || depth > 60) return false
        if (visit(node)) return true
        for (i in 0 until node.childCount) {
            if (walk(node.getChild(i), depth + 1, visit)) return true
        }
        return false
    }

    private fun findFirst(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        for (root in roots()) {
            walk(root) { n ->
                if (predicate(n)) {
                    found = n
                    true
                } else {
                    false
                }
            }
            if (found != null) break
        }
        return found
    }

    // ---------- Actions ----------

    /** Taps the on-screen item whose text or description best matches [text]. */
    fun clickText(text: String): Boolean {
        val target = text.lowercase(Locale.US).trim()
        if (target.isEmpty()) return false
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (root in roots()) {
            walk(root) { n ->
                val label = labelOf(n)
                if (label.isNotEmpty() && n.isVisibleToUser) {
                    val score = when {
                        label == target -> 3
                        label.startsWith(target) -> 2
                        label.contains(target) -> 1
                        else -> 0
                    }
                    if (score > bestScore) {
                        best = n
                        bestScore = score
                    }
                }
                bestScore == 3
            }
            if (bestScore == 3) break
        }
        return best?.let { clickNode(it) } ?: false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** direction: "up", "down", "left", "right" — the direction the content moves towards the user. */
    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f)
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
            "right" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2)
            "left" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2)
            else -> false
        }
    }

    /** Types text into the focused text box (or the first one on screen). */
    fun typeText(text: String): Boolean {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirst { it.isEditable && it.isVisibleToUser }
            ?: return false
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Collects the visible text of the current screen. */
    fun readScreen(maxChars: Int = 450): String {
        val sb = StringBuilder()
        walk(rootInActiveWindow) { n ->
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) sb.append(t).append(". ")
            sb.length >= maxChars
        }
        return sb.toString().take(maxChars)
    }

    /** Waits for a button (by view id or description) to appear and taps it — used for WhatsApp "Send". */
    fun clickWhenAppears(viewId: String?, description: String, timeoutMs: Long = 9000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val task = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                var node: AccessibilityNodeInfo? = null
                if (root != null && viewId != null) {
                    node = root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
                }
                if (node == null) {
                    node = findFirst { it.contentDescription?.toString()?.equals(description, true) == true }
                }
                if (node != null && clickNode(node)) return
                if (SystemClock.uptimeMillis() < deadline) main.postDelayed(this, 500)
            }
        }
        main.postDelayed(task, 1500)
    }

    /** Opens Quick Settings and taps the tile whose label matches one of [labels]. */
    fun toggleQuickSetting(labels: List<String>, onResult: (Boolean) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        main.postDelayed({
            val tile = findFirst { n ->
                val l = labelOf(n)
                l.isNotEmpty() && labels.any { l.contains(it) }
            }
            val ok = tile?.let { clickNode(it) } ?: false
            main.postDelayed({ closeShade() }, 900)
            onResult(ok)
        }, 1100)
    }
}
