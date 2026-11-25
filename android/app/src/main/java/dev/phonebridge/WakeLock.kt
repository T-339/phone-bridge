package dev.phonebridge

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Invisible activity that turns the screen on and dismisses a non-secure keyguard (swipe screen).
 * Only started when the phone is NOT locked with a PIN/fingerprint, so it never pops up an unlock prompt.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked && !km.isDeviceLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = done()
                override fun onDismissCancelled() = done()
                override fun onDismissError() = done()
            })
        } else {
            window.decorView.postDelayed({ done() }, 150)
        }
    }

    private fun done() {
        if (!isFinishing) finish()
        overridePendingTransition(0, 0)
    }
}

/** Locks the phone straight away (secure lock), like the power button used to. */
object LockNow {
    fun lock(c: Context) {
        val svc = BridgeService.instance
        if (svc == null) {
            Toast.makeText(c, "Phone Bridge's accessibility service is off, so it can't lock the phone.", Toast.LENGTH_LONG).show()
            return
        }
        EventLog.add("lock    locked from Phone Bridge")
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }
}

/** "Lock" button in the notification. */
class LockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LockNow.lock(context)
}

/** "Lock phone" Quick Settings tile. */
class LockTileService : TileService() {
    override fun onClick() = LockNow.lock(this)
}
