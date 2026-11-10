package com.notefloat.app.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Sync state shared by every syncable row. */
object SyncState { const val DIRTY = 0; const val SYNCED = 1; const val CONFLICT = 2 }

@Entity(tableName = "notes", indices = [Index("updatedAt"), Index("categoryId"), Index("trashed"), Index("archived")])
data class NoteEntity(
    @PrimaryKey val id: String = newId(),
    val title: String = "",
    /** Rich text stored as lightweight markup: **bold**, _italic_, "# " headings, "- " bullets, "1. " numbers, "[ ] "/"[x] " checklists. */
    val body: String = "",
    val categoryId: String? = null,
    val colorLabel: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val trashedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncState: Int = SyncState.DIRTY,
    val serverUpdatedAt: Long = 0,
    /** Set when a sync conflict preserved this copy as a duplicate rather than overwriting. */
    val conflictOf: String? = null
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val colorLabel: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val syncState: Int = SyncState.DIRTY
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncState: Int = SyncState.DIRTY
)

@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId")]
)
data class NoteTagRef(val noteId: String, val tagId: String)

@Entity(tableName = "voice_recordings", indices = [Index("noteId")])
data class VoiceRecordingEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val filePath: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val noteId: String? = null,
    val transcript: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val syncState: Int = SyncState.DIRTY,
    val uploaded: Boolean = false
)

@Entity(tableName = "reminders", indices = [Index("noteId"), Index("fireAt")])
data class ReminderEntity(
    @PrimaryKey val id: String = newId(),
    val noteId: String? = null,
    val title: String,
    val fireAt: Long,
    /** NONE, DAILY, WEEKLY, MONTHLY */
    val repeat: String = "NONE",
    val completed: Boolean = false,
    val completedAt: Long = 0,
    val snoozedUntil: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val syncState: Int = SyncState.DIRTY
)

@Entity(tableName = "clipboard_captures")
data class ClipboardCaptureEntity(
    @PrimaryKey val id: String = newId(),
    val text: String,
    val savedAsNoteId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val syncState: Int = SyncState.DIRTY
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val ok: Boolean,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val message: String = ""
)
