package com.notefloat.app.networking

import android.content.Context
import com.notefloat.app.storage.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-first sync. Local Room is the source of truth while offline; every edit marks its row DIRTY.
 *
 * Protocol (one round trip, see documentation/API_SPECIFICATION.md):
 *   push dirty rows + cursor  ->  server stores/merges, returns changes since cursor + new cursor
 *
 * CONFLICT POLICY (documented in ARCHITECTURE.md, enforced here and on the server):
 *   1. Compare by updatedAt (last-writer-wins) ONLY when the other side has not changed since the
 *      last sync base (serverUpdatedAt). If just one side changed, that side wins. No conflict.
 *   2. If BOTH sides changed the same note since the last sync base, nothing is discarded:
 *      the version with the later updatedAt stays in place, the other is saved as a new note
 *      titled "<title> (conflict copy)" with conflictOf pointing at the original. The user sees both.
 *   3. Deletions are tombstones. An edit made after a delete on the other side resurrects the note
 *      rather than losing the edit.
 *   4. Non-note rows (categories, tags, reminders, clipboard, voice metadata) use last-writer-wins.
 */
class SyncEngine(private val context: Context, private val secure: SecureStore, private val prefs: Prefs, private val api: Api = Api(secure)) {

    data class Result(val ok: Boolean, val pushed: Int, val pulled: Int, val conflicts: Int, val message: String)

    private val db = AppDatabase.get(context)

