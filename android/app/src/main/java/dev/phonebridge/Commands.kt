package dev.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Result(val text: String?, val image: String? = null, val mime: String = "image/jpeg")

class Commands(private val svc: AccessibilityService) {

    /** Elements from the last get_screen call; the list index is the id Claude uses. */
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private val allowedWhenBlocked = setOf("phone_status", "press_key", "open_app", "list_apps", "recent_events")

    /** Tools that work fine with the screen off, so they don't wake the phone. */
    private val noWake = setOf("phone_status", "recent_events", "list_apps")

    fun run(tool: String, a: JSONObject): Result {
        if (tool !in noWake && !(tool == "press_key" && a.optString("key") == "lock_screen")) ensureAwake()
        val fg = foregroundPackage()
        if (fg != null && tool !in allowedWhenBlocked && fg in Prefs.blocklist(svc)) {
            throw IllegalStateException("$fg is on the blocked list in Phone Bridge, so Claude can't act in it. " +
                "Go home or open another app.")
        }
        return when (tool) {
            "phone_status" -> Result(deviceInfo().toString(2))
            "get_screen" -> Result(dumpScreen(a.optBoolean("all", false)))
            "screenshot" -> screenshot(a.optInt("max_size", 1000).coerceIn(200, 1600))
            "tap" -> tapAt(a.getDouble("x").toFloat(), a.getDouble("y").toFloat(), a.optBoolean("long", false))
            "tap_element" -> tapElement(a.getInt("id"), a.optBoolean("long", false))
            "swipe" -> swipe(a)
            "scroll" -> scroll(a.getString("direction"), if (a.has("id")) a.getInt("id") else null)
            "type_text" -> typeText(a.getString("text"), if (a.has("id")) a.getInt("id") else null,
                a.optBoolean("append", false), a.optBoolean("submit", false))
            "press_key" -> pressKey(a.getString("key"))
            "open_app" -> openApp(a.getString("name"))
            "list_apps" -> listApps(a.optString("filter", ""))
            "open_url" -> openUrl(a.getString("url"))
            "recent_events" -> Result(EventLog.dump(a.optBoolean("clear", false)))
            else -> throw IllegalArgumentException("Unknown tool $tool")
        }
    }

    // ---------- wake ----------

    /**
     * If the screen is off (or on the swipe screen), turn it on and dismiss the swipe screen.
     * If the phone is properly locked (needs PIN/fingerprint), say so instead of waking it in a pocket.
     */
    private fun ensureAwake() {
        val pm = svc.getSystemService(PowerManager::class.java)
        val km = svc.getSystemService(KeyguardManager::class.java)
        if (pm.isInteractive && !km.isKeyguardLocked) return
        if (km.isDeviceLocked) {
            throw IllegalStateException("The phone is locked (needs your fingerprint or PIN), so Claude can't use it. " +
                "Unlock it, or use \"Lock after screen timeout\" so turning the screen off doesn't lock it straight away.")
        }
        EventLog.add("wake    turning the screen on for Claude")
        val i = Intent(svc, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        svc.startActivity(i)
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until && (!pm.isInteractive || km.isKeyguardLocked)) Thread.sleep(100)
        Thread.sleep(400) // let the app behind redraw
        if (!pm.isInteractive) throw IllegalStateException("Couldn't turn the screen on.")
    }

    // ---------- info ----------

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        svc.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun foregroundPackage(): String? = try {
        svc.rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }

