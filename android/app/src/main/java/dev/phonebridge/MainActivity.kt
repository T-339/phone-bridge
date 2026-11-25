package dev.phonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var urlField: EditText
    private lateinit var blockField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 4)
        }.also { col.addView(it) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        label("Phone Bridge", 24f, true)
        statusView = label("", 16f)

        label("1. Relay URL (from the Pi installer, starts with wss://)", bold = true)
        urlField = EditText(this).apply {
            setText(Prefs.relayUrl(this@MainActivity))
            hint = "wss://your-pi.tailnet.ts.net/phone/…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        col.addView(urlField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        label("Blocked apps (package names, comma separated – Claude can't act in these)", bold = true)
        blockField = EditText(this).apply {
            setText(Prefs.blocklistRaw(this@MainActivity))
            hint = "e.g. com.monzo.android, com.google.android.apps.walletnfcrel"
            minLines = 2
        }
        col.addView(blockField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        button("Save and connect") { save() }

        label("2. Turn on the accessibility service", bold = true)
        button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        label("If the switch is greyed out (\"Restricted setting\"): open App info below, tap ⋮ at the top right, " +
            "choose \"Allow restricted settings\", then try again.")
        button("Open App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        label("3. Keep it connected in the background", bold = true)
        button("Allow running without battery limits") { askBatteryExemption() }

        label("Turn it off any time with \"Turn off\" in the Phone Bridge notification.")

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        showStatus(BridgeService.status)
        BridgeService.statusListener = { showStatus(it) }
    }

    override fun onPause() {
        BridgeService.statusListener = null
        super.onPause()
    }

    private fun showStatus(s: String) {
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        statusView.text = "Status: $s" + if (battery) "" else "\n⚠ Battery limits are on – it may disconnect in the background."
    }

    private fun save() {
        val url = urlField.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("wss://") && !url.startsWith("ws://")) {
            Toast.makeText(this, "The relay URL should start with wss://", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setRelayUrl(this, url)
        Prefs.setBlocklist(this, blockField.text.toString())
        val svc = BridgeService.instance
        if (svc == null) {
            Toast.makeText(this, "Saved. Now turn on the accessibility service (step 2).", Toast.LENGTH_LONG).show()
        } else {
            svc.reconnectNow()
            Toast.makeText(this, "Saved, reconnecting…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
