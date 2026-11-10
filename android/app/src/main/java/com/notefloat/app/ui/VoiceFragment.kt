package com.notefloat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.storage.VoiceRecordingEntity
import com.notefloat.app.voice.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen 5: Voice notes. Microphone permission is requested only when Record is tapped. */
class VoiceFragment : Fragment() {
    private lateinit var recorder: VoiceRecorder
    private val player = VoicePlayer()
    private var tick: Job? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startNow() else android.widget.Toast.makeText(requireContext(), "Voice notes need the microphone. You can allow it later in Android settings.", android.widget.Toast.LENGTH_LONG).show()
    }
    private var rebuild: (() -> Unit)? = null
    private fun startNow() { if (!recorder.start()) android.widget.Toast.makeText(requireContext(), "Could not start recording.", android.widget.Toast.LENGTH_SHORT).show(); rebuild?.invoke() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val ui = Ui(requireContext(), m.prefs)
        recorder = VoiceRecorder(requireContext().applicationContext)
        val col = ui.column(); col.addView(ui.title("Voice notes"))
        col.addView(ui.body("Recording starts only when you tap Record, and stops when you tap Stop."))

        val timer = ui.tv("00:00", 44f, true).apply { gravity = android.view.Gravity.CENTER }
        val state = ui.body("").apply { gravity = android.view.Gravity.CENTER }
        val ctrl = ui.column()
        val card = ui.column().apply { background = ui.card(22f); setPadding(ui.dp(16f), ui.dp(22f), ui.dp(16f), ui.dp(18f)) }
        card.addView(timer); card.addView(state); card.addView(ctrl); col.addView(card)

        fun paint() {
            ctrl.removeAllViews(); timer.text = VoiceRecorder.fmt(recorder.elapsedMs)
            state.text = when (recorder.state) { VoiceRecorder.State.IDLE -> "Ready"; VoiceRecorder.State.RECORDING -> "Recording"; VoiceRecorder.State.PAUSED -> "Paused" }
            when (recorder.state) {
                VoiceRecorder.State.IDLE -> ctrl.addView(ui.button("Record") {
                    if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startNow() else micPermission.launch(Manifest.permission.RECORD_AUDIO) })
                VoiceRecorder.State.RECORDING -> { ctrl.addView(ui.button("Pause", false) { recorder.pause(); paint() }); ctrl.addView(ui.spacer(8f)); ctrl.addView(ui.button("Stop and save") { finish(m) }) }
                VoiceRecorder.State.PAUSED -> { ctrl.addView(ui.button("Resume", false) { recorder.resume(); paint() }); ctrl.addView(ui.spacer(8f)); ctrl.addView(ui.button("Stop and save") { finish(m) }) }
            }
            if (recorder.state != VoiceRecorder.State.IDLE) { ctrl.addView(ui.spacer(8f)); ctrl.addView(ui.button("Discard", false) { recorder.cancel(); paint() }) }
        }
        rebuild = { paint() }
        paint()
        tick = viewLifecycleOwner.lifecycleScope.launch { while (true) { delay(250); if (recorder.state == VoiceRecorder.State.RECORDING) timer.text = VoiceRecorder.fmt(recorder.elapsedMs) } }

        col.addView(ui.section("Recordings"))
        val list = ui.column(); col.addView(list)
        viewLifecycleOwner.lifecycleScope.launch {
            com.notefloat.app.storage.AppDatabase.get(requireContext()).voice().observe().collect { recs ->
                list.removeAllViews()
                if (recs.isEmpty()) list.addView(ui.body("No recordings yet."))
                recs.forEach { list.addView(row(ui, m, it)) }
            }
        }
        return ui.scroll(col)
    }

    private fun finish(m: MainActivity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = recorder.stop()
            android.widget.Toast.makeText(requireContext(), if (r != null) "Saved \"${r.name}\"" else "Recording was too short.", android.widget.Toast.LENGTH_SHORT).show()
            rebuild?.invoke(); SyncWorker.syncSoon(requireContext().applicationContext)
        }
    }

    private fun row(ui: Ui, m: MainActivity, r: VoiceRecordingEntity): View {
        val c = ui.column().apply { background = ui.card(); setPadding(ui.dp(16f), ui.dp(12f), ui.dp(16f), ui.dp(12f))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8f) } }
        c.addView(ui.tv(r.name, 15f, true)); c.addView(ui.tv(VoiceRecorder.fmt(r.durationMs) + "   " + (r.sizeBytes / 1024) + " KB" + if (r.noteId != null) "   attached" else "", 12f, false, ui.sub))
        val actions = android.widget.LinearLayout(requireContext()).apply { setPadding(0, ui.dp(8f), 0, 0) }
        val play = ui.button(if (player.playingId == r.id) "Stop" else "Play", false) {}
        play.setOnClickListener { player.toggle(r) { play.text = "Play" }; play.text = if (player.playingId == r.id) "Stop" else "Play" }
        actions.addView(play, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6f) })
        actions.addView(ui.button("Rename", false) {
            val et = EditText(requireContext()).apply { setText(r.name); setSingleLine() }
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Rename recording").setView(et)
                .setPositiveButton("Save") { _, _ -> m.lifecycleScope.launch { VoiceManager.rename(requireContext().applicationContext, r.id, et.text.toString().ifBlank { r.name }) } }.setNegativeButton("Cancel", null).show()
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6f) })
        actions.addView(ui.button("Delete", false) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setMessage("Delete \"${r.name}\"? The audio file is removed from this device.")
                .setPositiveButton("Delete") { _, _ -> player.stop(); m.lifecycleScope.launch { VoiceManager.delete(requireContext().applicationContext, r.id) } }.setNegativeButton("Cancel", null).show()
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(actions); return c
    }

    override fun onDestroyView() { super.onDestroyView(); tick?.cancel(); player.stop() }
}
