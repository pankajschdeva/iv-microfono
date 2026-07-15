package com.nova.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The "hands" of the assistant. Once the user turns this on in
 * Settings > Accessibility, it can tap, scroll, type and press system
 * buttons inside ANY app. This is the legal, Google-sanctioned way for an
 * assistant to act on other apps (the same mechanism automation apps use).
 *
 * It cannot: block uninstall, disable touch, or "take over" the phone.
 * Those are intentionally impossible for normal apps.
 */
class ActionService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var autoScroll = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        autoScroll = false
        super.onDestroy()
    }

    // ---- Basic system actions ----
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Tap a point on screen ----
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ---- Swipe / scroll ----
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun scrollDown() {
        val h = resources.displayMetrics.heightPixels.toFloat()
        val w = resources.displayMetrics.widthPixels.toFloat()
        swipe(w / 2, h * 0.75f, w / 2, h * 0.25f, 350)
    }

    fun scrollUp() {
        val h = resources.displayMetrics.heightPixels.toFloat()
        val w = resources.displayMetrics.widthPixels.toFloat()
        swipe(w / 2, h * 0.25f, w / 2, h * 0.75f, 350)
    }

    // ---- Auto-scroll loop (e.g. "reels scroll karte raho") ----
    fun startAutoScroll(everyMs: Long = 15000) {
        autoScroll = true
        val runnable = object : Runnable {
            override fun run() {
                if (!autoScroll) return
                scrollDown()
                handler.postDelayed(this, everyMs)
            }
        }
        handler.postDelayed(runnable, everyMs)
    }

    fun stopAutoScroll() { autoScroll = false }

    // ---- Find a node by visible text and click it ----
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findByText(root, text) ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = node.findAccessibilityNodeInfosByText(text)
        if (!matches.isNullOrEmpty()) return matches[0]
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByText(child, text)
            if (found != null) return found
        }
        return null
    }

    // ---- Type into the currently focused text field ----
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findEditable(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val f = findEditable(child)
            if (f != null) return f
        }
        return null
    }

    companion object {
        @Volatile var instance: ActionService? = null
        fun isRunning() = instance != null
    }
}
