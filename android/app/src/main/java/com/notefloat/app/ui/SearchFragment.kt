package com.notefloat.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.notes.RichText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen 11: Search. */
class SearchFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        val col = ui.column(); col.addView(ui.title("Search"))
        val et = ui.edit("Search title and text"); col.addView(et)
        val results = ui.column().apply { setPadding(0, ui.dp(10f), 0, 0) }; col.addView(results)
        var job: Job? = null
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                job?.cancel()
                job = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200); results.removeAllViews()
                    val q = e?.toString().orEmpty(); val found = m.repo.search(q)
                    if (q.isNotBlank() && found.isEmpty()) results.addView(ui.body("Nothing matched."))
                    found.forEach { n ->
                        results.addView(ui.column().apply {
                            background = ui.card(); setPadding(ui.dp(16f), ui.dp(12f), ui.dp(16f), ui.dp(12f))
                            addView(ui.tv(n.title.ifBlank { "Untitled" }, 15f, true)); addView(ui.tv(RichText.plain(n.body).take(110), 13f, false, ui.sub))
                            setOnClickListener { m.open(EditorFragment.newInstance(n.id)) }
                            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8f) }
                        })
                    }
                }
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        et.requestFocus()
        return ui.scroll(col)
    }
}
