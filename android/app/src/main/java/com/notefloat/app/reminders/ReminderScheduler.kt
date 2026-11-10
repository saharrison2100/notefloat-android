package com.notefloat.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.notefloat.app.R
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.ReminderEntity
import com.notefloat.app.storage.SyncState
import com.notefloat.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Reminders use AlarmManager. Exact alarms are used only when the user has granted that access
 * (Android 12+); otherwise an inexact-but-allow-while-idle alarm is used, which may fire a few
 * minutes late under Doze. All active reminders are re-armed after reboot, app update and time change.
 */
object ReminderScheduler {
    const val CHANNEL = "nf_reminders"
    const val ACTION_FIRE = "com.notefloat.app.REMINDER_FIRE"
    const val ACTION_DONE = "com.notefloat.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.notefloat.app.REMINDER_SNOOZE"
    const val EXTRA_ID = "rid"
    const val SNOOZE_MS = 10 * 60_000L

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders attached to your notes"
                })
            }
        }
    }

    fun canScheduleExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pi(c: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, id.hashCode(), Intent(c, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun effectiveTime(r: ReminderEntity): Long = if (r.snoozedUntil > r.fireAt) r.snoozedUntil else r.fireAt

    fun schedule(c: Context, r: ReminderEntity) {
        if (r.completed || r.deleted) { cancel(c, r.id); return }
        val am = c.getSystemService(AlarmManager::class.java)
        val at = effectiveTime(r).coerceAtLeast(System.currentTimeMillis() + 1_000)
        val p = pi(c, r.id)
        try {
            if (canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(c: Context, id: String) { c.getSystemService(AlarmManager::class.java).cancel(pi(c, id)) }

    fun requestExactAlarmAccess(c: Context) {
        if (Build.VERSION.SDK_INT >= 31 && !canScheduleExact(c)) {
            c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    suspend fun create(c: Context, title: String, at: Long, repeat: String, noteId: String?): ReminderEntity {
        val r = ReminderEntity(title = title.ifBlank { "Reminder" }, fireAt = at, repeat = repeat, noteId = noteId)
        AppDatabase.get(c).reminders().upsert(r)
        schedule(c, r)
        return r
    }

    suspend fun complete(c: Context, id: String) {
        val dao = AppDatabase.get(c).reminders()
        val r = dao.get(id) ?: return
        cancel(c, id); NotificationManagerCompat.from(c).cancel(id.hashCode())
        val next = nextOccurrence(r)
        if (next != null) {
            // repeating: history keeps this occurrence, schedule continues from the next one
            val n = r.copy(fireAt = next, snoozedUntil = 0, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)
            dao.upsert(n); schedule(c, n)
        } else {
            dao.upsert(r.copy(completed = true, completedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY))
        }
    }

    suspend fun snooze(c: Context, id: String, ms: Long = SNOOZE_MS) {
        val dao = AppDatabase.get(c).reminders()
        val r = dao.get(id) ?: return
        val n = r.copy(snoozedUntil = System.currentTimeMillis() + ms, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)
        dao.upsert(n); NotificationManagerCompat.from(c).cancel(id.hashCode()); schedule(c, n)
    }

    suspend fun delete(c: Context, id: String) {
        val dao = AppDatabase.get(c).reminders()
        dao.get(id)?.let { cancel(c, id); dao.upsert(it.copy(deleted = true, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }

    fun nextOccurrence(r: ReminderEntity): Long? {
        if (r.repeat == "NONE") return null
        val cal = Calendar.getInstance().apply { timeInMillis = r.fireAt }
        val now = System.currentTimeMillis()
        var guard = 0
        do {
            when (r.repeat) {
                "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                "MONTHLY" -> cal.add(Calendar.MONTH, 1)
                else -> return null
            }
        } while (cal.timeInMillis <= now && ++guard < 1000)
        return cal.timeInMillis
    }

    suspend fun rescheduleAll(c: Context) {
        ensureChannel(c)
        val now = System.currentTimeMillis()
        val dao = AppDatabase.get(c).reminders()
        dao.allActive().forEach { r ->
            if (effectiveTime(r) < now - 60_000 && r.repeat != "NONE") {
                nextOccurrence(r)?.let { n -> val u = r.copy(fireAt = n, snoozedUntil = 0, syncState = SyncState.DIRTY); dao.upsert(u); schedule(c, u) }
            } else schedule(c, r)   // missed one-off reminders fire immediately (coerced to now+1s)
        }
    }

    fun notify(c: Context, r: ReminderEntity) {
        ensureChannel(c)
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        fun act(a: String, label: String, code: Int) = NotificationCompat.Action(
            0, label, PendingIntent.getBroadcast(
                c, r.id.hashCode() + code, Intent(c, ReminderReceiver::class.java).setAction(a).putExtra(EXTRA_ID, r.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val open = PendingIntent.getActivity(
            c, r.id.hashCode(), Intent(c, MainActivity::class.java).putExtra("noteId", r.noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(r.title).setContentText("NoteFloat reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open)
            .addAction(act(ACTION_DONE, "Done", 1)).addAction(act(ACTION_SNOOZE, "Snooze 10 min", 2)).build()
        NotificationManagerCompat.from(c).notify(r.id.hashCode(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ReminderScheduler.ACTION_FIRE -> {
                        val r = AppDatabase.get(app).reminders().get(intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return@launch)
                        if (r != null && !r.completed && !r.deleted) {
                            ReminderScheduler.notify(app, r)
                            // one-off stays active until the user taps Done; repeating advances immediately
                            ReminderScheduler.nextOccurrence(r)?.let { n ->
                                val u = r.copy(fireAt = n, snoozedUntil = 0, syncState = SyncState.DIRTY)
                                AppDatabase.get(app).reminders().upsert(u); ReminderScheduler.schedule(app, u)
                            }
                        }
                    }
                    ReminderScheduler.ACTION_DONE -> ReminderScheduler.complete(app, intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return@launch)
                    ReminderScheduler.ACTION_SNOOZE -> ReminderScheduler.snooze(app, intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return@launch)
                }
            } finally { pending.finish() }
        }
    }
}

/** Re-arms alarms after reboot, app update, or clock/timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { ReminderScheduler.rescheduleAll(app) } finally { pending.finish() }
        }
    }
}
