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

/** Screen 1: Welcome and onboarding. Explains every permission before it is ever requested. */
class OnboardingFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        val col = ui.column()
        col.addView(ui.spacer(24f))
        col.addView(ui.tv("Welcome to NoteFloat", 30f, true))
        col.addView(ui.body("Capture notes, voice memos, reminders and clipboard text without leaving the app you are in."))
        col.addView(ui.section("What NoteFloat will ask for"))
        col.addView(ui.group(
            ui.tapRow("Display over other apps", "for the sidebar") {},
            ui.tapRow("Microphone", "only when you tap Record") {},
            ui.tapRow("Notifications", "for reminders") {},
        ))
        col.addView(ui.body("Overlay permission is granted in Android settings and is used only to draw the sidebar handle and panel. Audio is never recorded in the background. The clipboard is read only when you tap Clipboard. Notes stay on this device unless you sign in and turn sync on."))
        col.addView(ui.spacer(8f))
        col.addView(ui.button("Turn on floating sidebar") {
            if (OverlayService.canDraw(requireContext())) { OverlayService.start(requireContext()); m.finishOnboarding() }
            else androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Allow display over other apps")
                .setMessage("Android will open a settings page. Find NoteFloat and switch on \"Allow display over other apps\", then come back.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))) ; m.prefs.onboarded = true }
                .setNegativeButton("Not now", null).show()
        })
        col.addView(ui.spacer(8f))
        col.addView(ui.button("Skip, use the app only", filled = false) { m.finishOnboarding() })
        return ui.scroll(col)
    }
}
