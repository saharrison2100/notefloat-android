package com.notefloat.app.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.ClipboardCaptureEntity

/**
 * Clipboard capture is strictly user-initiated: nothing here registers a listener or polls.
 * readNow() is called only from a tap on "Clipboard" and returns text for the user to review and edit
 * before anything is saved. Content that the source app flags as sensitive (passwords, OTPs) is refused.
 *
 * Android 10+ only lets the foreground app with input focus read the clipboard. When called from the
 * floating panel, the panel window requests focus first (see PanelController); if Android still
 * returns nothing the UI tells the user to open the app and try again.
 */
object ClipboardCapture {

    sealed class Result {
        data class Text(val text: String) : Result()
        object Empty : Result()
        object Sensitive : Result()
        object NotText : Result()
    }

    fun readNow(context: Context): Result {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return Result.Empty
        val desc = cm.primaryClipDescription
        if (isSensitive(desc)) return Result.Sensitive
        if (desc != null && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
            return Result.NotText
        }
        val clip: ClipData = cm.primaryClip ?: return Result.Empty
        if (clip.itemCount == 0) return Result.Empty
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        return if (text.isEmpty()) Result.Empty else Result.Text(text.take(20_000))
    }

    private fun isSensitive(d: ClipDescription?): Boolean {
        val extras: PersistableBundle = d?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= 33) extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        else extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    suspend fun record(context: Context, text: String, noteId: String?) {
        AppDatabase.get(context).clipboard().upsert(ClipboardCaptureEntity(text = text, savedAsNoteId = noteId))
    }
}
