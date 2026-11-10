package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Screen 8: Categories and folders. */
class CategoriesFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        val col = ui.column(); col.addView(ui.title("Categories"))
        col.addView(ui.body("Group notes into folders. Tap to open, long press to rename or delete."))
        col.addView(ui.button("New category") { ask("New category", "") { name -> m.lifecycleScope.launch { m.repo.addCategory(name); com.notefloat.app.networking.SyncWorker.syncSoon(requireContext().applicationContext) } } })
        val list = ui.column().apply { setPadding(0, ui.dp(12f), 0, 0) }; col.addView(list)
        viewLifecycleOwner.lifecycleScope.launch {
            m.repo.categories.collect { cats ->
                list.removeAllViews()
                if (cats.isEmpty()) list.addView(ui.body("No categories yet."))
                cats.forEach { cat ->
                    list.addView(ui.tapRow(cat.name, null) { m.open(NotesFragment.of(NotesFragment.Source.CATEGORY, cat.id, cat.name)) }.apply {
                        background = ui.card(); layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8f) }
                        setOnLongClickListener {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(cat.name).setItems(arrayOf("Rename", "Delete")) { _, w ->
                                if (w == 0) ask("Rename", cat.name) { n -> m.lifecycleScope.launch { m.repo.renameCategory(cat.id, n) } }
                                else m.lifecycleScope.launch { m.repo.deleteCategory(cat.id) }
                            }.show(); true }
                    })
                }
            }
        }
        return ui.scroll(col)
    }
    private fun ask(title: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(requireContext()).apply { setText(initial); setSingleLine() }
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }.setNegativeButton("Cancel", null).show()
    }
}
