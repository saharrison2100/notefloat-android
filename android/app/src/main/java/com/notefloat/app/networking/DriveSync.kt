package com.notefloat.app.networking

import android.content.Context
import com.notefloat.app.reminders.ReminderScheduler
import com.notefloat.app.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Backup and merge of every note, category, tag, reminder and clipboard entry into the user's own
 * Google Drive, inside the hidden app data folder.
 *
 * Drive is a second, independent copy. It does not replace the account sync in nfSync: with both on,
 * the device pushes to Drive and to the Base44 account, and merging follows the same never-discard
 * rules. With neither on, everything stays local.
 */
class DriveSync(
    private val context: Context,
    private val secure: SecureStore,
    private val prefs: Prefs,
) {
    data class Result(val ok: Boolean, val message: String, val pulled: Int = 0, val pushed: Int = 0, val conflicts: Int = 0)

    private val db = AppDatabase.get(context)

    /** Two-way: read the Drive copy, merge, write the merged copy back. */
    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        if (!prefs.driveEnabled) return@withContext Result(false, "Drive backup is off.")
        val token = driveToken() ?: return@withContext Result(false, "Google Drive needs to be connected again.")
        try {
            val client = DriveClient(token)
            val local = readLocal()
            val existing = client.find(SnapshotCodec.FILE_NAME)
            val remote = if (existing == null) DriveMerge.Snapshot() else runCatching {
                SnapshotCodec.fromJson(client.download(existing.id))
            }.getOrElse { DriveMerge.Snapshot() }

            val merged = DriveMerge.merge(local, remote)
            writeLocal(merged.snapshot)

            val payload = SnapshotCodec.toJson(merged.snapshot)
            if (existing == null) client.create(SnapshotCodec.FILE_NAME, payload) else client.update(existing.id, payload)

            prefs.driveLastSyncAt = System.currentTimeMillis()
            val pulled = remote.size
            Result(
                ok = true,
                message = if (merged.conflicts > 0) "Backed up. ${merged.conflicts} conflict(s) kept as copies." else "Backed up to Drive.",
                pulled = pulled,
                pushed = merged.snapshot.size,
                conflicts = merged.conflicts,
            )
        } catch (e: DriveClient.DriveException) {
            if (e.code == 401) {
                secure.googleDriveToken = null
                Result(false, "Google Drive access expired. Tap Connect Google Drive to renew it.")
            } else Result(false, "Drive backup failed: ${e.message}")
        } catch (e: java.io.IOException) {
            Result(false, "No connection. Your notes are safe on this device and will back up next time.")
        } catch (e: Exception) {
            Result(false, "Drive backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** One-way: force the device's copy up to Drive, ignoring what is already there. */
    suspend fun backupNow(): Result = withContext(Dispatchers.IO) {
        val token = driveToken() ?: return@withContext Result(false, "Google Drive needs to be connected again.")
        try {
            val client = DriveClient(token)
            val local = readLocal()
            val payload = SnapshotCodec.toJson(local)
            val existing = client.find(SnapshotCodec.FILE_NAME)
            if (existing == null) client.create(SnapshotCodec.FILE_NAME, payload) else client.update(existing.id, payload)
            prefs.driveLastSyncAt = System.currentTimeMillis()
            Result(true, "Backed up ${local.notes.size} notes to Drive.", pushed = local.size)
        } catch (e: DriveClient.DriveException) {
            if (e.code == 401) { secure.googleDriveToken = null; Result(false, "Google Drive access expired. Tap Connect Google Drive to renew it.") }
            else Result(false, "Backup failed: ${e.message}")
        } catch (e: Exception) {
            Result(false, "Backup failed: ${e.message ?: "no connection"}")
        }
    }

    /** One-way: pull the Drive copy into this device, keeping local edits as conflict copies. */
    suspend fun restore(): Result = withContext(Dispatchers.IO) {
        val token = driveToken() ?: return@withContext Result(false, "Google Drive needs to be connected again.")
        try {
            val client = DriveClient(token)
            val existing = client.find(SnapshotCodec.FILE_NAME)
                ?: return@withContext Result(false, "No backup found in this Google account yet.")
            val remote = SnapshotCodec.fromJson(client.download(existing.id))
            if (remote.isEmpty) return@withContext Result(false, "The Drive backup is empty.")
            val merged = DriveMerge.merge(readLocal(), remote)
            writeLocal(merged.snapshot)
            prefs.driveLastSyncAt = System.currentTimeMillis()
            Result(
                ok = true,
                message = "Restored ${remote.notes.size} notes from Drive." + if (merged.conflicts > 0) " ${merged.conflicts} local edit(s) kept as copies." else "",
                pulled = remote.size,
                conflicts = merged.conflicts,
            )
        } catch (e: DriveClient.DriveException) {
            if (e.code == 401) { secure.googleDriveToken = null; Result(false, "Google Drive access expired. Tap Connect Google Drive to renew it.") }
            else Result(false, "Restore failed: ${e.message}")
        } catch (e: Exception) {
            Result(false, "Restore failed: ${e.message ?: "no connection"}")
        }
    }

    /** A valid Drive token, or null when the user has to re-consent. */
    fun driveToken(): String? {
        val t = secure.googleDriveToken
        val exp = secure.googleDriveTokenExpiry
        return t?.takeIf { exp == 0L || exp > System.currentTimeMillis() }
    }

    private suspend fun readLocal(): DriveMerge.Snapshot = DriveMerge.Snapshot(
        notes = db.notes().recent(100_000) + db.notes().observeArchived().first() + db.notes().observeTrash().first(),
        categories = db.categories().all(),
        tags = db.tags().observe().first(),
        reminders = db.reminders().allActive() + db.reminders().observeHistory().first(),
        clipboard = db.clipboard().observe().first(),
        voice = db.voice().observe().first(),
    )

    private suspend fun writeLocal(s: DriveMerge.Snapshot) {
        if (s.notes.isNotEmpty()) db.notes().upsertAll(s.notes.distinctBy { it.id })
        if (s.categories.isNotEmpty()) db.categories().upsertAll(s.categories.distinctBy { it.id })
        if (s.tags.isNotEmpty()) db.tags().upsertAll(s.tags.distinctBy { it.id })
        if (s.reminders.isNotEmpty()) db.reminders().upsertAll(s.reminders.distinctBy { it.id })
        if (s.clipboard.isNotEmpty()) db.clipboard().upsertAll(s.clipboard.distinctBy { it.id })
        if (s.voice.isNotEmpty()) db.voice().upsertAll(s.voice.filter { it.filePath.isNotEmpty() }.distinctBy { it.id })
        ReminderScheduler.rescheduleAll(context)
    }
}
