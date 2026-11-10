package com.notefloat.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.notefloat.app.R
import com.notefloat.app.storage.Prefs

/** Tiny view toolkit so every screen shares spacing, colour and text scaling (accessibility). */
class Ui(val c: Context, val prefs: Prefs) {
    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
    fun color(id: Int) = ContextCompat.getColor(c, id)
    val ink get() = color(R.color.nf_on_surface)
    val sub get() = color(R.color.nf_sub)
    val bg get() = color(R.color.nf_bg)
    val surface get() = color(R.color.nf_surface)
    val primary get() = color(R.color.nf_primary)
    val divider get() = color(R.color.nf_divider)

    fun card(radius: Float = 18f) = GradientDrawable().apply {
        setColor(surface); cornerRadius = dp(radius).toFloat()
        setStroke(dp(if (prefs.highContrast) 2f else 1f), if (prefs.highContrast) ink else divider)
    }

    fun tv(t: CharSequence, sp: Float = 15f, bold: Boolean = false, color: Int = ink) = TextView(c).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * prefs.textScale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    fun title(t: String) = tv(t, 26f, true).apply { setPadding(0, dp(4f), 0, dp(2f)) }
    fun section(t: String) = tv(t.uppercase(), 12f, true, sub).apply { setPadding(dp(4f), dp(18f), 0, dp(6f)); letterSpacing = 0.08f }
    fun body(t: CharSequence) = tv(t, 14f, false, sub).apply { setPadding(0, dp(2f), 0, dp(6f)) }

    fun button(t: String, filled: Boolean = true, onClick: () -> Unit) = MaterialButton(c).apply {
        text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * prefs.textScale)
        cornerRadius = dp(14f); minimumHeight = dp(48f)
        if (!filled) { backgroundTintList = android.content.res.ColorStateList.valueOf(surface); setTextColor(ink); strokeWidth = dp(1f); strokeColor = android.content.res.ColorStateList.valueOf(divider) }
        setOnClickListener { onClick() }
    }

    fun edit(hint: String, multiline: Boolean = false, value: String = "") = EditText(c).apply {
        this.hint = hint; setText(value); setTextColor(ink); setHintTextColor(sub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * prefs.textScale); background = card(14f)
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        if (multiline) { gravity = Gravity.TOP; minLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        else { maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    }

    fun switchRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(12f), dp(12f), dp(12f)) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 15f, true)); if (desc != null) col.addView(tv(desc, 12f, false, sub))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialSwitch(c).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) }; contentDescription = label })
        return row
    }

    fun group(vararg rows: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = card()
        rows.forEachIndexed { i, r -> if (i > 0) addView(View(c).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1f)).apply { marginStart = dp(16f) }); addView(r) }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun tapRow(label: String, value: String?, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        addView(tv(label, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(tv(value, 14f, false, sub))
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        val out = TypedValue(); c.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true); foreground = ContextCompat.getDrawable(c, out.resourceId)
    }

    fun slider(label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(12f), dp(16f), dp(8f)) }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val v = tv(fmt(value), 14f, false, sub)
        head.addView(tv(label, 15f, true), LinearLayout.LayoutParams(0, -2, 1f)); head.addView(v)
        val sb = SeekBar(c).apply {
            this.max = max - min; progress = value - min; contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { v.text = fmt(p + min); if (user) onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onChange(progress + min) }
            })
        }
        col.addView(head); col.addView(sb); return col
    }

    fun chipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12f), dp(8f), dp(12f), dp(12f)) }
        options.forEach { (key, label) ->
            val sel = key == selected
            row.addView(tv(label, 13f, sel, if (sel) color(R.color.nf_on_primary) else ink).apply {
                gravity = Gravity.CENTER; setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(if (sel) primary else bg) }
                setOnClickListener { onPick(key) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        }
        return row
    }

    fun spacer(h: Float) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun scroll(content: LinearLayout): ScrollView = ScrollView(c).apply {
        isFillViewport = true; setBackgroundColor(bg); content.setPadding(dp(18f), dp(14f), dp(18f), dp(28f)); addView(content)
    }
    fun column() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        val NOTE_COLORS = intArrayOf(0x00000000, 0xFFE57373.toInt(), 0xFFFFB74D.toInt(), 0xFFFFF176.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFBA68C8.toInt())
        val COLOR_NAMES = listOf("None", "Red", "Orange", "Yellow", "Green", "Blue", "Purple")
    }
}
