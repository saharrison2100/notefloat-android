package com.notefloat.app.networking

import com.notefloat.app.storage.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Merge rules for Drive backup, and the JSON shape of the snapshot file.
 *
 * Pure Kotlin with no Android dependencies, so the conflict behaviour is covered by unit tests
 * (DriveMergeTest) instead of only by tapping around on a phone.
 *
 * Policy, identical in spirit to the Base44 sync engine:
 *   1. Same id on both sides and identical content: keep one, no conflict.
 *   2. Same id, different content: the version with the later updatedAt keeps the id, the other is
 *      saved as a new note titled "<title> (conflict copy)". Nothing is ever silently overwritten.
 *   3. Rows that exist only in the Drive copy are added, marked DIRTY so they also reach the account
 *      sync if that is turned on.
 *   4. Categories, tags, reminders, clipboard entries and voice metadata are last-writer-wins.
 *   5. Audio bytes are not part of the snapshot; a local recording path is never replaced by a
 *      remote row.
 */
object DriveMerge {

    data class Snapshot(
        val notes: List<NoteEntity> = emptyList(),
        val categories: List<CategoryEntity> = emptyList(),
        val tags: List<TagEntity> = emptyList(),
        val reminders: List<ReminderEntity> = emptyList(),
        val clipboard: List<ClipboardCaptureEntity> = emptyList(),
        val voice: List<VoiceRecordingEntity> = emptyList(),
        val savedAt: Long = 0,
    ) {
        val isEmpty: Boolean get() = notes.isEmpty() && categories.isEmpty() && tags.isEmpty() &&
            reminders.isEmpty() && clipboard.isEmpty() && voice.isEmpty()
        val size: Int get() = notes.size + categories.size + tags.size + reminders.size + clipboard.size + voice.size
    }

    data class Merged(val snapshot: Snapshot, val conflicts: Int)

    fun merge(local: Snapshot, remote: Snapshot): Merged {
        var conflicts = 0
        val out = mutableListOf<NoteEntity>()
        val localById = local.notes.associateBy { it.id }.toMutableMap()

        for (r in remote.notes) {
            val l = localById[r.id]
            if (l == null) {
                out += r.copy(syncState = SyncState.DIRTY)
                continue
            }
            localById.remove(r.id)
            if (sameNote(l, r)) {
                out += l
                continue
            }
            conflicts++
            val keepLocal = l.updatedAt >= r.updatedAt
            val winner = if (keepLocal) l else r.copy(syncState = SyncState.SYNCED)
            val loser = if (keepLocal) r else l
            out += winner
            out += loser.copy(
                id = newId(),
                title = (loser.title.ifBlank { "Untitled" }) + " (conflict copy)",
                conflictOf = winner.id,
                serverUpdatedAt = 0,
                syncState = SyncState.DIRTY,
                trashed = false,
            )
        }
        out += localById.values

        return Merged(
            snapshot = Snapshot(
                notes = out,
                categories = mergeByTime(local.categories, remote.categories, { it.id }, { it.updatedAt }, { it.copy(syncState = SyncState.DIRTY) }),
                tags = mergeByTime(local.tags, remote.tags, { it.id }, { it.updatedAt }, { it.copy(syncState = SyncState.DIRTY) }),
                reminders = mergeByTime(local.reminders, remote.reminders, { it.id }, { it.updatedAt }, { it.copy(syncState = SyncState.DIRTY) }),
                clipboard = mergeByTime(local.clipboard, remote.clipboard, { it.id }, { it.createdAt }, { it.copy(syncState = SyncState.DIRTY) }),
                voice = mergeVoice(local.voice, remote.voice),
                savedAt = System.currentTimeMillis(),
            ),
            conflicts = conflicts,
        )
    }

    private fun sameNote(a: NoteEntity, b: NoteEntity): Boolean =
        a.updatedAt == b.updatedAt || (a.title == b.title && a.body == b.body && a.categoryId == b.categoryId &&
            a.colorLabel == b.colorLabel && a.pinned == b.pinned && a.archived == b.archived &&
            a.trashed == b.trashed && a.trashedAt == b.trashedAt)

    private fun <T> mergeByTime(
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        updated: (T) -> Long,
        markDirty: (T) -> T,
    ): List<T> {
        val byId = LinkedHashMap<String, T>()
        local.forEach { byId[id(it)] = it }
        remote.forEach { r ->
            val l = byId[id(r)]
            byId[id(r)] = when {
                l == null -> markDirty(r)
                updated(r) > updated(l) -> r
                else -> l
            }
        }
        return byId.values.toList()
    }

    /** A recording's local file path always wins: the snapshot carries metadata, not audio. */
    private fun mergeVoice(local: List<VoiceRecordingEntity>, remote: List<VoiceRecordingEntity>): List<VoiceRecordingEntity> =
        mergeByTime(local, remote, { it.id }, { it.updatedAt }, { it.copy(syncState = SyncState.DIRTY) })
            .map { r -> local.firstOrNull { it.id == r.id }?.let { r.copy(filePath = it.filePath, sizeBytes = it.sizeBytes) } ?: r }
}

/** JSON codec for the Drive snapshot file (schemaVersion 1). */
object SnapshotCodec {
    const val FILE_NAME = "notefloat-notes.json"
    const val SCHEMA_VERSION = 1

