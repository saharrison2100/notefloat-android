package com.notefloat.app.networking

import android.content.Context
import androidx.work.*
import com.notefloat.app.storage.Prefs
import com.notefloat.app.storage.SecureStore
import java.util.concurrent.TimeUnit

/** Runs sync when a network is available. Scheduled after edits and periodically. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = SyncEngine(applicationContext, SecureStore(applicationContext), Prefs(applicationContext)).run()
        return if (r.ok || r.message.startsWith("Sync is") || r.message.startsWith("Sign in")) Result.success() else Result.retry()
    }

    companion object {
        private const val ONE = "nf_sync_once"
        private const val PERIODIC = "nf_sync_periodic"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        fun syncSoon(c: Context) {
            val p = Prefs(c); if (!p.syncEnabled) return
            WorkManager.getInstance(c).enqueueUniqueWork(ONE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(p.syncWifiOnly))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        fun schedulePeriodic(c: Context) {
            val p = Prefs(c); val wm = WorkManager.getInstance(c)
            if (!p.syncEnabled) { wm.cancelUniqueWork(PERIODIC); return }
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints(p.syncWifiOnly)).build())
        }
    }
}
