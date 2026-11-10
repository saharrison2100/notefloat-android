package com.notefloat.app.floating

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.notefloat.app.clipboard.ClipboardCapture
import com.notefloat.app.notes.NoteRepository
import com.notefloat.app.notes.RichText
import com.notefloat.app.reminders.ReminderScheduler
import com.notefloat.app.storage.Prefs
import com.notefloat.app.ui.MainActivity
import com.notefloat.app.ui.PermissionGateActivity
import com.notefloat.app.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The expanded sidebar panel, drawn in a TYPE_APPLICATION_OVERLAY window anchored beside the handle.
 * Tabs: Capture (text), Voice, Notes (recent), Remind, Search. Header actions: clipboard, open app, close.
 * Tapping outside collapses it when "auto collapse" is on.
 * Text typed here is saved as a draft every moment it changes, so nothing is lost if the process dies.
 */
class PanelController(private val ctx: Context, private val prefs: Prefs, private val onClose: () -> Unit) {

    enum class Tab { CAPTURE, VOICE, NOTES, REMIND, SEARCH }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private val repo = NoteRepository(ctx)
    private val recorder = VoiceRecorder(ctx)

    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var body: FrameLayout? = null
    private var tab = Tab.CAPTURE
    private var focusable = false

    private var input: EditText? = null
    private var tickJob: Job? = null
    private var searchJob: Job? = null
    private var tabViews = mutableMapOf<Tab, TextView>()
    private var draftWritePending: Runnable? = null

    val isShown get() = root != null

    fun toggle() { if (isShown) hide() else show(tab) }

    fun show(t: Tab = Tab.CAPTURE) {
        tab = t
        if (!isShown) build()
        renderTab()
    }

    fun hide() {
        flushDraft()
        tickJob?.cancel(); searchJob?.cancel()
        if (recorder.state != VoiceRecorder.State.IDLE) { /* keep recording until the user stops it; panel just closes */ }
        input?.let { hideKeyboard(it) }
        root?.let { runCatching { wm.removeView(it) } }
        root = null; lp = null; input = null; body = null; focusable = false
        onClose()
    }

    fun destroy() {
        recorder.cancel()
        hide(); scope.coroutineContext[Job]?.cancel()
    }

    /** Re-anchor next to the handle after it moves. */
    fun reanchor() { if (isShown) hide() }

    // ------------------------------------------------------------------ window

