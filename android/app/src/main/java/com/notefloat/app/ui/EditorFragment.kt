package com.notefloat.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.notes.RichText
import com.notefloat.app.reminders.ReminderScheduler
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.NoteEntity
import com.notefloat.app.voice.VoiceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Screen 4: Quick capture / full editor.
 * - Autosaves 600 ms after the last keystroke, and again on pause. A disk draft is written every 250 ms,
 *   so even a process kill loses at most a fraction of a second of typing.
 * - The raw markup is edited; a Preview toggle shows the rendered rich text.
 */
class EditorFragment : Fragment() {
    companion object {
        fun newInstance(id: String?, prefill: String? = null, categoryId: String? = null) = EditorFragment().apply {
            arguments = Bundle().apply { putString("id", id); putString("prefill", prefill); putString("cat", categoryId) }
        }
    }

    private var noteId: String? = null
    private var current: NoteEntity? = null
    private var category: String? = null
    private var et: EditText? = null
    private val h = Handler(Looper.getMainLooper())
    private val autosave = Runnable { persist(false) }
    private val draftWrite = Runnable { et?.let { (activity as? MainActivity)?.repo?.saveDraft(noteId, it.text.toString()) } }
    private var loaded = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        noteId = arguments?.getString("id"); category = arguments?.getString("cat")
        val col = ui.column()

        val title = ui.tv(if (noteId == null) "New note" else "Edit note", 22f, true)
        val saved = ui.tv("", 12f, false, ui.sub)
        val top = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(saved); col.addView(top)

