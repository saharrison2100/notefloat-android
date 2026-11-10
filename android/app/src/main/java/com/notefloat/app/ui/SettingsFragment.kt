package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.notefloat.app.app.NoteFloatApp
import com.notefloat.app.floating.OverlayService

/** Screen 12: Settings (features, appearance, accessibility). */
class SettingsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val p = m.prefs; val ui = Ui(requireContext(), p)
        val col = ui.column(); col.addView(ui.title("Settings"))

        fun refreshOverlay() { if (OverlayService.running) OverlayService.start(requireContext(), OverlayService.ACTION_REFRESH) }

        col.addView(ui.section("Features"))
        col.addView(ui.group(
            ui.switchRow("Text notes", "Quick text capture and the editor", p.textNotes) { p.textNotes = it; refreshOverlay() },
            ui.switchRow("Voice notes", "Record from the app and the sidebar", p.voiceNotes) { p.voiceNotes = it; refreshOverlay() },
            ui.switchRow("Reminders", "Alerts attached to notes", p.reminders) { p.reminders = it; refreshOverlay() },
            ui.switchRow("Clipboard capture", "Only when you tap Clipboard", p.clipboard) { p.clipboard = it; refreshOverlay() },
        ))

        col.addView(ui.section("Appearance"))
        val themeHolder = ui.column()
        fun themeRow() {
            themeHolder.removeAllViews()
            themeHolder.addView(ui.chipRow(listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark"), p.theme) { p.theme = it; NoteFloatApp.applyTheme(it); themeRow(); refreshOverlay() })
        }
        themeRow()
        col.addView(ui.group(themeHolder,
            ui.slider("Text size", 85, 150, (p.textScale * 100).toInt(), { "${it}%" }) { p.textScale = it / 100f; refreshOverlay() },
            ui.switchRow("High contrast", "Stronger borders and more opaque panels", p.highContrast) { p.highContrast = it; refreshOverlay(); activity?.recreate() },
        ))
        col.addView(ui.body("Text size changes apply to new screens and the floating panel straight away. Reopen a screen to see it."))

        col.addView(ui.section("Permissions"))
        col.addView(ui.group(
            ui.tapRow("Display over other apps", if (OverlayService.canDraw(requireContext())) "Allowed" else "Not allowed") {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${requireContext().packageName}"))) },
            ui.tapRow("App permissions", "Microphone, notifications") {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${requireContext().packageName}"))) },
            ui.tapRow("Battery optimisation", null) { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ))
        col.addView(ui.body("NoteFloat only uses a permission for what is written next to it. Nothing runs in the background without a visible notification."))
        return ui.scroll(col)
    }
}
