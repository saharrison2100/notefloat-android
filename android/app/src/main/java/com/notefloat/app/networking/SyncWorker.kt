package com.notefloat.app.networking

import android.content.Context
import androidx.work.*
import com.notefloat.app.storage.Prefs
import com.notefloat.app.storage.SecureStore
import java.util.concurrent.TimeUnit

/** Runs sync when a network is available. Scheduled after edits and periodically. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val secure = SecureStore(applicationContext)
        val prefs = Prefs(applicationContext)
        var ok = true

        if (prefs.syncEnabled) {
            val r = SyncEngine(applicationContext, secure, prefs).run()
            ok = r.ok || r.message.startsWith("Sync is") || r.message.startsWith("Sign in")
        }

        // Drive backup rides on the same schedule. It only runs while a valid token exists; an expired
        // one is renewed without a prompt when Google still remembers the consent, otherwise the user
        // is asked next time they open Google and Drive.
        if (prefs.driveEnabled) {
            val drive = DriveSync(applicationContext, secure, prefs)
            if (drive.driveToken() == null) {
                runCatching {
                    val t = GoogleAuth.authorize(applicationContext, GoogleAuth.DRIVE_SCOPES)
                    secure.googleDriveToken = t.accessToken
                    secure.googleDriveTokenExpiry = t.expiresAt
                }
            }
            if (drive.driveToken() != null) {
                val d = drive.sync()
                ok = ok && (d.ok || d.message.contains("expired"))
            }
        }

        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val ONE = "nf_sync_once"
        private const val PERIODIC = "nf_sync_periodic"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        fun syncSoon(c: Context) {
            val p = Prefs(c); if (!p.syncEnabled && !p.driveEnabled) return
            WorkManager.getInstance(c).enqueueUniqueWork(ONE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(p.syncWifiOnly))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        fun schedulePeriodic(c: Context) {
            val p = Prefs(c); val wm = WorkManager.getInstance(c)
            if (!p.syncEnabled && !p.driveEnabled) { wm.cancelUniqueWork(PERIODIC); return }
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints(p.syncWifiOnly)).build())
        }
    }
}
