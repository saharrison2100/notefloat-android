package com.notefloat.app

import com.notefloat.app.networking.DriveMerge
import com.notefloat.app.networking.SnapshotCodec
import com.notefloat.app.storage.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive backup must never lose a note. These tests pin the merge rules that make that true:
 * identical rows collapse to one, a genuine two-sided edit keeps both, and the newer edit keeps the
 * original id while the older one is preserved as a "(conflict copy)".
 */
class DriveMergeTest {

    private fun note(
        id: String,
        title: String = "Note",
        body: String = "body",
        updatedAt: Long = 1000,
        pinned: Boolean = false,
        trashed: Boolean = false,
    ) = NoteEntity(id = id, title = title, body = body, updatedAt = updatedAt, createdAt = updatedAt, pinned = pinned, trashed = trashed)

    @Test
    fun notesOnlyInTheDriveCopyAreAdded() {
        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(),
            DriveMerge.Snapshot(notes = listOf(note("a"))),
        )
        assertEquals(1, merged.snapshot.notes.size)
        assertEquals("a", merged.snapshot.notes.first().id)
        assertEquals(0, merged.conflicts)
        // A pulled row is marked dirty so the account sync picks it up too.
        assertEquals(SyncState.DIRTY, merged.snapshot.notes.first().syncState)
    }

    @Test
    fun identicalNotesOnBothSidesStayOneNote() {
        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(notes = listOf(note("a"))),
            DriveMerge.Snapshot(notes = listOf(note("a"))),
        )
        assertEquals(1, merged.snapshot.notes.size)
        assertEquals(0, merged.conflicts)
    }

    @Test
    fun bothSidesEditedKeepsBothAndTheNewerKeepsTheId() {
        val local = note("a", title = "Mine", body = "local edit", updatedAt = 2000)
        val remote = note("a", title = "Theirs", body = "drive edit", updatedAt = 1000)

        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(notes = listOf(local)),
            DriveMerge.Snapshot(notes = listOf(remote)),
        )

        assertEquals(1, merged.conflicts)
        assertEquals(2, merged.snapshot.notes.size)

        val winner = merged.snapshot.notes.first { it.id == "a" }
        assertEquals("local edit", winner.body)

        val copy = merged.snapshot.notes.first { it.id != "a" }
        assertEquals("drive edit", copy.body)
        assertTrue(copy.title.endsWith("(conflict copy)"))
        assertEquals("a", copy.conflictOf)
    }

    @Test
    fun remoteNewerEditWinsTheIdAndLocalIsKeptAsCopy() {
        val local = note("a", body = "local", updatedAt = 1000)
        val remote = note("a", body = "drive", updatedAt = 5000)

        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(notes = listOf(local)),
            DriveMerge.Snapshot(notes = listOf(remote)),
        )

        assertEquals("drive", merged.snapshot.notes.first { it.id == "a" }.body)
        assertEquals(1, merged.conflicts)
        assertEquals(2, merged.snapshot.notes.size)
    }

    @Test
    fun aTrashedNoteOnOneSideIsATruthfulDifferenceNotSilence() {
        val local = note("a", updatedAt = 3000, trashed = true)
        val remote = note("a", updatedAt = 1000)

        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(notes = listOf(local)),
            DriveMerge.Snapshot(notes = listOf(remote)),
        )

        assertTrue(merged.snapshot.notes.first { it.id == "a" }.trashed)
        assertTrue(merged.snapshot.notes.size == 2)
    }

    @Test
    fun categoriesKeepTheLaterVersion() {
        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(categories = listOf(CategoryEntity(id = "c", name = "Work", updatedAt = 100))),
            DriveMerge.Snapshot(categories = listOf(CategoryEntity(id = "c", name = "Office", updatedAt = 500))),
        )
        assertEquals(1, merged.snapshot.categories.size)
        assertEquals("Office", merged.snapshot.categories.first().name)
    }

    @Test
    fun aLocalRecordingNeverLosesItsFile() {
        val merged = DriveMerge.merge(
            DriveMerge.Snapshot(voice = listOf(VoiceRecordingEntity(id = "v", name = "Memo", filePath = "/data/v.m4a", sizeBytes = 42, updatedAt = 100))),
            DriveMerge.Snapshot(voice = listOf(VoiceRecordingEntity(id = "v", name = "Memo", filePath = "", updatedAt = 900))),
        )
        assertEquals("/data/v.m4a", merged.snapshot.voice.first().filePath)
        assertEquals(42, merged.snapshot.voice.first().sizeBytes)
    }

    @Test
    fun snapshotSurvivesAJsonRoundTrip() {
        val snapshot = DriveMerge.Snapshot(
            notes = listOf(note("a", title = "Groceries", body = "# List\n- milk", updatedAt = 1500, pinned = true)),
            categories = listOf(CategoryEntity(id = "c", name = "Home", updatedAt = 10)),
            tags = listOf(TagEntity(id = "t", name = "urgent", updatedAt = 11)),
            reminders = listOf(ReminderEntity(id = "r", title = "Call", fireAt = 999, repeat = "DAILY", updatedAt = 12)),
            clipboard = listOf(ClipboardCaptureEntity(id = "cb", text = "copied text", createdAt = 13)),
            voice = listOf(VoiceRecordingEntity(id = "v", name = "Memo", filePath = "/x", durationMs = 1000, updatedAt = 14)),
        )

        val restored = SnapshotCodec.fromJson(SnapshotCodec.toJson(snapshot))

        assertEquals(1, restored.notes.size)
        assertEquals("Groceries", restored.notes.first().title)
        assertTrue(restored.notes.first().pinned)
        assertEquals("# List\n- milk", restored.notes.first().body)
        assertEquals("Home", restored.categories.first().name)
        assertEquals("urgent", restored.tags.first().name)
        assertEquals("DAILY", restored.reminders.first().repeat)
        assertEquals("copied text", restored.clipboard.first().text)
        assertEquals(1000, restored.voice.first().durationMs)
        // Audio bytes never travel in the snapshot; the path must come back empty, not wrong.
        assertEquals("", restored.voice.first().filePath)
    }

    @Test
    fun anEmptyOrForeignFileDoesNotThrow() {
        val empty = SnapshotCodec.fromJson("{}")
        assertTrue(empty.isEmpty)

        val foreign = SnapshotCodec.fromJson("""{"app":"SomethingElse","notes":[{"title":"no id"}]}""")
        assertTrue(foreign.isEmpty)
    }

    @Test
    fun rowWithoutAnIdIsSkippedRatherThanCrashing() {
        val s = SnapshotCodec.fromJson("""{"notes":[{"title":"broken"},{"id":"good","title":"ok","updatedAt":5}]}""")
        assertEquals(1, s.notes.size)
        assertEquals("good", s.notes.first().id)
        assertNotNull(s.notes.first().title)
    }
}
