package dev.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Prefs {
    private const val FILE = "bridge"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun relayUrl(c: Context): String = sp(c).getString("relay_url", "") ?: ""
    fun setRelayUrl(c: Context, v: String) = sp(c).edit().putString("relay_url", v.trim()).apply()

    /** Package names Claude may not act in, one per line or comma separated. */
    fun blocklist(c: Context): Set<String> =
        (sp(c).getString("blocklist", "") ?: "")
            .split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun blocklistRaw(c: Context): String = sp(c).getString("blocklist", "") ?: ""
    fun setBlocklist(c: Context, v: String) = sp(c).edit().putString("blocklist", v).apply()

    // ---------- Essential Key ----------
    const val VOICE_TAP_MIC = 0
    const val VOICE_HOLD_BOX = 1

    /** Off = Essential Key works as normal (Essential Space). On = hold to talk to Claude. */
    fun essentialToClaude(c: Context): Boolean = sp(c).getBoolean("essential_claude", false)
    fun setEssentialToClaude(c: Context, v: Boolean) = sp(c).edit().putBoolean("essential_claude", v).apply()

    fun essentialVoiceMethod(c: Context): Int = sp(c).getInt("essential_voice", VOICE_TAP_MIC)
    fun setEssentialVoiceMethod(c: Context, v: Int) = sp(c).edit().putInt("essential_voice", v).apply()

    /** Tap Send when dictation stops ("new chat …" and "cancel" are handled by the app). */
    fun essentialAutoSend(c: Context): Boolean = sp(c).getBoolean("essential_send", true)
    fun setEssentialAutoSend(c: Context, v: Boolean) = sp(c).edit().putBoolean("essential_send", v).apply()

    /** If Essential Space still opens despite the key being taken, close it. */
    fun essentialCloseOverlay(c: Context): Boolean = sp(c).getBoolean("essential_close", true)
    fun setEssentialCloseOverlay(c: Context, v: Boolean) = sp(c).edit().putBoolean("essential_close", v).apply()
}

/** Notification button: switches the Essential Key between Claude and normal (Essential Space). */
class EssentialToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = !Prefs.essentialToClaude(context)
        Prefs.setEssentialToClaude(context, on)
        EventLog.add("essential  switched ${if (on) "to Claude" else "back to Essential Space"} from notification")
        BridgeService.instance?.refreshNotification()
    }
}

/** "Turn off" button in the notification: switches the accessibility service off completely. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeService.instance?.shutdown()
    }
}
