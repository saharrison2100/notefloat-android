package com.notefloat.app.notes

import android.content.Context
import com.notefloat.app.storage.*
import kotlinx.coroutines.flow.Flow

/** Single entry point for note operations. Every write marks the row DIRTY so sync picks it up. */
class NoteRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val drafts = DraftStore(context)

    val active: Flow<List<NoteEntity>> = db.notes().observeActive()
    val pinned: Flow<List<NoteEntity>> = db.notes().observePinned()
    val archived: Flow<List<NoteEntity>> = db.notes().observeArchived()
    val trash: Flow<List<NoteEntity>> = db.notes().observeTrash()
    val categories: Flow<List<CategoryEntity>> = db.categories().observe()
    val tags: Flow<List<TagEntity>> = db.tags().observe()
    fun byCategory(id: String) = db.notes().observeByCategory(id)

    suspend fun get(id: String) = db.notes().get(id)
    suspend fun recent(limit: Int = 5) = db.notes().recent(limit)
    suspend fun search(q: String) = if (q.isBlank()) emptyList() else db.notes().search(q.trim())

    /** Create or update. Returns the saved note. Blank notes are never created. */
    suspend fun save(id: String?, body: String, categoryId: String? = null, color: Int? = null, tags: List<String>? = null): NoteEntity? {
        val existing = id?.let { db.notes().get(it) }
        if (existing == null && body.isBlank()) return null
        val now = System.currentTimeMillis()
        val note = (existing ?: NoteEntity()).copy(
            body = body,
            title = RichText.titleOf(body),
            categoryId = categoryId ?: existing?.categoryId,
            colorLabel = color ?: existing?.colorLabel ?: 0,
            updatedAt = now,
            syncState = SyncState.DIRTY
        )
        db.notes().upsert(note)
        if (tags != null) setTags(note.id, tags)
        drafts.clear(id)
        return note
    }

    suspend fun setTags(noteId: String, names: List<String>) {
        db.tags().unlinkAll(noteId)
        names.map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            val tag = db.tags().byName(name) ?: TagEntity(name = name).also { db.tags().insert(it) }
            db.tags().link(NoteTagRef(noteId, tag.id))
        }
        touch(noteId)
    }

    suspend fun tagsFor(noteId: String) = db.tags().tagsFor(noteId)

    private suspend fun touch(id: String) {
        db.notes().get(id)?.let { db.notes().upsert(it.copy(updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }

    private suspend fun mutate(id: String, f: (NoteEntity) -> NoteEntity) {
        db.notes().get(id)?.let { db.notes().upsert(f(it).copy(updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }

    suspend fun setPinned(id: String, v: Boolean) = mutate(id) { it.copy(pinned = v) }
    suspend fun setArchived(id: String, v: Boolean) = mutate(id) { it.copy(archived = v, pinned = if (v) false else it.pinned) }
    suspend fun setColor(id: String, c: Int) = mutate(id) { it.copy(colorLabel = c) }
    suspend fun setCategory(id: String, cat: String?) = mutate(id) { it.copy(categoryId = cat) }
    suspend fun moveToTrash(id: String) = mutate(id) { it.copy(trashed = true, trashedAt = System.currentTimeMillis(), pinned = false) }
    suspend fun restore(id: String) = mutate(id) { it.copy(trashed = false, trashedAt = 0) }

    /** Permanent delete. The row is kept as a tombstone only if it has synced, so the server can delete it too. */
    suspend fun deleteForever(id: String) {
        val n = db.notes().get(id) ?: return
        if (n.serverUpdatedAt > 0) db.notes().upsert(n.copy(trashed = true, trashedAt = 1, syncState = SyncState.DIRTY, body = "", title = ""))
        else db.notes().hardDelete(id)
    }

    suspend fun duplicate(id: String): NoteEntity? {
        val n = db.notes().get(id) ?: return null
        val copy = n.copy(
            id = newId(), title = n.title, body = n.body, pinned = false,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            syncState = SyncState.DIRTY, serverUpdatedAt = 0, conflictOf = null
        )
        db.notes().upsert(copy)
        db.tags().tagsFor(id).forEach { db.tags().link(NoteTagRef(copy.id, it.id)) }
        return copy
    }

    suspend fun purgeOldTrash(days: Int = 30) { db.notes().purgeTrashBefore(System.currentTimeMillis() - days * 86_400_000L) }

    suspend fun addCategory(name: String, color: Int = 0): CategoryEntity {
        val existing = db.categories().all().firstOrNull { it.name.equals(name.trim(), true) }
        if (existing != null) return existing
        return CategoryEntity(name = name.trim(), colorLabel = color).also { db.categories().upsert(it) }
    }
    suspend fun renameCategory(id: String, name: String) {
        db.categories().get(id)?.let { db.categories().upsert(it.copy(name = name.trim(), updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }
    suspend fun deleteCategory(id: String) {
        db.categories().get(id)?.let { db.categories().upsert(it.copy(deleted = true, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }

    // ---- draft recovery ----
    fun saveDraft(noteId: String?, body: String) = drafts.save(noteId, body)
    fun loadDraft(noteId: String?) = drafts.load(noteId)
    fun clearDraft(noteId: String?) = drafts.clear(noteId)
    fun hasUnsavedDraft() = drafts.pendingKeys().isNotEmpty()
    fun pendingDrafts() = drafts.pendingKeys()
}

/**
 * Drafts are written to disk on every keystroke burst (debounced by the editor). If the app or the
 * overlay is killed mid-edit, the next open offers the draft back. A draft is cleared only after the
 * note itself has been written to Room.
 */
class DraftStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nf_drafts", Context.MODE_PRIVATE)
    private fun key(id: String?) = "d_" + (id ?: "new")
    fun save(id: String?, body: String) { if (body.isBlank()) clear(id) else sp.edit().putString(key(id), body).apply() }
    fun load(id: String?): String? = sp.getString(key(id), null)
    fun clear(id: String?) { sp.edit().remove(key(id)).apply() }
    fun pendingKeys(): List<String> = sp.all.keys.filter { it.startsWith("d_") }.map { it.removePrefix("d_") }
}
