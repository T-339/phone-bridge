package dev.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Essential Key (Nothing hardware button) -> talk to Claude.
 *
 * The key arrives as KEYCODE_UNKNOWN (code 0) with scan code 250, so it is matched on the scan code.
 * When the feature is off, nothing here touches the key and Essential Space works as normal.
 * When it is on, the key is consumed (so Essential Space should not open), and:
 *   - short press  -> open Claude
 *   - hold         -> open Claude and start voice input; letting go stops it
 */
class EssentialKey(private val svc: AccessibilityService) {

    companion object {
        const val SCAN_CODE = 250
        const val CLAUDE_PKG = "com.anthropic.claude"
        const val ESSENTIAL_PKG = "com.nothing.ntessentialrecorder"
        private const val HOLD_MS = 400L
        private const val SEGMENT_MS = 300L
        private val STOP_WORDS = Regex("(?i)(stop (speech|dictat|listen|record|voice))|^done$|^finish|end voice|stop$")
    }

    private val main = Handler(Looper.getMainLooper())
    private var downAt = 0L
    private var held = false            // key currently down
    private var holdStarted = false     // passed the hold threshold
    private var voiceActive = false     // we started voice input
    private var holdPoint: Pair<Float, Float>? = null
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var lastKeyAt = 0L

    private val holdRunnable = Runnable {
        holdStarted = true
        EventLog.add("essential  hold -> open Claude and start voice")
        openClaude()
        waitForClaude(0)
    }

    fun isEssentialKey(e: KeyEvent) = e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == SCAN_CODE

    /** Returns true to consume the key. Only called when the feature is on. */
    fun onKey(e: KeyEvent): Boolean {
        lastKeyAt = SystemClock.uptimeMillis()
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (!held) {
                held = true
                holdStarted = false
                voiceActive = false
                downAt = SystemClock.uptimeMillis()
                main.postDelayed(holdRunnable, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                held = false
                main.removeCallbacks(holdRunnable)
                if (!holdStarted) {
                    EventLog.add("essential  short press -> open Claude")
                    openClaude()
                } else {
                    EventLog.add("essential  released after ${SystemClock.uptimeMillis() - downAt} ms")
                    release()
                }
            }
        }
        return true
    }

    /** Essential Space opened anyway (the system handled the key before us): close it and go back to Claude. */
    fun onWindow(pkg: String?) {
        if (pkg != ESSENTIAL_PKG) return
        if (SystemClock.uptimeMillis() - lastKeyAt > 3000) return
        if (!Prefs.essentialCloseOverlay(svc)) {
            EventLog.add("essential  Essential Space still opened (auto-close is off)")
            return
        }
        EventLog.add("essential  Essential Space still opened -> closing it")
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        main.postDelayed({ openClaude() }, 250)
    }

    private fun openClaude() {
        val i = svc.packageManager.getLaunchIntentForPackage(CLAUDE_PKG)
        if (i == null) {
            EventLog.add("essential  Claude app not found")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { svc.startActivity(i) } catch (e: Exception) { EventLog.add("essential  couldn't open Claude: ${e.message}") }
    }

    /** Polls until Claude's input bar is on screen, then starts voice. */
    private fun waitForClaude(tries: Int) {
        if (!holdStarted) return
        val holdMode = Prefs.essentialVoiceMethod(svc) == Prefs.VOICE_HOLD_BOX
        // In hold-the-box mode there's no point starting once the key is already up.
        if (holdMode && !held) { EventLog.add("essential  let go before Claude was ready"); return }
        val root = claudeRoot()
        if (root != null) {
            if (holdMode) {
                val box = find(root) { n -> n.isEditable } ?: find(root) { n -> desc(n).contains("hold to talk", true) }
                if (box != null) { startHold(box); return }
            } else {
                val mic = find(root) { n -> desc(n).equals("Start speech input", true) }
                    ?: find(root) { n -> desc(n).contains("speech", true) || desc(n).contains("dictat", true) || desc(n).contains("voice", true) }
                if (mic != null) {
                    val ok = click(mic)
                    voiceActive = ok
                    EventLog.add("essential  tapped \"${desc(mic)}\" ok=$ok")
                    if (ok && !held) main.postDelayed({ release() }, 600)
                    return
                }
            }
        }
        if (tries >= 30) {
            EventLog.add("essential  gave up: Claude's reply box/mic not found")
            dumpButtons()
            return
        }
        main.postDelayed({ waitForClaude(tries + 1) }, 150)
    }

    // ---------- method 1: hold the reply box with a continuous touch ----------

    private fun startHold(box: AccessibilityNodeInfo) {
        val r = Rect().also { box.getBoundsInScreen(it) }
        val p = r.exactCenterX() to r.exactCenterY()
        holdPoint = p
        voiceActive = true
        EventLog.add("essential  holding reply box at ${p.first.toInt()},${p.second.toInt()}")
        val path = Path().apply { moveTo(p.first, p.second) }
        val s = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        dispatch(s)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        currentStroke = s
        val g = GestureDescription.Builder().addStroke(s).build()
        val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!s.willContinue()) return
                val p = holdPoint ?: return
                val path = Path().apply { moveTo(p.first, p.second) }
                // Keep the finger down while the key is held, lift it shortly after release.
                val next = if (held) s.continueStroke(path, 0, SEGMENT_MS, true)
                           else s.continueStroke(path, 0, 50, false)
                dispatch(next)
                if (!next.willContinue()) { voiceActive = false; EventLog.add("essential  lifted touch") }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                voiceActive = false
                EventLog.add("essential  hold gesture was cancelled")
            }
        }, null)
        if (!sent) { voiceActive = false; EventLog.add("essential  couldn't send hold gesture") }
    }

    // ---------- method 2: tap the mic, tap stop on release ----------

    private fun release() {
        if (Prefs.essentialVoiceMethod(svc) == Prefs.VOICE_HOLD_BOX) return // gesture loop lifts itself
        if (!voiceActive) return
        voiceActive = false
        main.postDelayed({
            val root = claudeRoot() ?: return@postDelayed
            val stop = find(root) { n -> val d = desc(n); d.isNotEmpty() && !d.contains("responding", true) && STOP_WORDS.containsMatchIn(d) }
            if (stop != null) {
                val ok = click(stop)
                EventLog.add("essential  tapped \"${desc(stop)}\" to stop voice ok=$ok")
            } else {
                EventLog.add("essential  no stop button found; buttons on screen:")
                dumpButtons()
            }
        }, 150)
    }

    // ---------- node helpers ----------

    private fun claudeRoot(): AccessibilityNodeInfo? =
        svc.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == CLAUDE_PKG } }
            ?: svc.rootInActiveWindow?.takeIf { it.packageName == CLAUDE_PKG }

    private fun desc(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text ?: "").toString()

    private fun find(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isVisibleToUser && pred(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), pred)?.let { return it }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        // Nothing clickable in the tree: tap its centre instead.
        val r = Rect().also { n.getBoundsInScreen(it) }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return svc.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
    }

    private fun dumpButtons() {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val d = desc(n)
            if ((n.isClickable || n.isEditable) && d.isNotEmpty()) out += "\"${d.take(40)}\""
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(claudeRoot() ?: svc.rootInActiveWindow)
        EventLog.add("essential  buttons: " + out.joinToString(", ").ifEmpty { "(none)" })
    }
}