    suspend fun run(): Result {
        if (!prefs.syncEnabled) return Result(false, 0, 0, 0, "Sync is turned off.")
        if (!secure.signedIn) return Result(false, 0, 0, 0, "Sign in to sync.")
        return try {
            val dirtyNotes = db.notes().pendingSync()
            val dirtyCats = db.categories().pendingSync()
            val dirtyTags = db.tags().pendingSync()
            val dirtyRem = db.reminders().pendingSync()
            val dirtyClip = db.clipboard().pendingSync()
            val dirtyVoice = db.voice().pendingSync()

            val req = JSONObject().put("action", "sync").put("cursor", prefs.serverCursor)
                .put("notes", JSONArray(dirtyNotes.map { noteJson(it) }))
                .put("categories", JSONArray(dirtyCats.map { catJson(it) }))
                .put("tags", JSONArray(dirtyTags.map { tagJson(it) }))
                .put("reminders", JSONArray(dirtyRem.map { remJson(it) }))
                .put("clipboard", JSONArray(dirtyClip.map { clipJson(it) }))
                .put("voice", JSONArray(dirtyVoice.map { voiceJson(it) }))
            val res = api.post("nfSync", req)

            var conflicts = 0
            var pulled = 0
            // push results: mark accepted rows synced, remember server timestamps
            val accepted = res.optJSONObject("accepted") ?: JSONObject()
            dirtyNotes.forEach { n ->
                val ts = accepted.optJSONObject("notes")?.optLong(n.id, -1) ?: -1
                if (ts >= 0) db.notes().upsert(n.copy(syncState = SyncState.SYNCED, serverUpdatedAt = ts))
            }
            dirtyCats.forEach { db.categories().upsert(it.copy(syncState = SyncState.SYNCED)) }
            dirtyTags.forEach { db.tags().upsertAll(listOf(it.copy(syncState = SyncState.SYNCED))) }
            dirtyRem.forEach { db.reminders().upsert(it.copy(syncState = SyncState.SYNCED)) }
            dirtyClip.forEach { db.clipboard().upsert(it.copy(syncState = SyncState.SYNCED)) }
            dirtyVoice.forEach { db.voice().upsert(it.copy(syncState = SyncState.SYNCED)) }

            // server-reported conflicts: server keeps its copy, we keep ours as a conflict copy
            val rejected = res.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until rejected.length()) {
                val o = rejected.getJSONObject(i)
                val mine = db.notes().get(o.getString("id")) ?: continue
                conflicts++
                val copy = mine.copy(id = newId(), title = (mine.title.ifBlank { "Untitled" }) + " (conflict copy)",
                    conflictOf = mine.id, syncState = SyncState.DIRTY, serverUpdatedAt = 0, updatedAt = System.currentTimeMillis())
                db.notes().upsert(copy)
                db.notes().upsert(parseNote(o.getJSONObject("server")).copy(syncState = SyncState.SYNCED))
            }

            // pull
            val changes = res.optJSONObject("changes") ?: JSONObject()
            changes.optJSONArray("categories")?.let { a -> db.categories().upsertAll(a.objs().map { parseCat(it) }) ; pulled += a.length() }
            changes.optJSONArray("tags")?.let { a -> db.tags().upsertAll(a.objs().map { parseTag(it) }); pulled += a.length() }
            changes.optJSONArray("reminders")?.let { a -> val rs = a.objs().map { parseRem(it) }; db.reminders().upsertAll(rs)
                rs.forEach { com.notefloat.app.reminders.ReminderScheduler.schedule(context, it) }; pulled += a.length() }
            changes.optJSONArray("clipboard")?.let { a -> db.clipboard().upsertAll(a.objs().map { parseClip(it) }); pulled += a.length() }
            changes.optJSONArray("voice")?.let { a ->
                // never overwrite a local file path with a server row; keep path if we already have the file
                a.objs().forEach { o -> val inc = parseVoice(o); val local = db.voice().get(inc.id)
                    db.voice().upsert(if (local != null) inc.copy(filePath = local.filePath, sizeBytes = local.sizeBytes) else inc) }
                pulled += a.length() }
            changes.optJSONArray("notes")?.let { a ->
                a.objs().forEach { o ->
                    val inc = parseNote(o).copy(syncState = SyncState.SYNCED)
                    val local = db.notes().get(inc.id)
                    when {
                        local == null -> db.notes().upsert(inc)
                        local.syncState == SyncState.SYNCED -> db.notes().upsert(inc)
                        // both changed since base: keep both. later updatedAt keeps the id, the other becomes a conflict copy.
                        else -> {
                            conflicts++
                            val keepLocal = local.updatedAt >= inc.updatedAt
                            val loser = if (keepLocal) inc else local
                            val winner = if (keepLocal) local else inc
                            db.notes().upsert(winner.copy(syncState = if (keepLocal) SyncState.DIRTY else SyncState.SYNCED))
                            db.notes().upsert(loser.copy(id = newId(), title = (loser.title.ifBlank { "Untitled" }) + " (conflict copy)",
                                conflictOf = winner.id, syncState = SyncState.DIRTY, serverUpdatedAt = 0, trashed = false))
                        }
                    }
                }
                pulled += a.length()
            }
            prefs.serverCursor = res.optLong("cursor", prefs.serverCursor)
            prefs.lastSyncAt = System.currentTimeMillis()
            val pushed = dirtyNotes.size + dirtyCats.size + dirtyTags.size + dirtyRem.size + dirtyClip.size + dirtyVoice.size
            log(true, pushed, pulled, conflicts, "OK")
            Result(true, pushed, pulled, conflicts, if (conflicts > 0) "Synced. $conflicts conflict(s) kept as copies." else "Synced.")
        } catch (e: Api.ApiException) { log(false, 0, 0, 0, e.message ?: "error"); Result(false, 0, 0, 0, e.message ?: "Sync failed") }
        catch (e: java.io.IOException) { log(false, 0, 0, 0, "offline"); Result(false, 0, 0, 0, "No connection. Changes are saved on this device and will sync later.") }
        catch (e: Exception) { log(false, 0, 0, 0, e.javaClass.simpleName); Result(false, 0, 0, 0, "Sync failed: ${e.message}") }
    }

    private suspend fun log(ok: Boolean, pushed: Int, pulled: Int, conflicts: Int, msg: String) {
        db.syncLogs().insert(SyncLogEntity(ok = ok, pushed = pushed, pulled = pulled, conflicts = conflicts, message = msg))
        db.syncLogs().trim(System.currentTimeMillis() - 30L * 86_400_000L)
    }

    // ---- JSON mapping ----
    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
    private fun noteJson(n: NoteEntity) = JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("categoryId", n.categoryId ?: JSONObject.NULL)
        .put("colorLabel", n.colorLabel).put("pinned", n.pinned).put("archived", n.archived).put("trashed", n.trashed).put("trashedAt", n.trashedAt)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt).put("baseServerUpdatedAt", n.serverUpdatedAt).put("conflictOf", n.conflictOf ?: JSONObject.NULL)
    private fun parseNote(o: JSONObject) = NoteEntity(id = o.getString("id"), title = o.optString("title"), body = o.optString("body"),
        categoryId = o.optString("categoryId").takeIf { it.isNotEmpty() && it != "null" }, colorLabel = o.optInt("colorLabel"), pinned = o.optBoolean("pinned"),
        archived = o.optBoolean("archived"), trashed = o.optBoolean("trashed"), trashedAt = o.optLong("trashedAt"), createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"), serverUpdatedAt = o.optLong("serverUpdatedAt", o.optLong("updatedAt")), syncState = SyncState.SYNCED,
        conflictOf = o.optString("conflictOf").takeIf { it.isNotEmpty() && it != "null" })
    private fun catJson(c: CategoryEntity) = JSONObject().put("id", c.id).put("name", c.name).put("colorLabel", c.colorLabel).put("deleted", c.deleted).put("updatedAt", c.updatedAt).put("createdAt", c.createdAt)
    private fun parseCat(o: JSONObject) = CategoryEntity(o.getString("id"), o.optString("name"), o.optInt("colorLabel"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"), SyncState.SYNCED)
    private fun tagJson(t: TagEntity) = JSONObject().put("id", t.id).put("name", t.name).put("updatedAt", t.updatedAt)
    private fun parseTag(o: JSONObject) = TagEntity(o.getString("id"), o.optString("name"), o.optLong("updatedAt"), SyncState.SYNCED)
    private fun remJson(r: ReminderEntity) = JSONObject().put("id", r.id).put("noteId", r.noteId ?: JSONObject.NULL).put("title", r.title).put("fireAt", r.fireAt).put("repeat", r.repeat)
        .put("completed", r.completed).put("completedAt", r.completedAt).put("snoozedUntil", r.snoozedUntil).put("deleted", r.deleted).put("updatedAt", r.updatedAt).put("createdAt", r.createdAt)
    private fun parseRem(o: JSONObject) = ReminderEntity(o.getString("id"), o.optString("noteId").takeIf { it.isNotEmpty() && it != "null" }, o.optString("title"), o.optLong("fireAt"), o.optString("repeat", "NONE"),
        o.optBoolean("completed"), o.optLong("completedAt"), o.optLong("snoozedUntil"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"), SyncState.SYNCED)
    private fun clipJson(c: ClipboardCaptureEntity) = JSONObject().put("id", c.id).put("text", c.text).put("savedAsNoteId", c.savedAsNoteId ?: JSONObject.NULL).put("createdAt", c.createdAt).put("deleted", c.deleted)
    private fun parseClip(o: JSONObject) = ClipboardCaptureEntity(o.getString("id"), o.optString("text"), o.optString("savedAsNoteId").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("createdAt"), o.optBoolean("deleted"), SyncState.SYNCED)
    /** Voice metadata only. Audio bytes are never uploaded unless the user enabled audio upload. */
    private fun voiceJson(v: VoiceRecordingEntity) = JSONObject().put("id", v.id).put("name", v.name).put("durationMs", v.durationMs).put("sizeBytes", v.sizeBytes)
        .put("noteId", v.noteId ?: JSONObject.NULL).put("transcript", v.transcript ?: JSONObject.NULL).put("deleted", v.deleted).put("updatedAt", v.updatedAt).put("createdAt", v.createdAt)
    private fun parseVoice(o: JSONObject) = VoiceRecordingEntity(o.getString("id"), o.optString("name"), "", o.optLong("durationMs"), o.optLong("sizeBytes"),
        o.optString("noteId").takeIf { it.isNotEmpty() && it != "null" }, o.optString("transcript").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"), SyncState.SYNCED)
}
