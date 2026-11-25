package dev.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Essential Key (Nothing hardware button) -> talk to Claude.
 *
 * The key arrives as KEYCODE_UNKNOWN (code 0) with scan code 250, so it is matched on the scan code.
 * When the feature is off, nothing here touches the key and Essential Space works as normal.
 * When it is on, the key is consumed (so Essential Space should not open), and:
 *   - hold                   -> open Claude and start voice input; letting go stops it
 *   - tap, then press again  -> left to Essential Space (with Nothing's "Activate with Single Tap Before Use" on)
 *   - single tap on its own  -> opens Claude once that 2 s window has passed
 */
class EssentialKey(private val svc: AccessibilityService) {

    companion object {
        const val SCAN_CODE = 250
        const val CLAUDE_PKG = "com.anthropic.claude"
        const val ESSENTIAL_PKG = "com.nothing.ntessentialrecorder"
        private const val HOLD_MS = 400L
        private const val ARM_MS = 2000L
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
    private var lastShortUpAt = 0L
    private var passThrough = false     // this press belongs to Essential Space (tap, then press again)
    private var toggleMode = false      // started from screen off: talk, then press again to stop
    private var swallowUp = false       // the press that stopped toggle mode

    private val shortPressRunnable = Runnable {
        EventLog.add("essential  short press -> open Claude")
        openClaude()
    }

    private val holdRunnable = Runnable {
        holdStarted = true
        EventLog.add("essential  hold -> open Claude and start voice")
        startVoiceFlow()
    }

    /** Wakes the screen if needed, then opens Claude and starts voice. */
    private fun startVoiceFlow() {
        val pm = svc.getSystemService(PowerManager::class.java)
        val km = svc.getSystemService(KeyguardManager::class.java)
        if (km.isDeviceLocked) {
            EventLog.add("essential  phone is locked (needs fingerprint/PIN) -> can't dictate")
            buzz(false)
            return
        }
        val delay = if (!pm.isInteractive || km.isKeyguardLocked) {
            EventLog.add("essential  waking the screen")
            svc.startActivity(Intent(svc, WakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            700L
        } else 0L
        main.postDelayed({ openClaude(); waitForClaude(0) }, delay)
    }

    fun isEssentialKey(e: KeyEvent) = e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == SCAN_CODE

    /** Returns true to consume the key. Only called when the feature is on. */
    fun onKey(e: KeyEvent): Boolean {
        lastKeyAt = SystemClock.uptimeMillis()
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (!held) {
                if (toggleMode && !isListening()) {
                    // Claude stopped listening by itself; treat this as a fresh press.
                    toggleMode = false
                    EventLog.add("essential  dictation had already stopped -> normal press")
                }
                if (toggleMode) {
                    // Started from screen off: this press stops the dictation.
                    toggleMode = false
                    swallowUp = true
                    EventLog.add("essential  press -> stop dictation")
                    stopVoice()
                    return true
                }
                held = true
                main.removeCallbacks(shortPressRunnable)
                // Nothing's "Activate with Single Tap Before Use": a press within 2 s of a tap opens Essential Space.
                // Leave that press to Essential Space and don't close it.
                passThrough = SystemClock.uptimeMillis() - lastShortUpAt < ARM_MS
                if (passThrough) {
                    EventLog.add("essential  second press within 2 s -> leaving it to Essential Space")
                    return true
                }
                holdStarted = false
                voiceActive = false
                downAt = SystemClock.uptimeMillis()
                main.postDelayed(holdRunnable, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                if (swallowUp) { swallowUp = false; return true }
                if (!held) {
                    // No key-down seen: the screen was off and the press went to waking it.
                    val dur = e.eventTime - e.downTime
                    EventLog.add("essential  key up without down (screen was off), held $dur ms")
                    if (dur >= HOLD_MS) {
                        EventLog.add("essential  screen-off hold -> wake, open Claude, talk, press again to stop")
                        toggleMode = true
                        holdStarted = true
                        passThrough = false
                        startVoiceFlow()
                    }
                    return true
                }
                held = false
                main.removeCallbacks(holdRunnable)
                if (passThrough) {
                    lastShortUpAt = 0L
                } else if (!holdStarted) {
                    // Wait until Nothing's 2 s window has passed, so tap-then-press can still reach Essential Space.
                    lastShortUpAt = SystemClock.uptimeMillis()
                    main.postDelayed(shortPressRunnable, ARM_MS)
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
        if (passThrough) return
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
        val holdMode = Prefs.essentialVoiceMethod(svc) == Prefs.VOICE_HOLD_BOX && !toggleMode
        // In hold-the-box mode there's no point starting once the key is already up.
        if (holdMode && !held && !toggleMode) { EventLog.add("essential  let go before Claude was ready"); return }
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
                    if (ok) waitForListening(0) else buzz(false)
                    if (ok && !held && !toggleMode) main.postDelayed({ release() }, 600)
                    return
                }
            }
        }
        if (tries >= 30) {
            EventLog.add("essential  gave up: Claude's reply box/mic not found")
            buzz(false)
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
        main.postDelayed({ if (voiceActive) buzz(true) }, 500)
    }

    // ---------- vibration: one buzz = listening, two = voice didn't start ----------

    /** Claude shows "Stop dictation" once it is actually listening. */
    private fun waitForListening(tries: Int) {
        val root = claudeRoot()
        val stop = root?.let { r -> find(r) { n -> val d = desc(n); !d.contains("responding", true) && STOP_WORDS.containsMatchIn(d) } }
        if (stop != null) {
            EventLog.add("essential  listening (\"${desc(stop)}\" showing) -> buzz")
            buzz(true)
            return
        }
        if (tries >= 60) { EventLog.add("essential  voice didn't seem to start"); buzz(false); toggleMode = false; return }
        main.postDelayed({ waitForListening(tries + 1) }, 75)
    }

    private fun buzz(ok: Boolean) {
        try {
            val v = svc.getSystemService(Vibrator::class.java) ?: return
            val effect = if (ok) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                         else VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            } else {
                @Suppress("DEPRECATION") v.vibrate(effect)
            }
        } catch (e: Exception) { EventLog.add("essential  couldn't vibrate: ${e.message}") }
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
        stopVoice()
    }

    private fun stopVoice() {
        if (!voiceActive) return
        voiceActive = false
        main.postDelayed({
            val root = claudeRoot() ?: return@postDelayed
            val stop = find(root) { n -> val d = desc(n); d.isNotEmpty() && !d.contains("responding", true) && STOP_WORDS.containsMatchIn(d) }
            if (stop != null) {
                val ok = click(stop)
                EventLog.add("essential  tapped \"${desc(stop)}\" to stop voice ok=$ok")
                if (ok && Prefs.essentialAutoSend(svc)) main.postDelayed({ finishDictation(0) }, 250)
            } else {
                EventLog.add("essential  no stop button found; buttons on screen:")
                dumpButtons()
            }
        }, 150)
    }

    // ---------- after dictation: send, new chat, cancel ----------

    private val NEW_CHAT = Regex("^\\s*new\\s+(chat|conversation|session)\\b[\\s,.:;!?-]*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CANCEL = Regex("^\\s*(cancel( that| it)?|scratch that|never ?mind|forget (it|that)|delete (it|that))[\\s.!]*$", RegexOption.IGNORE_CASE)

    private fun isListening(): Boolean =
        claudeRoot()?.let { r -> find(r) { n -> val d = desc(n); !d.contains("responding", true) && STOP_WORDS.containsMatchIn(d) } } != null

    private fun replyBox(root: AccessibilityNodeInfo) = find(root) { n -> n.isEditable }

    private fun boxText(box: AccessibilityNodeInfo): String =
        if (box.isShowingHintText) "" else (box.text ?: "").toString().trim()

    /** Waits for Claude to finish turning speech into text, then acts on it. */
    private fun finishDictation(tries: Int) {
        val root = claudeRoot()
        val stillListening = root?.let { r -> find(r) { n -> val d = desc(n); !d.contains("responding", true) && STOP_WORDS.containsMatchIn(d) } } != null
        val box = root?.let { replyBox(it) }
        val text = box?.let { boxText(it) } ?: ""
        if ((stillListening || text.isEmpty()) && tries < 25) {
            main.postDelayed({ finishDictation(tries + 1) }, 120)
            return
        }
        if (box == null || text.isEmpty()) { EventLog.add("essential  nothing to send"); return }
        handleText(root, box, text)
    }

    private fun handleText(root: AccessibilityNodeInfo, box: AccessibilityNodeInfo, text: String) {
        if (CANCEL.matches(text)) {
            setText(box, "")
            EventLog.add("essential  \"$text\" -> cleared, not sent")
            buzz(false)
            return
        }
        val m = NEW_CHAT.find(text)
        if (m != null) {
            val rest = m.groupValues[2].trim()
            EventLog.add("essential  \"new chat\" -> opening a new chat" + if (rest.isNotEmpty()) " and sending the rest" else ", then listening")
            setText(box, "")
            val newBtn = find(root) { n -> desc(n).equals("New session", true) || desc(n).equals("New chat", true) }
            if (newBtn == null) { EventLog.add("essential  New chat button not found"); dumpButtons(); buzz(false); return }
            click(newBtn)
            main.postDelayed({ afterNewChat(rest, 0) }, 600)
            return
        }
        send(root)
    }

    private fun afterNewChat(rest: String, tries: Int) {
        val root = claudeRoot()
        val box = root?.let { replyBox(it) }
        if (box == null) {
            if (tries < 20) main.postDelayed({ afterNewChat(rest, tries + 1) }, 150)
            else { EventLog.add("essential  new chat didn't open"); buzz(false) }
            return
        }
        if (rest.isNotEmpty()) {
            setText(box, rest)
            main.postDelayed({ claudeRoot()?.let { send(it) } }, 300)
        } else {
            // Listen again in the new chat; press the key once to stop and send.
            toggleMode = true
            holdStarted = true
            waitForClaude(0)
        }
    }

    private fun send(root: AccessibilityNodeInfo) {
        val btn = find(root) { n -> desc(n).startsWith("Send", true) }
        if (btn == null) { EventLog.add("essential  Send button not found"); dumpButtons(); buzz(false); return }
        val ok = click(btn)
        EventLog.add("essential  tapped \"${desc(btn)}\" ok=$ok")
    }

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    // ---------- node helpers ----------

    private fun claudeRoot(): AccessibilityNodeInfo? =
        (svc.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == CLAUDE_PKG } }
            ?: svc.rootInActiveWindow?.takeIf { it.packageName == CLAUDE_PKG })
            ?.also { try { it.refresh() } catch (_: Exception) {} }

    private fun desc(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text ?: "").toString()

    private fun find(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isVisibleToUser && pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try { c.refresh() } catch (_: Exception) {}
            find(c, pred)?.let { return it }
        }
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
