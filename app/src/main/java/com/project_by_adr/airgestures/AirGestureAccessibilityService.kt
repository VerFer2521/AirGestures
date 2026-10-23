package com.project_by_adr.airgestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Does things on request: swipe, tap, press the system's Back / Recents /
 * Notifications / Screenshot, and (new) click a button by its label and type
 * into a text box. It only looks at the screen at the moment you give a voice
 * command, and never stores or sends anything it sees.
 */
class AirGestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: AirGestureAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /** next = true swipes up (next reel); false swipes down (previous reel). */
        fun swipe(next: Boolean): Boolean = instance?.doSwipe(next) ?: false

        fun doubleTap(): Boolean = instance?.tapTwice() ?: false

        fun screenshot(): Boolean =
            instance?.global(GLOBAL_ACTION_TAKE_SCREENSHOT, Build.VERSION_CODES.P) ?: false

        fun back(): Boolean = instance?.global(GLOBAL_ACTION_BACK, 0) ?: false

        fun recents(): Boolean = instance?.global(GLOBAL_ACTION_RECENTS, 0) ?: false

        fun notifications(): Boolean = instance?.global(GLOBAL_ACTION_NOTIFICATIONS, 0) ?: false

        /** Clicks the best match for any of these words (button text or description). */
        fun clickLabel(vararg words: String): Boolean = instance?.doClickLabel(words.toList()) ?: false

        /** Types into the focused text box (or the first one). append = keep what's there. */
        fun typeText(text: String, append: Boolean): Boolean = instance?.doType(text, append) ?: false

        /** Presses the keyboard's Enter / Search key in the text box. Android 11 or newer. */
        fun submit(): Boolean = instance?.doSubmit() ?: false

        fun hasTextBox(): Boolean = instance?.findTextBox() != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // intentionally empty: we only look at the screen when you give a voice command
    }

    override fun onInterrupt() {}

    private fun global(action: Int, minSdk: Int): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) return false
        return performGlobalAction(action)
    }

    private fun doSwipe(next: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.30f
        val bottom = metrics.heightPixels * 0.72f

        val path = Path()
        if (next) {
            path.moveTo(x, bottom)
            path.lineTo(x, top)
        } else {
            path.moveTo(x, top)
            path.lineTo(x, bottom)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 220)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Two quick taps in the middle of the screen (on Instagram this likes the reel). */
    private fun tapTwice(): Boolean {
        val metrics = resources.displayMetrics
        val path = Path()
        path.moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.45f)

        val first = GestureDescription.StrokeDescription(path, 0, 40)
        val second = GestureDescription.StrokeDescription(path, 140, 40)
        val gesture = GestureDescription.Builder().addStroke(first).addStroke(second).build()
        return dispatchGesture(gesture, null, null)
    }

    // ------------------------------------------------ voice autopilot helpers

    /** Collects the views on screen (the first 800 is plenty). */
    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 800) {
            val node = queue.removeFirst()
            out.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return "$text $desc".trim().lowercase()
    }

    private fun doClickLabel(words: List<String>): Boolean {
        val wanted = words.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false

        val best = allNodes()
            .filter { node ->
                node.isVisibleToUser && !node.isEditable &&
                        labelOf(node).let { label -> wanted.any { label.contains(it) } }
            }
            .minByOrNull { labelOf(it).length }
            ?: return false

        return clickNode(best)
    }

    /** Clicks the view, or the closest clickable parent, or just taps its centre. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        val box = Rect()
        node.getBoundsInScreen(box)
        if (box.isEmpty) return false
        return tapAt(box.exactCenterX(), box.exactCenterY())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findTextBox(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    private fun doType(text: String, append: Boolean): Boolean {
        val box = findTextBox() ?: return false

        var before = ""
        if (append) {
            val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && box.isShowingHintText
            if (!showingHint) before = box.text?.toString() ?: ""
        }
        val gap = if (before.isNotEmpty() && !before.endsWith(" ")) " " else ""

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            before + gap + text
        )
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun doSubmit(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val box = findTextBox() ?: return false
        return box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
}