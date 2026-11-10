package com.notefloat.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(note: NoteEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(notes: List<NoteEntity>)
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE trashed = 0 AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashed = 0 AND archived = 0 ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE pinned = 1 AND trashed = 0 AND archived = 0 ORDER BY updatedAt DESC")
    fun observePinned(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE archived = 1 AND trashed = 0 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashed = 1 ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashed = 0 AND categoryId = :categoryId ORDER BY pinned DESC, updatedAt DESC")
    fun observeByCategory(categoryId: String): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE trashed = 0 AND (title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%') " +
            "ORDER BY pinned DESC, updatedAt DESC LIMIT 100"
    )
    suspend fun search(q: String): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE trashed = 0") fun observeCount(): Flow<Int>
    @Query("SELECT * FROM notes WHERE syncState != 1") suspend fun pendingSync(): List<NoteEntity>
    @Query("DELETE FROM notes WHERE id = :id") suspend fun hardDelete(id: String)
    @Query("DELETE FROM notes WHERE trashed = 1 AND trashedAt < :before") suspend fun purgeTrashBefore(before: Long): Int
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CategoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(c: List<CategoryEntity>)
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY name COLLATE NOCASE") suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE syncState != 1") suspend fun pendingSync(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun get(id: String): CategoryEntity?
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: TagEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(t: List<TagEntity>)
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE") suspend fun byName(name: String): TagEntity?
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<TagEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(ref: NoteTagRef)
    @Query("DELETE FROM note_tags WHERE noteId = :noteId") suspend fun unlinkAll(noteId: String)
    @Query("SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId = t.id WHERE nt.noteId = :noteId ORDER BY t.name COLLATE NOCASE")
    suspend fun tagsFor(noteId: String): List<TagEntity>
    @Query("SELECT * FROM tags WHERE syncState != 1") suspend fun pendingSync(): List<TagEntity>
}

@Dao
interface VoiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: VoiceRecordingEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(v: List<VoiceRecordingEntity>)
    @Query("SELECT * FROM voice_recordings WHERE deleted = 0 ORDER BY createdAt DESC") fun observe(): Flow<List<VoiceRecordingEntity>>
    @Query("SELECT * FROM voice_recordings WHERE id = :id") suspend fun get(id: String): VoiceRecordingEntity?
    @Query("SELECT * FROM voice_recordings WHERE noteId = :noteId AND deleted = 0") suspend fun forNote(noteId: String): List<VoiceRecordingEntity>
    @Query("SELECT * FROM voice_recordings WHERE syncState != 1") suspend fun pendingSync(): List<VoiceRecordingEntity>
    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM voice_recordings WHERE deleted = 0") fun observeBytes(): Flow<Long>
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: ReminderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(r: List<ReminderEntity>)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: String): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE deleted = 0 AND completed = 0 ORDER BY fireAt") fun observeUpcoming(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE deleted = 0 AND completed = 1 ORDER BY completedAt DESC") fun observeHistory(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE deleted = 0 AND completed = 0") suspend fun allActive(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE noteId = :noteId AND deleted = 0 ORDER BY fireAt") suspend fun forNote(noteId: String): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE syncState != 1") suspend fun pendingSync(): List<ReminderEntity>
}

@Dao
interface ClipboardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ClipboardCaptureEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(c: List<ClipboardCaptureEntity>)
    @Query("SELECT * FROM clipboard_captures WHERE deleted = 0 ORDER BY createdAt DESC LIMIT 200") fun observe(): Flow<List<ClipboardCaptureEntity>>
    @Query("SELECT * FROM clipboard_captures WHERE syncState != 1") suspend fun pendingSync(): List<ClipboardCaptureEntity>
}

@Dao
interface SyncLogDao {
    @Insert suspend fun insert(l: SyncLogEntity)
    @Query("SELECT * FROM sync_logs ORDER BY at DESC LIMIT 50") fun observe(): Flow<List<SyncLogEntity>>
    @Query("DELETE FROM sync_logs WHERE at < :before") suspend fun trim(before: Long)
}
