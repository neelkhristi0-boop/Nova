package com.laughterlabs.nova

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The "hands" of Nova. Once you switch it on in Settings > Accessibility,
 * Nova can see what's on screen and tap, type, scroll and navigate in ANY app.
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NovaAccessibilityService? = null
            private set
        @Volatile private var whatsappSendUntil = 0L

        /** After Nova opens a WhatsApp chat with text filled in, press Send automatically for 10 seconds. */
        fun armWhatsAppSend() { whatsappSendUntil = System.currentTimeMillis() + 10_000 }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (System.currentTimeMillis() < whatsappSendUntil && pkg.startsWith("com.whatsapp")) {
            if (pressWhatsAppSend(pkg)) whatsappSendUntil = 0
        }
    }

    private fun pressWhatsAppSend(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser }
        if (byId != null && click(byId)) return true
        val byDesc = root.findAccessibilityNodeInfosByText("Send")
            .firstOrNull { it.isVisibleToUser && it.contentDescription?.toString().equals("Send", ignoreCase = true) }
        return byDesc != null && click(byDesc)
    }

    // ---------------------------------------------------------------- public abilities

    /** "tap Send", "click Login": finds the text on screen and taps it. */
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val best = matches.firstOrNull {
            it.text?.toString().equals(text, true) || it.contentDescription?.toString().equals(text, true)
        } ?: matches.firstOrNull() ?: return false
        return click(best)
    }

    /** Types into the focused text box (or the first box on screen). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirst(root) { it.isEditable } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Swipes the screen like a finger would. */
    fun scroll(down: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * if (down) 0.75f else 0.30f
        val to = dm.heightPixels * if (down) 0.30f else 0.75f
        swipe(x, from, x, to)
    }

    /** All readable text on the current screen, for "read the screen". */
    fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.length > 500) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank() && n.isVisibleToUser) out.append(t.trim()).append(". ")
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out.toString().take(500)
    }

    // ---------------------------------------------------------------- low level

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        // Nothing clickable in the tree: tap the exact spot on screen instead.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        tap(r.exactCenterX(), r.exactCenterY())
        return true
    }

    private fun findFirst(n: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (test(n)) return n
        for (i in 0 until n.childCount) findFirst(n.getChild(i), test)?.let { return it }
        return null
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 320)).build(), null, null)
    }
}
