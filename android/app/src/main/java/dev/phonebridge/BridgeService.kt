package dev.phonebridge

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BridgeService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneBridge"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1

        @Volatile var instance: BridgeService? = null
        @Volatile var status: String = "Accessibility service is off"
            private set
        @Volatile var statusListener: ((String) -> Unit)? = null
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null
    private var connected = false
    private var stopped = false
    private var retryDelay = 2000L
    private val reconnectRunnable = Runnable { connect() }
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    lateinit var commands: Commands
        private set
    private val essential by lazy { EssentialKey(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        stopped = false
        commands = Commands(this)
        createChannel()
        watchNetwork()
        connect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            EventLog.add("window  ${event.packageName} / ${event.className}")
            if (Prefs.essentialToClaude(this)) essential.onWindow(event.packageName?.toString())
        }
    }

    /**
     * Logs every button press. Returns false (the press goes on to the system as normal) for everything
     * except the Essential Key while "Hold Essential Key to talk to Claude" is switched on.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "action=${event.action}"
        }
        EventLog.add("key     ${KeyEvent.keyCodeToString(event.keyCode)} (code ${event.keyCode}, scan ${event.scanCode}) " +
            "$action repeat=${event.repeatCount}${if (event.isLongPress) " LONG" else ""}")
        if (essential.isEssentialKey(event) && Prefs.essentialToClaude(this)) {
            return try { essential.onKey(event) } catch (e: Exception) {
                EventLog.add("essential  error: ${e.message}")
                false
            }
        }
        return false
    }

    fun refreshNotification() = main.post { if (!stopped) showNotification(status) }
    override fun onInterrupt() {}

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        stopped = true
        main.removeCallbacks(reconnectRunnable)
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        ws?.close(1000, "service stopped")
        ws = null
        connected = false
        instance = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        setStatus("Accessibility service is off", notify = false)
    }

    /** Kill switch: turns the accessibility service off entirely. */
    fun shutdown() {
        teardown()
        disableSelf()
    }

    /** Called from the settings screen after the URL changes. */
    fun reconnectNow() {
        main.post {
            main.removeCallbacks(reconnectRunnable)
            ws?.cancel()
            ws = null
            retryDelay = 2000L
            connect()
        }
    }

    private fun connect() {
        if (stopped) return
        val url = Prefs.relayUrl(this)
        if (url.isBlank()) {
            setStatus("No relay URL set – open the app")
            return
        }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            setStatus("Relay URL is not valid")
            return
        }
        setStatus("Connecting…")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                connected = true
                retryDelay = 2000L
                setStatus("Connected – Claude can use this phone")
                webSocket.send(JSONObject().put("type", "hello").put("info", commands.deviceInfo()).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                worker.execute {
                    val reply = handle(text)
                    webSocket.send(reply)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                Log.w(TAG, "socket failure", t)
                val why = when (response?.code) {
                    404 -> "relay says wrong secret (404)"
                    null -> t.message ?: t.javaClass.simpleName
                    else -> "HTTP ${response.code}"
                }
                scheduleReconnect("Disconnected: $why")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                scheduleReconnect("Relay closed the connection")
            }
        })
    }

    private fun scheduleReconnect(why: String) {
        connected = false
        if (stopped) return
        setStatus("$why – retrying in ${retryDelay / 1000}s")
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (!connected && !stopped) {
                        main.removeCallbacks(reconnectRunnable)
                        retryDelay = 2000L
                        main.postDelayed(reconnectRunnable, 1500)
                    }
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun handle(text: String): String {
        var id: Any = JSONObject.NULL
        return try {
            val msg = JSONObject(text)
            id = msg.opt("id") ?: JSONObject.NULL
            val tool = msg.getString("tool")
            val args = msg.optJSONObject("args") ?: JSONObject()
            setStatus("Claude: $tool", notify = true)
            val r = commands.run(tool, args)
            setStatus("Connected – Claude can use this phone", notify = true)
            JSONObject().put("id", id).put("ok", true)
                .put("text", r.text ?: JSONObject.NULL)
                .put("image", r.image ?: JSONObject.NULL)
                .put("mime", r.mime)
                .toString()
        } catch (e: Throwable) {
            Log.w(TAG, "command failed", e)
            JSONObject().put("id", id).put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    // ---------- status + notification ----------

    private fun setStatus(s: String, notify: Boolean = true) {
        status = s
        statusListener?.let { l -> main.post { l(s) } }
        if (notify && !stopped) showNotification(s)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Phone Bridge status", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun showNotification(text: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, StopReceiver::class.java), flags)
        val toggle = PendingIntent.getBroadcast(this, 2, Intent(this, EssentialToggleReceiver::class.java), flags)
        val keyLabel = if (Prefs.essentialToClaude(this)) "Essential Key: Claude" else "Essential Key: normal"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Phone Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, keyLabel, toggle).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
            // Notification permission not granted; the app still works.
        }
    }
}
