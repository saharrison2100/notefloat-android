package com.notefloat.app.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.reminders.ReminderScheduler
import com.notefloat.app.storage.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteFloatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        applyTheme(prefs.theme)
        ReminderScheduler.ensureChannel(this)
        CoroutineScope(Dispatchers.IO).launch { ReminderScheduler.rescheduleAll(this@NoteFloatApp) }
        SyncWorker.schedulePeriodic(this)
    }

    companion object {
        fun applyTheme(t: String) = AppCompatDelegate.setDefaultNightMode(
            when (t) { "LIGHT" -> AppCompatDelegate.MODE_NIGHT_NO; "DARK" -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
    }
}
