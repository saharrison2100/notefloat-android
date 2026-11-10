package com.notefloat.app.notes

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Notes are stored as plain text with a small, readable markup so they sync and export cleanly:
 *   **bold**   _italic_   "# " heading   "- " bullet   "1. " numbered   "[ ] " / "[x] " checklist
 * This object renders that markup for display and provides editor helpers that operate on the
 * current selection or line.
 */
object RichText {

    fun render(src: String): CharSequence {
        val out = SpannableStringBuilder()
        src.split("\n").forEachIndexed { i, raw ->
            if (i > 0) out.append('\n')
            var line = raw
            var headingStart = -1
            when {
                line.startsWith("# ") -> { line = line.removePrefix("# "); headingStart = out.length }
                line.startsWith("- ") -> line = "\u2022 " + line.removePrefix("- ")
                line.startsWith("[ ] ") -> line = "\u2610 " + line.removePrefix("[ ] ")
                line.startsWith("[x] ") || line.startsWith("[X] ") -> line = "\u2611 " + line.drop(4)
            }
            val lineStart = out.length
            appendInline(out, line)
            if (headingStart >= 0) {
                out.setSpan(RelativeSizeSpan(1.35f), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> {
                    val end = line.indexOf("**", i + 2)
                    if (end > 0) { span(out, line.substring(i + 2, end), Typeface.BOLD); i = end + 2; continue }
                }
                line[i] == '_' -> {
                    val end = line.indexOf('_', i + 1)
                    if (end > i + 1) { span(out, line.substring(i + 1, end), Typeface.ITALIC); i = end + 1; continue }
                }
            }
            out.append(line[i]); i++
        }
    }

    private fun span(out: SpannableStringBuilder, text: String, style: Int) {
        val s = out.length
        out.append(text)
        out.setSpan(StyleSpan(style), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ---- editor helpers (operate on raw markup text) ----

    fun wrapSelection(text: Editable, start: Int, end: Int, marker: String) {
        val a = minOf(start, end); val b = maxOf(start, end)
        if (a == b) { text.insert(a, marker + marker); return }
        val selected = text.substring(a, b)
        if (selected.startsWith(marker) && selected.endsWith(marker) && selected.length >= marker.length * 2) {
            text.replace(a, b, selected.removePrefix(marker).removeSuffix(marker))
        } else text.replace(a, b, marker + selected + marker)
    }

    /** Toggle a line prefix ("# ", "- ", "[ ] ") on the line containing [cursor]. "1. " numbers the block. */
    fun toggleLinePrefix(text: Editable, cursor: Int, prefix: String) {
        val s = text.toString()
        val lineStart = s.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
        val lineEnd = s.indexOf('\n', lineStart).let { if (it < 0) s.length else it }
        val line = s.substring(lineStart, lineEnd)
        val known = listOf("# ", "- ", "[ ] ", "[x] ", "[X] ") + Regex("^\\d+\\. ").find(line)?.value.let { listOfNotNull(it) }
        val existing = known.firstOrNull { line.startsWith(it) }
        val numbered = prefix == "1. "
        val newPrefix = if (numbered) nextNumber(s, lineStart) else prefix
        text.replace(lineStart, lineEnd,
            when {
                existing != null && (existing == prefix || (numbered && existing.matches(Regex("\\d+\\. ")))) -> line.removePrefix(existing)
                existing != null -> newPrefix + line.removePrefix(existing)
                else -> newPrefix + line
            })
    }

    private fun nextNumber(s: String, lineStart: Int): String {
        if (lineStart == 0) return "1. "
        val prevEnd = lineStart - 1
        val prevStart = s.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
        val m = Regex("^(\\d+)\\. ").find(s.substring(prevStart, prevEnd))
        return if (m != null) "${m.groupValues[1].toInt() + 1}. " else "1. "
    }

    /** Called when Enter is pressed: continue bullets, numbers and checklists. Returns true if handled. */
    fun continueList(text: Editable, cursor: Int): Boolean {
        val s = text.toString()
        val lineStart = s.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
        val line = s.substring(lineStart, cursor)
        val bullet = listOf("- ", "[ ] ").firstOrNull { line.startsWith(it) }
        val checked = if (line.startsWith("[x] ") || line.startsWith("[X] ")) "[ ] " else null
        val num = Regex("^(\\d+)\\. ").find(line)
        val marker = bullet ?: checked ?: num?.let { "${it.groupValues[1].toInt() + 1}. " } ?: return false
        val lead = bullet ?: checked ?: num!!.value
        if (line.trim() == lead.trim()) { text.delete(lineStart, cursor); return true } // empty item ends the list
        text.insert(cursor, "\n$marker")
        return true
    }

    /** Flip the checkbox on the line at [lineIndex]. */
    fun toggleCheckbox(src: String, lineIndex: Int): String {
        val lines = src.split("\n").toMutableList()
        if (lineIndex !in lines.indices) return src
        val l = lines[lineIndex]
        lines[lineIndex] = when {
            l.startsWith("[ ] ") -> "[x] " + l.drop(4)
            l.startsWith("[x] ") || l.startsWith("[X] ") -> "[ ] " + l.drop(4)
            else -> l
        }
        return lines.joinToString("\n")
    }

    fun titleOf(body: String): String =
        body.lineSequence().map { it.trim().removePrefix("# ").removePrefix("- ").removePrefix("[ ] ").removePrefix("[x] ").trim() }
            .firstOrNull { it.isNotEmpty() }?.take(80) ?: ""

    fun plain(body: String): String = body.replace("**", "").replace(Regex("(?<!\\w)_|_(?!\\w)"), "")
}
