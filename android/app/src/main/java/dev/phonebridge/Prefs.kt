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
}

/** "Turn off" button in the notification: switches the accessibility service off completely. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeService.instance?.shutdown()
    }
}
