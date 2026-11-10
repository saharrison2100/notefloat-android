package com.notefloat.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class, CategoryEntity::class, TagEntity::class, NoteTagRef::class,
        VoiceRecordingEntity::class, ReminderEntity::class, ClipboardCaptureEntity::class, SyncLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun categories(): CategoryDao
    abstract fun tags(): TagDao
    abstract fun voice(): VoiceDao
    abstract fun reminders(): ReminderDao
    abstract fun clipboard(): ClipboardDao
    abstract fun syncLogs(): SyncLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notefloat.db")
                .build().also { instance = it }
        }
    }
}