    fun deviceInfo(): JSONObject {
        val (w, h) = screenSize()
        val battery = svc.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("screen", "${w}x$h")
            .put("battery_percent", battery)
            .put("screen_on", svc.getSystemService(PowerManager::class.java).isInteractive)
            .put("locked", svc.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            .put("foreground_app", foregroundPackage() ?: "unknown")
    }

    // ---------- reading the screen ----------

    private fun dumpScreen(all: Boolean): String {
        nodes.clear()
        val (w, h) = screenSize()
        val sb = StringBuilder()
        sb.append("Screen ${w}x$h, foreground app: ${foregroundPackage() ?: "unknown"}\n")
        val windows = try { svc.windows.sortedByDescending { it.layer } } catch (_: Exception) { emptyList() }
        if (windows.isEmpty()) {
            val root = svc.rootInActiveWindow ?: return sb.append("(screen content not available)").toString()
            walk(root, 0, all, sb)
        } else {
            for (win in windows) {
                val root = win.root ?: continue
                sb.append("== ").append(windowType(win.type))
                win.title?.let { sb.append(" \"").append(it).append('"') }
                root.packageName?.let { sb.append(" (").append(it).append(')') }
                sb.append(" ==\n")
                walk(root, 0, all, sb)
            }
        }
        if (nodes.size >= MAX_NODES) sb.append("… (truncated at $MAX_NODES elements)\n")
        return sb.toString()
    }

    private fun windowType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        else -> "window"
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, all: Boolean, sb: StringBuilder) {
        if (nodes.size >= MAX_NODES) return
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.takeIf { it.isNotBlank() && !n.isShowingHintText }
        val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val hint = n.hintText?.toString()?.takeIf { it.isNotBlank() }
        val interesting = all || n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable ||
            n.isCheckable || text != null || desc != null
        var childDepth = depth
        if (interesting) {
            val id = nodes.size
            nodes.add(n)
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append(" ".repeat(min(depth, 10)))
            sb.append('[').append(id).append("] ")
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            text?.let { sb.append(" \"").append(clip(it)).append('"') }
            if (desc != null && desc != text) sb.append(" desc=\"").append(clip(desc)).append('"')
            hint?.let { sb.append(" hint=\"").append(clip(it)).append('"') }
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("click")
            if (n.isLongClickable) flags.add("long")
            if (n.isEditable) flags.add("edit")
            if (n.isScrollable) flags.add("scroll")
            if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
            if (n.isFocused) flags.add("focused")
            if (n.isSelected) flags.add("selected")
            if (!n.isEnabled) flags.add("disabled")
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" [").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append("]\n")
            childDepth = depth + 1
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, childDepth, all, sb)
        }
    }

    private fun clip(s: String) = s.replace('\n', ' ').let { if (it.length > 160) it.take(160) + "…" else it }

    private fun node(id: Int): AccessibilityNodeInfo {
        val n = nodes.getOrNull(id) ?: throw IllegalArgumentException("No element [$id]. Call get_screen again.")
        if (!n.refresh()) throw IllegalStateException("Element [$id] is gone from the screen. Call get_screen again.")
        return n
    }

    // ---------- screenshot ----------

    private fun screenshot(maxSize: Int): Result {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        var err: String? = null
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(s: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (e: Exception) {
                        err = "could not read screenshot: ${e.message}"
                    } finally {
                        s.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    err = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "too soon after the last screenshot, try again"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "this app blocks screenshots (secure window); use get_screen"
                        else -> "screenshot failed (code $errorCode)"
                    }
                    latch.countDown()
                }
            })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("screenshot timed out")
        err?.let { throw IllegalStateException(it) }
        val full = bmp ?: throw IllegalStateException("screenshot empty")
        val scale = min(1f, maxSize.toFloat() / max(full.width, full.height))
        val img = if (scale < 1f)
            Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true)
        else full
        val out = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val factor = full.width.toFloat() / img.width
        val text = "Screenshot ${img.width}x${img.height} of a ${full.width}x${full.height} screen. " +
            "Multiply image coordinates by ${"%.3f".format(factor)} to get screen coordinates for tap/swipe."
        if (img !== full) img.recycle()
        full.recycle()
        return Result(text, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    // ---------- gestures ----------

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun clampPoint(x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = screenSize()
        return x.coerceIn(0f, (w - 1).toFloat()) to y.coerceIn(0f, (h - 1).toFloat())
    }

    private fun tapAt(x: Float, y: Float, long: Boolean): Result {
        val (cx, cy) = clampPoint(x, y)
        val p = Path().apply { moveTo(cx, cy) }
        if (!gesture(p, if (long) 800 else 60)) throw IllegalStateException("tap was cancelled")
        return Result("${if (long) "Long-pressed" else "Tapped"} at ${cx.roundToInt()},${cy.roundToInt()}.")
    }

    private fun tapElement(id: Int, long: Boolean): Result {
        val n = node(id)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var target: AccessibilityNodeInfo? = n
        while (target != null && !(if (long) target.isLongClickable else target.isClickable)) target = target.parent
        if (target != null && target.performAction(action)) {
            return Result("${if (long) "Long-pressed" else "Tapped"} [$id].")
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tapAt(r.exactCenterX(), r.exactCenterY(), long)
    }

    private fun swipe(a: JSONObject): Result {
        val (x1, y1) = clampPoint(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat())
        val (x2, y2) = clampPoint(a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat())
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        if (!gesture(p, a.optLong("duration_ms", 300))) throw IllegalStateException("swipe was cancelled")
        return Result("Swiped.")
    }

    private fun scroll(dir: String, id: Int?): Result {
        if (id != null) {
            val n = node(id)
            val act = when (dir) {
                "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> throw IllegalArgumentException("direction must be up/down/left/right")
            }
            if (n.performAction(act)) return Result("Scrolled [$id] $dir.")
            // fall through to a gesture inside the element
            val r = Rect().also { n.getBoundsInScreen(it) }
            return Result(swipeIn(r, dir))
        }
        val (w, h) = screenSize()
        return Result(swipeIn(Rect(0, (h * 0.15).toInt(), w, (h * 0.85).toInt()), dir))
    }

    private fun swipeIn(r: Rect, dir: String): String {
        val cx = r.exactCenterX(); val cy = r.exactCenterY()
        val dx = r.width() * 0.3f; val dy = r.height() * 0.3f
        val p = Path()
        when (dir) {
            "down" -> { p.moveTo(cx, cy + dy); p.lineTo(cx, cy - dy) }   // finger moves up = content scrolls down
            "up" -> { p.moveTo(cx, cy - dy); p.lineTo(cx, cy + dy) }
            "right" -> { p.moveTo(cx + dx, cy); p.lineTo(cx - dx, cy) }
            "left" -> { p.moveTo(cx - dx, cy); p.lineTo(cx + dx, cy) }
            else -> throw IllegalArgumentException("direction must be up/down/left/right")
        }
        if (!gesture(p, 350)) throw IllegalStateException("scroll gesture was cancelled")
        return "Scrolled $dir."
    }

    // ---------- typing + keys ----------

    private fun focusedInput(): AccessibilityNodeInfo? {
        svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        try {
            for (w in svc.windows) w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        } catch (_: Exception) {}
        return null
    }

    private fun typeText(text: String, id: Int?, append: Boolean, submit: Boolean): Result {
        var n = if (id != null) node(id) else focusedInput()
            ?: throw IllegalStateException("No text field is focused. Tap one first or pass its id.")
        if (!n.isEditable) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            n = focusedInput()?.takeIf { it.isEditable }
                ?: throw IllegalStateException("That element is not a text field.")
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (append && !n.isShowingHintText) n.text?.toString() ?: "" else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw IllegalStateException("The field did not accept the text.")
        }
        var msg = "Typed ${text.length} characters."
        if (submit) {
            Thread.sleep(150)
            n.refresh()
            msg += if (n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) " Pressed enter."
            else " Enter key not supported here; tap the send/search button instead."
        }
        return Result(msg)
    }

    private fun pressKey(key: String): Result {
        if (key == "enter") {
            val n = focusedInput() ?: throw IllegalStateException("No text field is focused.")
            if (!n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) {
                throw IllegalStateException("Enter key not supported here; tap the send/search button instead.")
            }
            return Result("Pressed enter.")
        }
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> throw IllegalArgumentException("Unknown key $key")
        }
        if (!svc.performGlobalAction(action)) throw IllegalStateException("$key did not work")
        return Result("Pressed $key.")
    }

    // ---------- apps + links ----------

    private data class App(val label: String, val pkg: String)

    private fun apps(): List<App> {
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun listApps(filter: String): Result {
        val f = filter.lowercase()
        val list = apps().filter { f.isEmpty() || it.label.lowercase().contains(f) || it.pkg.lowercase().contains(f) }
        return Result(list.joinToString("\n") { "${it.label} — ${it.pkg}" }.ifEmpty { "No matching apps." })
    }

    private fun openApp(name: String): Result {
        val q = name.trim().lowercase()
        val all = apps()
        val app = all.firstOrNull { it.pkg.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: all.firstOrNull { it.label.lowercase().contains(q) }
            ?: throw IllegalArgumentException("No app called \"$name\". Use list_apps to see what is installed.")
        if (app.pkg in Prefs.blocklist(svc)) throw IllegalStateException("${app.label} is on the blocked list.")
        val launch = svc.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: throw IllegalStateException("${app.label} can't be launched.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        svc.startActivity(launch)
        return Result("Opened ${app.label} (${app.pkg}).")
    }

    private fun openUrl(url: String): Result {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No app can open $url")
        }
        return Result("Opened $url.")
    }

    companion object {
        private const val MAX_NODES = 350
    }
}
