package dev.phonebridge

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory log of button presses and screen changes, readable by Claude via recent_events. */
object EventLog {
    private const val MAX = 120
    private val items = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    @Synchronized
    fun add(line: String) {
        if (items.size >= MAX) items.removeFirst()
        items.addLast(fmt.format(Date()) + "  " + line)
    }

    @Synchronized
    fun dump(clear: Boolean): String {
        val out = if (items.isEmpty()) "(no events yet)" else items.joinToString("\n")
        if (clear) items.clear()
        return out
    }
}
