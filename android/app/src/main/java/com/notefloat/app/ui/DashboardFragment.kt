package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.floating.OverlayService
import com.notefloat.app.storage.AppDatabase
import kotlinx.coroutines.launch

/** Screen 2: Main dashboard. */
class DashboardFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs); val db = AppDatabase.get(requireContext())
        val col = ui.column()
        col.addView(ui.title("NoteFloat")); col.addView(ui.body("Capture anything, anywhere."))

        val status = ui.tv("", 14f, false, ui.sub)
        val toggle = ui.button("") {}
        fun paint() {
            val on = OverlayService.running
            status.text = if (on) "Sidebar is on. Tap the handle over any app." else "Sidebar is off."
            toggle.text = if (on) "Turn off sidebar" else "Turn on sidebar"
        }
        toggle.setOnClickListener {
            if (OverlayService.running) { OverlayService.stop(requireContext()) ; toggle.postDelayed({ paint() }, 400) }
            else m.open(FloatingConfigFragment())
        }
        paint()
        val card = ui.column().apply { background = ui.card(); setPadding(ui.dp(16f), ui.dp(16f), ui.dp(16f), ui.dp(16f)) }
        card.addView(ui.tv("Floating sidebar", 17f, true)); card.addView(status); card.addView(ui.spacer(10f)); card.addView(toggle)
        card.addView(ui.spacer(6f)); card.addView(ui.button("Configure sidebar", filled = false) { m.open(FloatingConfigFragment()) })
        col.addView(card)

        col.addView(ui.section("Quick capture"))
        col.addView(ui.group(
            ui.tapRow("New note", null) { m.open(EditorFragment.newInstance(null)) },
            ui.tapRow("Capture from clipboard", null) { m.open(ClipboardFragment()) },
            ui.tapRow("Record a voice note", null) { m.open(VoiceFragment()) },
            ui.tapRow("Set a reminder", null) { m.open(RemindersFragment()) },
        ))
        val counts = ui.tv("", 14f, false, ui.sub)
        col.addView(ui.section("Library")); col.addView(counts)
        col.addView(ui.group(
            ui.tapRow("All notes", null) { m.open(NotesFragment.of(NotesFragment.Source.ALL)) },
            ui.tapRow("Pinned", null) { m.open(NotesFragment.of(NotesFragment.Source.PINNED)) },
            ui.tapRow("Categories and folders", null) { m.open(CategoriesFragment()) },
            ui.tapRow("Search", null) { m.open(SearchFragment()) },
        ))
        viewLifecycleOwner.lifecycleScope.launch {
            val notes = m.repo.recent(1000).size
            val rem = db.reminders().allActive().size
            counts.text = "$notes notes   $rem active reminders"
        }
        return ui.scroll(col)
    }
}