    fun toJson(s: DriveMerge.Snapshot): String {
        val o = JSONObject()
            .put("app", "NoteFloat")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("savedAt", System.currentTimeMillis())
        o.put("notes", JSONArray(s.notes.map { n ->
            JSONObject().put("id", n.id).put("title", n.title).put("body", n.body)
                .put("categoryId", n.categoryId ?: JSONObject.NULL).put("colorLabel", n.colorLabel)
                .put("pinned", n.pinned).put("archived", n.archived).put("trashed", n.trashed)
                .put("trashedAt", n.trashedAt).put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
                .put("conflictOf", n.conflictOf ?: JSONObject.NULL)
        }))
        o.put("categories", JSONArray(s.categories.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("colorLabel", c.colorLabel)
                .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt).put("deleted", c.deleted)
        }))
        o.put("tags", JSONArray(s.tags.map { t ->
            JSONObject().put("id", t.id).put("name", t.name).put("updatedAt", t.updatedAt)
        }))
        o.put("reminders", JSONArray(s.reminders.map { r ->
            JSONObject().put("id", r.id).put("noteId", r.noteId ?: JSONObject.NULL).put("title", r.title)
                .put("fireAt", r.fireAt).put("repeat", r.repeat).put("completed", r.completed)
                .put("completedAt", r.completedAt).put("snoozedUntil", r.snoozedUntil)
                .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt).put("deleted", r.deleted)
        }))
        o.put("clipboard", JSONArray(s.clipboard.map { c ->
            JSONObject().put("id", c.id).put("text", c.text).put("savedAsNoteId", c.savedAsNoteId ?: JSONObject.NULL)
                .put("createdAt", c.createdAt).put("deleted", c.deleted)
        }))
        o.put("voice", JSONArray(s.voice.map { v ->
            JSONObject().put("id", v.id).put("name", v.name).put("durationMs", v.durationMs)
                .put("noteId", v.noteId ?: JSONObject.NULL).put("transcript", v.transcript ?: JSONObject.NULL)
                .put("createdAt", v.createdAt).put("updatedAt", v.updatedAt).put("deleted", v.deleted)
        }))
        return o.toString()
    }

    /** Lenient by design: a truncated or foreign file must never crash the app or wipe local notes. */
    fun fromJson(text: String): DriveMerge.Snapshot {
        val o = JSONObject(text)
        fun arr(key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()
        fun objs(key: String): List<JSONObject> {
            val a = arr(key); return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        fun str(x: JSONObject, key: String): String? =
            if (x.isNull(key)) null else x.optString(key).takeIf { it.isNotEmpty() && it != "null" }

        return DriveMerge.Snapshot(
            notes = objs("notes").mapNotNull { n ->
                val id = n.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NoteEntity(
                    id = id,
                    title = n.optString("title"),
                    body = n.optString("body"),
                    categoryId = str(n, "categoryId"),
                    colorLabel = n.optInt("colorLabel"),
                    pinned = n.optBoolean("pinned"),
                    archived = n.optBoolean("archived"),
                    trashed = n.optBoolean("trashed"),
                    trashedAt = n.optLong("trashedAt"),
                    createdAt = n.optLong("createdAt"),
                    updatedAt = n.optLong("updatedAt"),
                    syncState = SyncState.SYNCED,
                    conflictOf = str(n, "conflictOf"),
                )
            },
            categories = objs("categories").mapNotNull { c ->
                val id = c.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CategoryEntity(
                    id = id, name = c.optString("name"), colorLabel = c.optInt("colorLabel"),
                    createdAt = c.optLong("createdAt"), updatedAt = c.optLong("updatedAt"),
                    deleted = c.optBoolean("deleted"), syncState = SyncState.SYNCED,
                )
            },
            tags = objs("tags").mapNotNull { t ->
                val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TagEntity(id = id, name = t.optString("name"), updatedAt = t.optLong("updatedAt"), syncState = SyncState.SYNCED)
            },
            reminders = objs("reminders").mapNotNull { r ->
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ReminderEntity(
                    id = id, noteId = str(r, "noteId"), title = r.optString("title"),
                    fireAt = r.optLong("fireAt"), repeat = r.optString("repeat", "NONE"),
                    completed = r.optBoolean("completed"), completedAt = r.optLong("completedAt"),
                    snoozedUntil = r.optLong("snoozedUntil"), createdAt = r.optLong("createdAt"),
                    updatedAt = r.optLong("updatedAt"), deleted = r.optBoolean("deleted"),
                    syncState = SyncState.SYNCED,
                )
            },
            clipboard = objs("clipboard").mapNotNull { c ->
                val id = c.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClipboardCaptureEntity(
                    id = id, text = c.optString("text"), savedAsNoteId = str(c, "savedAsNoteId"),
                    createdAt = c.optLong("createdAt"), deleted = c.optBoolean("deleted"),
                    syncState = SyncState.SYNCED,
                )
            },
            voice = objs("voice").mapNotNull { v ->
                val id = v.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VoiceRecordingEntity(
                    id = id, name = v.optString("name"), filePath = "", durationMs = v.optLong("durationMs"),
                    noteId = str(v, "noteId"), transcript = str(v, "transcript"),
                    createdAt = v.optLong("createdAt"), updatedAt = v.optLong("updatedAt"),
                    deleted = v.optBoolean("deleted"), syncState = SyncState.SYNCED,
                )
            },
            savedAt = o.optLong("savedAt"),
        )
    }
}
