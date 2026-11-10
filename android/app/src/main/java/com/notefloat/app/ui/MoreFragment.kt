package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class MoreFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        val col = ui.column(); col.addView(ui.title("More"))
        col.addView(ui.section("Library"))
        col.addView(ui.group(
            ui.tapRow("Search", null) { m.open(SearchFragment()) },
            ui.tapRow("Pinned notes", null) { m.open(NotesFragment.of(NotesFragment.Source.PINNED)) },
            ui.tapRow("Categories and folders", null) { m.open(CategoriesFragment()) },
            ui.tapRow("Clipboard history", null) { m.open(ClipboardFragment()) },
            ui.tapRow("Archived notes", null) { m.open(NotesFragment.of(NotesFragment.Source.ARCHIVED)) },
            ui.tapRow("Trash", null) { m.open(NotesFragment.of(NotesFragment.Source.TRASH)) },
        ))
        col.addView(ui.section("Sidebar and app"))
        col.addView(ui.group(
            ui.tapRow("Floating sidebar", null) { m.open(FloatingConfigFragment()) },
            ui.tapRow("Settings", null) { m.open(SettingsFragment()) },
            ui.tapRow("Storage and sync", null) { m.open(StorageSyncFragment()) },
            ui.tapRow("Google and Drive", null) { m.open(GoogleAccountFragment()) },
            ui.tapRow("Welcome tour", null) { m.open(OnboardingFragment()) },
        ))
        col.addView(ui.body("NoteFloat 1.2.0"))
        return ui.scroll(col)
    }
}
