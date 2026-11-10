package com.notefloat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.reminders.ReminderScheduler
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.ReminderEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** Screen 6: Reminders, with upcoming, history, snooze, repeat and complete. */
class RemindersFragment : Fragment() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) android.widget.Toast.makeText(requireContext(), "Without notification permission, reminders cannot alert you.", android.widget.Toast.LENGTH_LONG).show()
    }
    private val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs); val db = AppDatabase.get(requireContext())
        val col = ui.column(); col.addView(ui.title("Reminders"))

        if (Build.VERSION.SDK_INT >= 33 && requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            col.addView(ui.body("Allow notifications so reminders can alert you."))
            col.addView(ui.button("Allow notifications") { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }); col.addView(ui.spacer(8f))
        }
        if (!ReminderScheduler.canScheduleExact(requireContext())) {
            col.addView(ui.body("For on-the-minute alerts, allow exact alarms. Without it, reminders still fire but can be a few minutes late."))
            col.addView(ui.button("Allow exact alarms", false) { ReminderScheduler.requestExactAlarmAccess(requireContext()) }); col.addView(ui.spacer(8f))
        }
        val battery = requireContext().getSystemService(android.os.PowerManager::class.java)
        if (!battery.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            col.addView(ui.body("Some phones stop background apps aggressively. If reminders or the sidebar disappear, exclude NoteFloat from battery optimisation."))
            col.addView(ui.button("Battery settings", false) { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }); col.addView(ui.spacer(8f))
        }

        col.addView(ui.section("New reminder"))
        val title = ui.edit("Reminder title"); col.addView(title)
        var at = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        var rep = "NONE"
        val whenTv = ui.tv("", 14f, false, ui.sub).apply { setPadding(0, ui.dp(8f), 0, ui.dp(4f)) }
        fun paint() { whenTv.text = fmt.format(at.time) + "   repeat: " + rep.lowercase() }
        paint(); col.addView(whenTv)
        col.addView(ui.group(
            ui.tapRow("Date", null) { android.app.DatePickerDialog(requireContext(), { _, y, mo, d -> at.set(y, mo, d); paint() }, at.get(Calendar.YEAR), at.get(Calendar.MONTH), at.get(Calendar.DAY_OF_MONTH)).show() },
            ui.tapRow("Time", null) { android.app.TimePickerDialog(requireContext(), { _, h, mi -> at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, mi); paint() }, at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), true).show() },
            ui.tapRow("Repeat", null) { rep = when (rep) { "NONE" -> "DAILY"; "DAILY" -> "WEEKLY"; "WEEKLY" -> "MONTHLY"; else -> "NONE" }; paint() },
        ))
        col.addView(ui.spacer(8f))
        col.addView(ui.button("Set reminder") {
            val t = title.text.toString().trim()
            if (t.isEmpty()) { android.widget.Toast.makeText(requireContext(), "Add a title first.", android.widget.Toast.LENGTH_SHORT).show(); return@button }
            if (at.timeInMillis < System.currentTimeMillis()) { android.widget.Toast.makeText(requireContext(), "Pick a time in the future.", android.widget.Toast.LENGTH_SHORT).show(); return@button }
            m.lifecycleScope.launch { ReminderScheduler.create(requireContext().applicationContext, t, at.timeInMillis, rep, null); title.setText(""); SyncWorker.syncSoon(requireContext().applicationContext) }
        })

        col.addView(ui.section("Upcoming")); val up = ui.column(); col.addView(up)
        col.addView(ui.section("History")); val hist = ui.column(); col.addView(hist)
        viewLifecycleOwner.lifecycleScope.launch { db.reminders().observeUpcoming().collect { l -> up.removeAllViews(); if (l.isEmpty()) up.addView(ui.body("Nothing scheduled.")); l.forEach { up.addView(row(ui, m, it, false)) } } }
        viewLifecycleOwner.lifecycleScope.launch { db.reminders().observeHistory().collect { l -> hist.removeAllViews(); if (l.isEmpty()) hist.addView(ui.body("Completed reminders appear here.")); l.take(30).forEach { hist.addView(row(ui, m, it, true)) } } }
        return ui.scroll(col)
    }

    private fun row(ui: Ui, m: MainActivity, r: ReminderEntity, done: Boolean): View {
        val c = ui.column().apply { background = ui.card(); setPadding(ui.dp(16f), ui.dp(12f), ui.dp(16f), ui.dp(12f))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8f) } }
        c.addView(ui.tv(r.title, 15f, true))
        val snoozed = if (r.snoozedUntil > r.fireAt) "  (snoozed)" else ""
        c.addView(ui.tv(fmt.format(Date(ReminderScheduler.effectiveTime(r))) + (if (r.repeat != "NONE") "   repeats ${r.repeat.lowercase()}" else "") + snoozed, 12f, false, ui.sub))
        val a = android.widget.LinearLayout(requireContext()).apply { setPadding(0, ui.dp(8f), 0, 0) }
        if (!done) {
            a.addView(ui.button("Done", false) { m.lifecycleScope.launch { ReminderScheduler.complete(requireContext().applicationContext, r.id); SyncWorker.syncSoon(requireContext().applicationContext) } }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6f) })
            a.addView(ui.button("Snooze 10m", false) { m.lifecycleScope.launch { ReminderScheduler.snooze(requireContext().applicationContext, r.id); SyncWorker.syncSoon(requireContext().applicationContext) } }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6f) })
        }
        a.addView(ui.button("Delete", false) { m.lifecycleScope.launch { ReminderScheduler.delete(requireContext().applicationContext, r.id); SyncWorker.syncSoon(requireContext().applicationContext) } }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(a); return c
    }
}
