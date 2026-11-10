package com.notefloat.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.SyncState
import com.notefloat.app.storage.VoiceRecordingEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records AAC/M4A audio with MediaRecorder. Recording only ever starts from an explicit call
 * made in response to a user tap; there is no background or automatic recording anywhere.
 * Pause/resume uses MediaRecorder.pause()/resume() (API 24+).
 */
class VoiceRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING, PAUSED }

    var state: State = State.IDLE; private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var accumulated = 0L

    val elapsedMs: Long get() = when (state) {
        State.RECORDING -> accumulated + (SystemClock.elapsedRealtime() - startedAt)
        State.PAUSED -> accumulated
        State.IDLE -> 0
    }

    fun start(): Boolean {
        if (state != State.IDLE) return false
        val dir = audioDir(context)
        val f = File(dir, "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; file = f
            startedAt = SystemClock.elapsedRealtime(); accumulated = 0; state = State.RECORDING
            true
        } catch (e: Exception) {
            runCatching { r.release() }; f.delete(); state = State.IDLE; false
        }
    }

    fun pause(): Boolean {
        if (state != State.RECORDING) return false
        return runCatching {
            recorder?.pause()
            accumulated += SystemClock.elapsedRealtime() - startedAt
            state = State.PAUSED
        }.isSuccess
    }

    fun resume(): Boolean {
        if (state != State.PAUSED) return false
        return runCatching {
            recorder?.resume(); startedAt = SystemClock.elapsedRealtime(); state = State.RECORDING
        }.isSuccess
    }

    /** Stops and saves to Room. Returns the saved recording, or null if it failed or was empty. */
    suspend fun stop(name: String? = null, noteId: String? = null): VoiceRecordingEntity? {
        if (state == State.IDLE) return null
        val duration = elapsedMs
        val f = file
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null; file = null; state = State.IDLE; accumulated = 0
        if (f == null || !f.exists() || f.length() < 512 || duration < 400) { f?.delete(); return null }
        val title = name?.takeIf { it.isNotBlank() }
            ?: ("Voice note " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date()))
        val rec = VoiceRecordingEntity(name = title, filePath = f.absolutePath, durationMs = duration, sizeBytes = f.length(), noteId = noteId)
        AppDatabase.get(context).voice().upsert(rec)
        return rec
    }

    /** Throw away the in-progress recording without saving. */
    fun cancel() {
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }
        file?.delete(); recorder = null; file = null; state = State.IDLE; accumulated = 0
    }

    companion object {
        fun audioDir(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }

        fun fmt(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        }
    }
}

/** Plays one recording at a time. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    var playingId: String? = null; private set

    fun toggle(rec: VoiceRecordingEntity, onDone: () -> Unit) {
        if (playingId == rec.id) { stop(); onDone(); return }
        stop()
        val f = File(rec.filePath)
        if (!f.exists()) { onDone(); return }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { stop(); onDone() }
        }
        playingId = rec.id
    }

    fun stop() { runCatching { player?.stop() }; runCatching { player?.release() }; player = null; playingId = null }
}

object VoiceManager {
    suspend fun rename(context: Context, id: String, name: String) {
        val dao = AppDatabase.get(context).voice()
        dao.get(id)?.let { dao.upsert(it.copy(name = name.trim(), updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }
    suspend fun attach(context: Context, id: String, noteId: String?) {
        val dao = AppDatabase.get(context).voice()
        dao.get(id)?.let { dao.upsert(it.copy(noteId = noteId, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY)) }
    }
    suspend fun delete(context: Context, id: String) {
        val dao = AppDatabase.get(context).voice()
        dao.get(id)?.let {
            File(it.filePath).delete()
            dao.upsert(it.copy(deleted = true, updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY))
        }
    }
}
