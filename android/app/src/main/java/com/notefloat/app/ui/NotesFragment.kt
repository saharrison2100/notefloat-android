package com.notefloat.app.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notefloat.app.notes.RichText
import com.notefloat.app.storage.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Screens 3, 9, 10 (and trash / category views): one list, different sources. */
class NotesFragment : Fragment() {
    enum class Source { ALL, PINNED, ARCHIVED, TRASH, CATEGORY }

    companion object {
        fun of(s: Source, categoryId: String? = null, name: String? = null) = NotesFragment().apply {
            arguments = Bundle().apply { putString("s", s.name); putString("cat", categoryId); putString("name", name) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        val source = Source.valueOf(arguments?.getString("s") ?: "ALL")
        val catId = arguments?.getString("cat")
        val heading = when (source) { Source.ALL -> "All notes"; Source.PINNED -> "Pinned"; Source.ARCHIVED -> "Archived"; Source.TRASH -> "Trash"; Source.CATEGORY -> arguments?.getString("name") ?: "Category" }

        val root = FrameLayout(requireContext()).apply { setBackgroundColor(ui.bg) }
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18f), ui.dp(14f), ui.dp(18f), 0) }
        col.addView(ui.title(heading))
        if (source == Source.TRASH) col.addView(ui.body("Notes here are deleted for good after 30 days. Tap a note to restore it."))
        val empty = ui.body(when (source) { Source.ALL -> "No notes yet. Tap + to write one, or use the floating sidebar."; Source.PINNED -> "Pin a note to keep it here."; Source.ARCHIVED -> "Archived notes appear here."; Source.TRASH -> "Trash is empty."; else -> "No notes in this category." }).apply { visibility = View.GONE; setPadding(0, ui.dp(24f), 0, 0) }
        col.addView(empty)
        val list = RecyclerView(requireContext()).apply { layoutManager = LinearLayoutManager(requireContext()); clipToPadding = false; setPadding(0, ui.dp(8f), 0, ui.dp(96f)) }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(col)

        val adapter = NoteAdapter(ui, source == Source.TRASH) { n -> if (source == Source.TRASH) trashMenu(n) else m.open(EditorFragment.newInstance(n.id)) }
        list.adapter = adapter
        val flow: Flow<List<NoteEntity>> = when (source) {
            Source.ALL -> m.repo.active; Source.PINNED -> m.repo.pinned; Source.ARCHIVED -> m.repo.archived; Source.TRASH -> m.repo.trash; Source.CATEGORY -> m.repo.byCategory(catId!!)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            flow.collect { notes -> adapter.submit(notes); empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE }
        }
        if (source == Source.ALL || source == Source.CATEGORY) {
            root.addView(com.google.android.material.floatingactionbutton.FloatingActionButton(requireContext()).apply {
                setImageResource(android.R.drawable.ic_input_add); contentDescription = "New note"
                setOnClickListener { m.open(EditorFragment.newInstance(null, null, catId)) }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(20f), ui.dp(20f)) })
        }
        return root
    }

    private fun trashMenu(n: NoteEntity) {
        val m = activity as MainActivity
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(n.title.ifBlank { "Untitled" })
            .setItems(arrayOf("Restore", "Delete forever")) { _, w ->
                m.lifecycleScope.launch { if (w == 0) m.repo.restore(n.id) else m.repo.deleteForever(n.id)
                    com.notefloat.app.networking.SyncWorker.syncSoon(requireContext().applicationContext) }
            }.show()
    }
}

class NoteAdapter(private val ui: Ui, private val trash: Boolean, private val onClick: (NoteEntity) -> Unit) : RecyclerView.Adapter<NoteAdapter.VH>() {
    private var items: List<NoteEntity> = emptyList()
    fun submit(l: List<NoteEntity>) { items = l; notifyDataSetChanged() }
    class VH(val v: LinearLayout, val t: android.widget.TextView, val b: android.widget.TextView, val meta: android.widget.TextView, val dot: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val v = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL; background = ui.card(); setPadding(ui.dp(16f), ui.dp(14f), ui.dp(16f), ui.dp(12f))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10f) } }
        val head = LinearLayout(p.context).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = View(p.context).apply { visibility = View.GONE }
        head.addView(dot, LinearLayout.LayoutParams(ui.dp(10f), ui.dp(10f)).apply { marginEnd = ui.dp(8f) })
        val t = ui.tv("", 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f)); v.addView(head)
        val b = ui.tv("", 13f, false, ui.sub).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        v.addView(b); val meta = ui.tv("", 11f, false, ui.sub).apply { setPadding(0, ui.dp(6f), 0, 0) }; v.addView(meta)
        return VH(v, t, b, meta, dot)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val n = items[i]
        h.t.text = (if (n.pinned) "\u2605 " else "") + n.title.ifBlank { "Untitled" }
        val rest = n.body.lines().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
        h.b.text = RichText.render(rest); h.b.visibility = if (rest.isBlank()) View.GONE else View.VISIBLE
        h.meta.text = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(n.updatedAt)) + (if (n.conflictOf != null) "   conflict copy" else "")
        val col = Ui.NOTE_COLORS[n.colorLabel.coerceIn(0, 6)]
        h.dot.visibility = if (n.colorLabel == 0) View.GONE else View.VISIBLE
        h.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
        h.v.setOnClickListener { onClick(n) }
    }
}
