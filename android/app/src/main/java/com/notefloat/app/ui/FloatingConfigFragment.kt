package com.notefloat.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.notefloat.app.floating.OverlayService

/** Screen 13: Floating sidebar configuration. */
class FloatingConfigFragment : Fragment() {
    private var col: android.widget.LinearLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        col = ui.column(); build(m, ui); return ui.scroll(col!!)
    }

    override fun onResume() { super.onResume(); val m = activity as? MainActivity ?: return; build(m, Ui(requireContext(), m.prefs)) }

    private fun ping() { if (OverlayService.running) OverlayService.start(requireContext(), OverlayService.ACTION_REFRESH) }

    private fun build(m: MainActivity, ui: Ui) {
        val col = col ?: return; val p = m.prefs; col.removeAllViews()
        col.addView(ui.title("Floating sidebar"))
        val allowed = OverlayService.canDraw(requireContext())

        if (!allowed) {
            col.addView(ui.body("To float over other apps, Android needs you to switch on \"Display over other apps\" for NoteFloat. It is only used to draw the handle and panel."))
            col.addView(ui.button("Open Android settings") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))) })
        } else {
            col.addView(ui.group(
                ui.switchRow("Sidebar on", if (OverlayService.running) "Running over other apps" else "Off", OverlayService.running) { on ->
                    if (on) OverlayService.start(requireContext()) else OverlayService.stop(requireContext()); col.postDelayed({ build(m, ui) }, 500) },
                ui.switchRow("Show handle", "Hide it temporarily without stopping the sidebar", !p.handleHidden) { show ->
                    if (OverlayService.running) OverlayService.start(requireContext(), OverlayService.ACTION_TOGGLE_HANDLE) else p.handleHidden = !show },
            ))
        }

        col.addView(ui.section("Edge"))
        col.addView(ui.group(ui.chipRow(listOf("LEFT" to "Left edge", "RIGHT" to "Right edge"), p.edge) { p.edge = it; ping(); build(m, ui) }))
        col.addView(ui.body("You can also drag the handle across the screen to switch edges."))

        col.addView(ui.section("Handle"))
        col.addView(ui.group(
            ui.slider("Opacity", 30, 100, p.handleOpacity, { "$it%" }) { p.handleOpacity = it; ping() },
            ui.slider("Vertical position", 0, 100, (p.handleY * 100).toInt(), { "$it%" }) { p.handleY = it / 100f; ping() },
            ui.slider("Handle height", 56, 120, p.handleSizeDp, { "$it dp" }) { p.handleSizeDp = it; ping() },
            ui.chipRow(listOf("QUICK_CAPTURE" to "Double tap: quick note", "NONE" to "Double tap: same as tap"), p.doubleTap) { p.doubleTap = it; build(m, ui) },
        ))

        col.addView(ui.section("Panel"))
        col.addView(ui.group(
            ui.slider("Width", 260, 460, p.panelWidthDp, { "$it dp" }) { p.panelWidthDp = it },
            ui.slider("Height", 320, 640, p.panelHeightDp, { "$it dp" }) { p.panelHeightDp = it },
            ui.switchRow("Collapse when I tap outside", null, p.autoCollapse) { p.autoCollapse = it },
        ))
        col.addView(ui.body("Tap the handle to open the panel. Double tap for a quick note. Long press the handle to hide it; the notification brings it back."))
    }
}