    private fun build() {
        val p = Glass.palette(ctx, prefs.highContrast)
        val sw = ctx.resources.displayMetrics.widthPixels
        val sh = ctx.resources.displayMetrics.heightPixels
        val w = minOf(Glass.dpi(ctx, prefs.panelWidthDp.toFloat()), sw - Glass.dpi(ctx, 24f))
        val h = minOf(Glass.dpi(ctx, prefs.panelHeightDp.toFloat()), (sh * 0.78f).toInt())

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.rounded(p.panel, Glass.dp(ctx, 24f), p.stroke, Glass.dpi(ctx, 1f))
            elevation = Glass.dp(ctx, 14f)
            setPadding(Glass.dpi(ctx, 14f), Glass.dpi(ctx, 12f), Glass.dpi(ctx, 14f), Glass.dpi(ctx, 12f))
        }
        card.addView(header(p))
        card.addView(tabBar(p))
        body = FrameLayout(ctx)
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Glass.dpi(ctx, 8f) })

        val frame = FrameLayout(ctx)
        frame.addView(card, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && prefs.autoCollapse) { hide(); true } else false
        }
        // card swallows touches so they don't reach the frame
        card.setOnTouchListener { _, _ -> false }
        card.isClickable = true

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Glass.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND.let { 0 },
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        Glass.applyBlur(params, 0)
        lp = params; root = frame
        card.alpha = 0f; card.translationX = if (prefs.edge == "LEFT") -40f else 40f
        // anchor card toward the handle's side
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (prefs.edge == "LEFT") Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            marginStart = Glass.dpi(ctx, 8f); marginEnd = Glass.dpi(ctx, 40f).let { if (prefs.edge == "RIGHT") it else Glass.dpi(ctx, 8f) }
            if (prefs.edge == "LEFT") marginStart = Glass.dpi(ctx, 40f)
        }
        try { wm.addView(frame, params) } catch (e: Exception) { root = null; Toast.makeText(ctx, "Could not open the panel.", Toast.LENGTH_SHORT).show(); return }
        card.animate().alpha(1f).translationX(0f).setDuration(160).start()
    }

    private fun setFocusable(f: Boolean) {
        val params = lp ?: return; val r = root ?: return
        if (focusable == f) return
        focusable = f
        params.flags = if (f) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(r, params) }
    }

    private fun header(p: Glass.Palette): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("NoteFloat", p.text, 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (prefs.clipboard) row.addView(chip("Clipboard", p) { captureClipboard() })
        row.addView(chip("Open", p) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); hide()
        })
        row.addView(chip("\u2715", p) { hide() }.apply { contentDescription = "Close panel" })
        return row
    }

    private fun tabBar(p: Glass.Palette): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.setPadding(0, Glass.dpi(ctx, 8f), 0, 0)
        tabViews.clear()
        val items = buildList {
            if (prefs.textNotes) add(Tab.CAPTURE to "Note")
            if (prefs.voiceNotes) add(Tab.VOICE to "Voice")
            add(Tab.NOTES to "Recent")
            if (prefs.reminders) add(Tab.REMIND to "Remind")
            add(Tab.SEARCH to "Search")
        }
        items.forEach { (t, name) ->
            val tv = label(name, p.sub, 13f).apply {
                gravity = Gravity.CENTER; setPadding(0, Glass.dpi(ctx, 8f), 0, Glass.dpi(ctx, 8f))
                setOnClickListener { saveDraftNow(); tab = t; renderTab() }
            }
            tabViews[t] = tv
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (tab !in tabViews.keys) tab = tabViews.keys.first()
        return row
    }

    private fun renderTab() {
        val p = Glass.palette(ctx, prefs.highContrast)
        if (tab !in tabViews.keys) tab = tabViews.keys.first()
        tabViews.forEach { (t, v) ->
            val sel = t == tab
            v.setTextColor(if (sel) p.accent else p.sub); v.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.background = if (sel) Glass.rounded(p.field, Glass.dp(ctx, 12f)) else null
        }
        tickJob?.cancel(); input = null
        body?.removeAllViews()
        val content = when (tab) {
            Tab.CAPTURE -> captureView(p)
            Tab.VOICE -> voiceView(p)
            Tab.NOTES -> recentView(p)
            Tab.REMIND -> remindView(p)
            Tab.SEARCH -> searchView(p)
        }
        body?.addView(content, FrameLayout.LayoutParams(-1, -1))
        // only the typing tabs need keyboard focus; everything else stays non-focusable so other apps keep input
        val wantsKeys = tab == Tab.CAPTURE || tab == Tab.SEARCH || tab == Tab.REMIND
        setFocusable(wantsKeys)
        if (wantsKeys) input?.let { et -> et.post { et.requestFocus(); (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) } }
    }

    // ------------------------------------------------------------------ tabs

    private fun captureView(p: Glass.Palette, prefill: String? = null): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val et = field(p, "Write it down. Use the bar for bold, lists and checklists.", multiline = true)
        input = et
        et.setText(prefill ?: repo.loadDraft(null).orEmpty())
        et.setSelection(et.text.length)
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scheduleDraft(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        et.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN) RichText.continueList(et.text, et.selectionStart) else false
        }
        col.addView(et, LinearLayout.LayoutParams(-1, 0, 1f))

        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tools.setPadding(0, Glass.dpi(ctx, 8f), 0, 0)
        fun tool(t: String, f: () -> Unit) = tools.addView(chip(t, p) { f() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tool("B") { RichText.wrapSelection(et.text, et.selectionStart, et.selectionEnd, "**") }
        tool("I") { RichText.wrapSelection(et.text, et.selectionStart, et.selectionEnd, "_") }
        tool("H") { RichText.toggleLinePrefix(et.text, et.selectionStart, "# ") }
        tool("\u2022") { RichText.toggleLinePrefix(et.text, et.selectionStart, "- ") }
        tool("1.") { RichText.toggleLinePrefix(et.text, et.selectionStart, "1. ") }
        tool("\u2610") { RichText.toggleLinePrefix(et.text, et.selectionStart, "[ ] ") }
        col.addView(tools)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.setPadding(0, Glass.dpi(ctx, 8f), 0, 0)
        row.addView(label("Auto-saved as a draft", p.sub, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(primary("Save note", p) {
            val text = et.text.toString()
            if (text.isBlank()) { toast("Nothing to save yet."); return@primary }
            scope.launch {
                repo.save(null, text); repo.clearDraft(null); et.setText("")
                toast("Saved to NoteFloat"); if (prefs.autoCollapse) hide() else renderTab()
            }
        })
        col.addView(row)
        return col
    }

    private fun voiceView(p: Glass.Palette): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val timer = label(VoiceRecorder.fmt(recorder.elapsedMs), p.text, 38f, bold = true).apply { gravity = Gravity.CENTER }
        val status = label("", p.sub, 13f).apply { gravity = Gravity.CENTER }
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        fun refresh() {
            timer.text = VoiceRecorder.fmt(recorder.elapsedMs)
            status.text = when (recorder.state) {
                VoiceRecorder.State.IDLE -> "Tap Record to start. Recording only starts when you tap."
                VoiceRecorder.State.RECORDING -> "Recording..."
                VoiceRecorder.State.PAUSED -> "Paused"
            }
            controls.removeAllViews()
            when (recorder.state) {
                VoiceRecorder.State.IDLE -> controls.addView(primary("Record", p) { startRecording { renderTab() } })
                VoiceRecorder.State.RECORDING -> {
                    controls.addView(chip("Pause", p) { recorder.pause(); renderTab() })
                    controls.addView(primary("Stop & save", p) { stopRecording() })
                }
                VoiceRecorder.State.PAUSED -> {
                    controls.addView(chip("Resume", p) { recorder.resume(); renderTab() })
                    controls.addView(primary("Stop & save", p) { stopRecording() })
                }
            }
        }
        refresh()
        col.addView(View(ctx), LinearLayout.LayoutParams(1, Glass.dpi(ctx, 18f)))
        col.addView(timer); col.addView(status)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, Glass.dpi(ctx, 16f)))
        col.addView(controls)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        if (recorder.state != VoiceRecorder.State.IDLE) {
            col.addView(chip("Discard", p) { recorder.cancel(); renderTab() })
            tickJob = scope.launch { while (true) { delay(250); timer.text = VoiceRecorder.fmt(recorder.elapsedMs) } }
        }
        col.addView(label("Recordings are saved on this device. Manage them in the app.", p.sub, 11f).apply { gravity = Gravity.CENTER })
        return col
    }

    private fun startRecording(after: () -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // microphone permission is requested only now, from an explicit tap
            PermissionGateActivity.request(ctx, PermissionGateActivity.MIC); hide(); return
        }
        if (!recorder.start()) toast("Could not start recording.")
        after()
    }

    private fun stopRecording() {
        scope.launch {
            val rec = recorder.stop()
            toast(if (rec != null) "Saved \"${rec.name}\"" else "Recording was too short.")
            renderTab()
        }
    }

    private fun recentView(p: Glass.Palette): View {
        val sv = ScrollView(ctx); val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }; sv.addView(list)
        scope.launch {
            val notes = repo.recent(12)
            if (notes.isEmpty()) list.addView(label("No notes yet. Write one in the Note tab.", p.sub, 13f).apply { setPadding(0, Glass.dpi(ctx, 16f), 0, 0) })
            notes.forEach { n -> list.addView(noteRow(p, n.id, n.title.ifBlank { "Untitled" }, RichText.plain(n.body).lines().drop(1).joinToString(" ").take(90), n.pinned)) }
        }
        return sv
    }

    private fun noteRow(p: Glass.Palette, id: String, title: String, sub: String, pinned: Boolean): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.rounded(p.field, Glass.dp(ctx, 14f))
            setPadding(Glass.dpi(ctx, 12f), Glass.dpi(ctx, 10f), Glass.dpi(ctx, 12f), Glass.dpi(ctx, 10f))
            setOnClickListener {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("noteId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); hide()
            }
        }
        c.addView(label((if (pinned) "\u2605 " else "") + title, p.text, 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) c.addView(label(sub, p.sub, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Glass.dpi(ctx, 6f) }
        return c
    }

    private fun searchView(p: Glass.Palette): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val et = field(p, "Search notes", multiline = false); input = et
        val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(ctx).apply { addView(results) }
        col.addView(et); col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Glass.dpi(ctx, 8f) })
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(150)
                    val q = s?.toString().orEmpty()
                    results.removeAllViews()
                    val found = repo.search(q)
                    if (q.isNotBlank() && found.isEmpty()) results.addView(label("Nothing matched.", p.sub, 13f))
                    found.take(20).forEach { n -> results.addView(noteRow(p, n.id, n.title.ifBlank { "Untitled" }, RichText.plain(n.body).take(90), n.pinned)) }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        return col
    }

    private var remindAt: Calendar = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    private var remindRepeat = "NONE"

    private fun remindView(p: Glass.Palette): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val et = field(p, "What should I remind you about?", multiline = false); input = et
        col.addView(et)
        val whenLabel = label("", p.text, 14f).apply { setPadding(0, Glass.dpi(ctx, 12f), 0, Glass.dpi(ctx, 6f)) }
        fun paint() { whenLabel.text = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(remindAt.time) + "   repeat: " + remindRepeat.lowercase() }
        paint(); col.addView(whenLabel)

        val quick = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun inMin(label: String, min: Int) = quick.addView(chip(label, p) { remindAt = Calendar.getInstance().apply { add(Calendar.MINUTE, min) }; paint() })
        inMin("+15 min", 15); inMin("+1 hr", 60); inMin("+3 hr", 180)
        quick.addView(chip("Tomorrow 9", p) { remindAt = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }; paint() })
        col.addView(quick)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(chip("Pick time", p) {
            // dialog windows from a service need the overlay window type
            val d = TimePickerDialog(android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert), { _, h, m ->
                remindAt = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }; paint()
            }, remindAt.get(Calendar.HOUR_OF_DAY), remindAt.get(Calendar.MINUTE), true)
            d.window?.setType(Glass.overlayType()); d.show()
        })
        row2.addView(chip("Repeat", p) { remindRepeat = when (remindRepeat) { "NONE" -> "DAILY"; "DAILY" -> "WEEKLY"; "WEEKLY" -> "MONTHLY"; else -> "NONE" }; paint() })
        col.addView(row2)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(primary("Set reminder", p) {
            val title = et.text.toString().trim()
            if (title.isEmpty()) { toast("Add a title first."); return@primary }
            if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                PermissionGateActivity.request(ctx, PermissionGateActivity.NOTIFY); hide(); return@primary
            }
            scope.launch {
                ReminderScheduler.create(ctx, title, remindAt.timeInMillis, remindRepeat, null)
                toast("Reminder set"); et.setText(""); if (prefs.autoCollapse) hide()
            }
        })
        return col
    }

    // ------------------------------------------------------------------ clipboard

    private fun captureClipboard() {
        // Android 10+ allows clipboard reads only while this window has input focus; this runs from a tap.
        setFocusable(true)
        root?.postDelayed({
            when (val r = ClipboardCapture.readNow(ctx)) {
                is ClipboardCapture.Result.Text -> { tab = Tab.CAPTURE; renderTab(); input?.setText(r.text); input?.setSelection(r.text.length)
                    toast("Clipboard loaded. Edit it, then Save note.") ; scope.launch { ClipboardCapture.record(ctx, r.text, null) } }
                ClipboardCapture.Result.Sensitive -> toast("The copied content is marked sensitive, so NoteFloat did not read it.")
                ClipboardCapture.Result.NotText -> toast("The clipboard does not hold text.")
                ClipboardCapture.Result.Empty -> toast("Clipboard is empty, or Android blocked access. Open NoteFloat and try Clipboard there.")
            }
        }, 120)
    }

    // ------------------------------------------------------------------ drafts

    private fun scheduleDraft(text: String) {
        draftWritePending?.let { root?.removeCallbacks(it) }
        draftWritePending = Runnable { repo.saveDraft(null, text) }
        root?.postDelayed(draftWritePending, 250)
    }
    private fun saveDraftNow() { input?.let { if (tab == Tab.CAPTURE) repo.saveDraft(null, it.text.toString()) } }
    private fun flushDraft() { draftWritePending?.let { root?.removeCallbacks(it) }; saveDraftNow() }

    // ------------------------------------------------------------------ view helpers

    private fun hideKeyboard(v: View) { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
    private fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    private fun label(t: String, color: Int, sp: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * prefs.textScale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(t: String, p: Glass.Palette, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * prefs.textScale); gravity = Gravity.CENTER
        setPadding(Glass.dpi(ctx, 10f), Glass.dpi(ctx, 8f), Glass.dpi(ctx, 10f), Glass.dpi(ctx, 8f))
        background = Glass.rounded(p.field, Glass.dp(ctx, 12f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = Glass.dpi(ctx, 4f); marginEnd = Glass.dpi(ctx, 4f); topMargin = Glass.dpi(ctx, 2f) }
        setOnClickListener { onClick() }
    }

    private fun primary(t: String, p: Glass.Palette, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; setTextColor(android.graphics.Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * prefs.textScale); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setPadding(Glass.dpi(ctx, 18f), Glass.dpi(ctx, 10f), Glass.dpi(ctx, 18f), Glass.dpi(ctx, 10f))
        background = Glass.rounded(p.accent, Glass.dp(ctx, 14f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = Glass.dpi(ctx, 4f); marginEnd = Glass.dpi(ctx, 4f) }
        setOnClickListener { onClick() }
    }

    private fun field(p: Glass.Palette, hint: String, multiline: Boolean) = EditText(ctx).apply {
        this.hint = hint; setHintTextColor(p.sub); setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * prefs.textScale)
        background = Glass.rounded(p.field, Glass.dp(ctx, 14f))
        setPadding(Glass.dpi(ctx, 12f), Glass.dpi(ctx, 10f), Glass.dpi(ctx, 12f), Glass.dpi(ctx, 10f))
        if (multiline) { gravity = Gravity.TOP or Gravity.START; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        else { maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE }
    }
}
