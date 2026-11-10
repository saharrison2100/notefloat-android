package com.notefloat.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.notefloat.app.R
import com.notefloat.app.storage.Prefs
import com.notefloat.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the system-wide overlay windows (handle + panel).
 * It is started only after the user has granted "Display over other apps" and stops itself if that
 * permission is revoked. The service never records audio or reads the clipboard on its own.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL = "nf_service"
        const val NOTIF_ID = 4201
        const val ACTION_START = "com.notefloat.app.floating.START"
        const val ACTION_STOP = "com.notefloat.app.floating.STOP"
        const val ACTION_TOGGLE_HANDLE = "com.notefloat.app.floating.TOGGLE_HANDLE"
        const val ACTION_QUICK_CAPTURE = "com.notefloat.app.floating.QUICK_CAPTURE"
        const val ACTION_SWITCH_EDGE = "com.notefloat.app.floating.SWITCH_EDGE"
        const val ACTION_REFRESH = "com.notefloat.app.floating.REFRESH"

        @Volatile var running = false

        fun canDraw(c: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

        fun start(c: Context, action: String = ACTION_START) {
            val i = Intent(c, OverlayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, OverlayService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private var handle: HandleController? = null
    private var panel: PanelController? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) { prefs.floatingEnabled = false; stopSelf(); return START_NOT_STICKY }
        if (!canDraw(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" to use the floating sidebar.", Toast.LENGTH_LONG).show()
            prefs.floatingEnabled = false; stopSelf(); return START_NOT_STICKY
        }
        running = true
        prefs.floatingEnabled = true
        ensureControllers()
        when (action) {
            ACTION_TOGGLE_HANDLE -> { if (prefs.handleHidden) showHandle() else hideHandle() }
            ACTION_QUICK_CAPTURE -> { showHandle(); panel?.show(PanelController.Tab.CAPTURE) }
            ACTION_SWITCH_EDGE -> { prefs.edge = if (prefs.edge == "LEFT") "RIGHT" else "LEFT"; handle?.reposition() }
            ACTION_REFRESH -> { handle?.rebuild(); panel?.hide() }
            else -> if (!prefs.handleHidden) showHandle()
        }
        promote() // refresh notification text
        return START_STICKY
    }

    private fun ensureControllers() {
        if (panel == null) panel = PanelController(this, prefs, onClose = { })
        if (handle == null) handle = HandleController(
            this, prefs,
            onTap = { panel?.toggle() },
            onDoubleTap = { if (prefs.doubleTap == "QUICK_CAPTURE") panel?.show(PanelController.Tab.CAPTURE) else panel?.toggle() },
            onHideRequested = { hideHandle() },
            onMoved = { panel?.reanchor() }
        )
    }

    private fun showHandle() { prefs.handleHidden = false; handle?.show() }
    private fun hideHandle() { panel?.hide(); handle?.hide(); prefs.handleHidden = true }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.hide(); handle?.reposition()
    }

    /** Called when the user removes the app from recents. The service keeps running by design. */
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        running = false
        panel?.destroy(); handle?.destroy(); panel = null; handle = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating sidebar status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the NoteFloat sidebar is available over other apps"
                    setShowBadge(false)
                })
            }
        }
    }

    private fun svc(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, OverlayService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hidden = prefs.handleHidden
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (hidden) "NoteFloat sidebar is hidden" else "NoteFloat sidebar is on")
            .setContentText(if (hidden) "Tap Show to bring the handle back" else "Tap the handle to capture a note over any app")
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (hidden) "Show" else "Hide", svc(ACTION_TOGGLE_HANDLE, 2))
            .addAction(0, "Quick note", svc(ACTION_QUICK_CAPTURE, 3))
            .addAction(0, "Stop", svc(ACTION_STOP, 4))
            .build()
    }

    private fun promote() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }
}
