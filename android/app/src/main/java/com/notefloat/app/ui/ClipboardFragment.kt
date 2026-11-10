package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.clipboard.ClipboardCapture
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.storage.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** Screen 7: Clipboard capture and history. Reads the clipboard only when the user taps the button. */
class ClipboardFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs); val db = AppDatabase.get(requireContext())
        val col = ui.column(); col.addView(ui.title("Clipboard"))
        col.addView(ui.body("NoteFloat never watches your clipboard. Tap the button to read what you last copied, edit it, then save."))
        val preview = ui.edit("Captured text appears here. You can edit it.", multiline = true); preview.minLines = 6
        val status = ui.body("")
        var chosenCat: String? = null
        val catRow = ui.tapRow("Category", "None") {}
        col.addView(ui.button("Read clipboard now") {
            when (val r = ClipboardCapture.readNow(requireContext())) {
                is ClipboardCapture.Result.Text -> { preview.setText(r.text); status.text = "Review and edit, then save." }
                ClipboardCapture.Result.Sensitive -> status.text = "The copied content is marked sensitive, so NoteFloat did not read it."
                ClipboardCapture.Result.NotText -> status.text = "The clipboard does not hold text."
                ClipboardCapture.Result.Empty -> status.text = "The clipboard is empty, or Android blocked access. Keep this screen open and try again."
            }
        })
        col.addView(status); col.addView(preview); col.addView(ui.spacer(8f)); col.addView(ui.group(catRow))
        catRow.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch {
            val cats = m.repo.categories.first()
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Category").setItems((listOf("None") + cats.map { it.name }).toTypedArray()) { _, w ->
                chosenCat = if (w == 0) null else cats[w - 1].id; (catRow.getChildAt(1) as? android.widget.TextView)?.text = if (w == 0) "None" else cats[w - 1].name
            }.show() } }
        col.addView(ui.spacer(8f))
        col.addView(ui.button("Save as note") {
            val t = preview.text.toString()
            if (t.isBlank()) { status.text = "Nothing to save yet."; return@button }
            m.lifecycleScope.launch {
                val n = m.repo.save(null, t, chosenCat)
                ClipboardCapture.record(requireContext().applicationContext, t, n?.id); preview.setText(""); status.text = "Saved as a note."
                SyncWorker.syncSoon(requireContext().applicationContext)
            }
        })
        col.addView(ui.section("History"))
        val list = ui.column(); col.addView(list)
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        viewLifecycleOwner.lifecycleScope.launch {
            db.clipboard().observe().collect { l ->
                list.removeAllViews(); if (l.isEmpty()) list.addView(ui.body("Nothing captured yet."))
                l.forEach { cap -> list.addView(ui.column().apply {
                    background = ui.card(); setPadding(ui.dp(16f), ui.dp(12f), ui.dp(16f), ui.dp(12f))
                    addView(ui.tv(cap.text.take(140), 14f)); addView(ui.tv(fmt.format(Date(cap.createdAt)) + if (cap.savedAsNoteId != null) "   saved as note" else "", 11f, false, ui.sub))
                    setOnClickListener { preview.setText(cap.text) }
                    setOnLongClickListener { m.lifecycleScope.launch { db.clipboard().upsert(cap.copy(deleted = true, syncState = 0)) }; true }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8f) } }) }
            }
        }
        list.addView(ui.body("Tap an entry to reuse it. Long press to remove it from history."))
        return ui.scroll(col)
    }
}