        val editor = ui.edit("Start typing. Everything is saved as you write.", multiline = true).also { et = it }
        editor.minLines = 12
        val preview = ui.tv("", 15f).apply { background = ui.card(14f); setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f)); visibility = View.GONE }

        // formatting bar
        val bar = LinearLayout(requireContext()).apply { setPadding(0, ui.dp(8f), 0, ui.dp(8f)) }
        fun tool(t: String, desc: String, f: () -> Unit) = bar.addView(ui.button(t, false) { f() }.apply { contentDescription = desc; minimumWidth = 0; setPadding(ui.dp(4f), 0, ui.dp(4f), 0) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4f) })
        tool("B", "Bold") { RichText.wrapSelection(editor.text, editor.selectionStart, editor.selectionEnd, "**") }
        tool("I", "Italic") { RichText.wrapSelection(editor.text, editor.selectionStart, editor.selectionEnd, "_") }
        tool("H", "Heading") { RichText.toggleLinePrefix(editor.text, editor.selectionStart, "# ") }
        tool("\u2022", "Bullet list") { RichText.toggleLinePrefix(editor.text, editor.selectionStart, "- ") }
        tool("1.", "Numbered list") { RichText.toggleLinePrefix(editor.text, editor.selectionStart, "1. ") }
        tool("\u2610", "Checklist") { RichText.toggleLinePrefix(editor.text, editor.selectionStart, "[ ] ") }
        col.addView(bar); col.addView(editor); col.addView(preview)

        var previewing = false
        val previewBtn = ui.button("Show formatted preview", false) {}
        previewBtn.setOnClickListener {
            previewing = !previewing
            if (previewing) { preview.text = RichText.render(editor.text.toString()); preview.visibility = View.VISIBLE; editor.visibility = View.GONE }
            else { preview.visibility = View.GONE; editor.visibility = View.VISIBLE }
            previewBtn.text = if (previewing) "Back to editing" else "Show formatted preview"
        }
        col.addView(previewBtn)

        editor.setOnKeyListener { _, code, ev -> code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN && RichText.continueList(editor.text, editor.selectionStart) }
        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (!loaded) return
                saved.text = "Saving..."
                h.removeCallbacks(draftWrite); h.postDelayed(draftWrite, 250)
                h.removeCallbacks(autosave); h.postDelayed(autosave, 600)
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })

        // meta
        val tags = ui.edit("Tags, separated by commas")
        col.addView(ui.section("Tags")); col.addView(tags)
        val catRow = ui.tapRow("Category", "None") {}
        val colorRow = ui.tapRow("Colour label", Ui.COLOR_NAMES[0]) {}
        col.addView(ui.section("Organise")); col.addView(ui.group(catRow, colorRow))

        val voiceBox = ui.column(); col.addView(ui.section("Attached recordings")); col.addView(voiceBox)
        val remBox = ui.column(); col.addView(ui.section("Reminders")); col.addView(remBox)

        val actions = ui.column(); col.addView(ui.section("Actions"))
        val pin = ui.tapRow("Pin", null) {}; val arch = ui.tapRow("Archive", null) {}
        val dup = ui.tapRow("Duplicate", null) {}; val remind = ui.tapRow("Add reminder for this note", null) {}
        val attach = ui.tapRow("Attach a voice recording", null) {}; val del = ui.tapRow("Move to trash", null) {}
        col.addView(ui.group(pin, arch, dup, remind, attach, del))

        // load
        viewLifecycleOwner.lifecycleScope.launch {
            val n = noteId?.let { m.repo.get(it) }
            current = n
            val draft = m.repo.loadDraft(noteId)
            val prefill = arguments?.getString("prefill")
            editor.setText(when { draft != null && (n == null || draft != n.body) && prefill == null -> draft; prefill != null -> prefill; else -> n?.body.orEmpty() })
            if (draft != null && n != null && draft != n.body && prefill == null) saved.text = "Recovered unsaved changes"
            editor.setSelection(editor.text.length)
            category = n?.categoryId ?: category
            tags.setText(n?.let { m.repo.tagsFor(it.id).joinToString(", ") { t -> t.name } }.orEmpty())
            loaded = true
            refreshMeta(ui, m, catRow, colorRow, pin, arch, voiceBox, remBox)
            if (n == null) editor.requestFocus()
        }
        tags.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { if (loaded) { h.removeCallbacks(autosave); h.postDelayed(autosave, 800) } }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        tagsField = tags; savedLabel = saved

        catRow.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch {
            val cats = m.repo.categories.first()
            val names = (listOf("None") + cats.map { it.name }).toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Category").setItems(names) { _, w ->
                category = if (w == 0) null else cats[w - 1].id
                persist(false); viewLifecycleOwner.lifecycleScope.launch { refreshMeta(ui, m, catRow, colorRow, pin, arch, voiceBox, remBox) }
            }.show() } }
        colorRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Colour label").setItems(Ui.COLOR_NAMES.toTypedArray()) { _, w ->
                viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { m.repo.setColor(it.id, w); reload(m); refreshMeta(ui, m, catRow, colorRow, pin, arch, voiceBox, remBox) } } }.show() }
        pin.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { m.repo.setPinned(it.id, !it.pinned); reload(m); refreshMeta(ui, m, catRow, colorRow, pin, arch, voiceBox, remBox); SyncWorker.syncSoon(requireContext().applicationContext) } } }
        arch.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { m.repo.setArchived(it.id, !it.archived); SyncWorker.syncSoon(requireContext().applicationContext); parentFragmentManager.popBackStack() } } }
        dup.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { m.repo.duplicate(it.id); android.widget.Toast.makeText(requireContext(), "Duplicated", android.widget.Toast.LENGTH_SHORT).show(); SyncWorker.syncSoon(requireContext().applicationContext) } } }
        del.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { current?.let { m.repo.moveToTrash(it.id); m.repo.clearDraft(it.id); SyncWorker.syncSoon(requireContext().applicationContext); android.widget.Toast.makeText(requireContext(), "Moved to trash. Restore it from More > Trash.", android.widget.Toast.LENGTH_LONG).show() }
            noteId = null; current = null; et?.setText(""); parentFragmentManager.popBackStack() } }
        remind.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { n -> pickReminder(m, n) } } }
        attach.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ensureSaved(m); current?.let { n -> pickRecording(m, n, ui, voiceBox) } } }
        return ui.scroll(col)
    }

    private var tagsField: EditText? = null
    private var savedLabel: android.widget.TextView? = null

    private suspend fun ensureSaved(m: MainActivity) {
        if (current == null) { persistNow(m) }
    }

    private suspend fun reload(m: MainActivity) { current = noteId?.let { m.repo.get(it) } ?: current }

    private fun persist(close: Boolean) { val m = activity as? MainActivity ?: return; m.lifecycleScope.launch { persistNow(m) } }

    private suspend fun persistNow(m: MainActivity) {
        val body = et?.text?.toString() ?: return
        val tags = tagsField?.text?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val saved = m.repo.save(noteId, body, category, null, tags)
        if (saved != null) { noteId = saved.id; current = saved; m.repo.clearDraft(null); savedLabel?.text = "Saved" ; SyncWorker.syncSoon(m.applicationContext) }
        else savedLabel?.text = ""
    }

    private suspend fun refreshMeta(ui: Ui, m: MainActivity, catRow: LinearLayout, colorRow: LinearLayout, pin: LinearLayout, arch: LinearLayout, voiceBox: LinearLayout, remBox: LinearLayout) {
        fun setValue(row: LinearLayout, v: String) { (row.getChildAt(1) as? android.widget.TextView)?.text = v ?: "" }
        val cat = category?.let { id -> m.repo.categories.first().firstOrNull { it.id == id }?.name } ?: "None"
        if (catRow.childCount < 2) catRow.addView(ui.tv(cat, 14f, false, ui.sub)) else setValue(catRow, cat)
        val cl = Ui.COLOR_NAMES[(current?.colorLabel ?: 0).coerceIn(0, 6)]
        if (colorRow.childCount < 2) colorRow.addView(ui.tv(cl, 14f, false, ui.sub)) else setValue(colorRow, cl)
        (pin.getChildAt(0) as android.widget.TextView).text = if (current?.pinned == true) "Unpin" else "Pin"
        (arch.getChildAt(0) as android.widget.TextView).text = if (current?.archived == true) "Unarchive" else "Archive"
        voiceBox.removeAllViews(); remBox.removeAllViews()
        val id = current?.id ?: return
        val db = AppDatabase.get(requireContext())
        db.voice().forNote(id).forEach { v -> voiceBox.addView(ui.body("\u25B6 ${v.name}  (${com.notefloat.app.voice.VoiceRecorder.fmt(v.durationMs)})")) }
        if (voiceBox.childCount == 0) voiceBox.addView(ui.body("None"))
        db.reminders().forNote(id).forEach { r -> remBox.addView(ui.body("${r.title}  \u2022  " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ReminderScheduler.effectiveTime(r))) + if (r.completed) "  (done)" else "")) }
        if (remBox.childCount == 0) remBox.addView(ui.body("None"))
    }

    private fun pickReminder(m: MainActivity, n: NoteEntity) {
        val opts = arrayOf("In 15 minutes", "In 1 hour", "Tomorrow 9:00", "Custom time...")
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Remind me").setItems(opts) { _, w ->
            fun create(cal: Calendar) { m.lifecycleScope.launch {
                if (android.os.Build.VERSION.SDK_INT >= 33 && requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    PermissionGateActivity.request(requireContext(), PermissionGateActivity.NOTIFY); return@launch }
                ReminderScheduler.create(requireContext().applicationContext, n.title.ifBlank { "Note reminder" }, cal.timeInMillis, "NONE", n.id)
                android.widget.Toast.makeText(requireContext(), "Reminder set", android.widget.Toast.LENGTH_SHORT).show(); SyncWorker.syncSoon(requireContext().applicationContext) } }
            val cal = Calendar.getInstance()
            when (w) {
                0 -> { cal.add(Calendar.MINUTE, 15); create(cal) }
                1 -> { cal.add(Calendar.HOUR_OF_DAY, 1); create(cal) }
                2 -> { cal.add(Calendar.DAY_OF_YEAR, 1); cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); create(cal) }
                else -> android.app.DatePickerDialog(requireContext(), { _, y, mo, d ->
                    android.app.TimePickerDialog(requireContext(), { _, hh, mm -> cal.set(y, mo, d, hh, mm, 0); create(cal) }, 9, 0, true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }.show()
    }

    private fun pickRecording(m: MainActivity, n: NoteEntity, ui: Ui, box: LinearLayout) {
        m.lifecycleScope.launch {
            val all = AppDatabase.get(requireContext()).voice().observe().first()
            if (all.isEmpty()) { android.widget.Toast.makeText(requireContext(), "No recordings yet. Record one in the Voice tab.", android.widget.Toast.LENGTH_LONG).show(); return@launch }
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Attach recording")
                .setItems(all.map { it.name }.toTypedArray()) { _, w -> m.lifecycleScope.launch { VoiceManager.attach(requireContext().applicationContext, all[w].id, n.id)
                    box.addView(ui.body("\u25B6 ${all[w].name}")); SyncWorker.syncSoon(requireContext().applicationContext) } }.show()
        }
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(autosave); h.removeCallbacks(draftWrite)
        val m = activity as? MainActivity ?: return
        // write the draft synchronously first (cheap), then the note
        et?.let { m.repo.saveDraft(noteId, it.text.toString()) }
        if (loaded) m.lifecycleScope.launch { persistNow(m) }
    }
}
