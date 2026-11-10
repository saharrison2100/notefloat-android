package com.notefloat.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.networking.Api
import com.notefloat.app.networking.SyncEngine
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.storage.AppDatabase
import com.notefloat.app.storage.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Screen 14: Storage and synchronization. Account, sync, local export, deletion. */
class StorageSyncFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity; val p = m.prefs; val ui = Ui(requireContext(), p)
        val secure = SecureStore(requireContext()); val db = AppDatabase.get(requireContext())
        val col = ui.column(); col.addView(ui.title("Storage and sync"))

        col.addView(ui.section("On this device"))
        val usage = ui.body("Calculating..."); col.addView(usage)
        viewLifecycleOwner.lifecycleScope.launch {
            val audio = db.voice().observeBytes().first(); val notes = m.repo.active.first().size
            usage.text = "$notes notes   audio ${"%.1f".format(audio / 1_048_576.0)} MB   (everything stays on the device unless sync is on)"
        }

        col.addView(ui.section("Account and sync"))
        val account = ui.column(); col.addView(account)
        fun paint() {
            account.removeAllViews()
            if (!secure.signedIn) {
                account.addView(ui.body("Sign in to back up and sync your notes across devices. Your notes are private to your account."))
                val email = ui.edit("Email"); val pw = ui.edit("Password (8+ characters)"); pw.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                account.addView(email); account.addView(ui.spacer(8f)); account.addView(pw); account.addView(ui.spacer(8f))
                fun auth(signup: Boolean) {
                    val e = email.text.toString().trim(); val pass = pw.text.toString()
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches() || pass.length < 8) { toast("Enter a valid email and a password of 8 or more characters."); return }
                    viewLifecycleOwner.lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { val api = Api(secure); if (signup) api.signUp(e, pass, e.substringBefore('@')) else api.signIn(e, pass) } }
                        if (r.isSuccess) { p.syncEnabled = true; SyncWorker.schedulePeriodic(requireContext().applicationContext); SyncWorker.syncSoon(requireContext().applicationContext); toast("Signed in. Sync is on."); paint() }
                        else toast(r.exceptionOrNull()?.message ?: "Could not sign in.")
                    }
                }
                account.addView(ui.button("Sign in") { auth(false) }); account.addView(ui.spacer(6f)); account.addView(ui.button("Create account", false) { auth(true) })
            } else {
                account.addView(ui.group(
                    ui.tapRow("Signed in as", secure.email) {},
                    ui.switchRow("Sync", "Back up and sync notes, reminders and voice details", p.syncEnabled) { p.syncEnabled = it; SyncWorker.schedulePeriodic(requireContext().applicationContext); if (it) SyncWorker.syncSoon(requireContext().applicationContext) },
                    ui.switchRow("Wi-Fi only", "Skip sync on mobile data", p.syncWifiOnly) { p.syncWifiOnly = it; SyncWorker.schedulePeriodic(requireContext().applicationContext) },
                ))
                account.addView(ui.spacer(8f))
                val result = ui.body(if (p.lastSyncAt > 0) "Last sync ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(p.lastSyncAt))}" else "Not synced yet.")
                account.addView(ui.button("Sync now") {
                    result.text = "Syncing..."
                    viewLifecycleOwner.lifecycleScope.launch { val r = withContext(Dispatchers.IO) { SyncEngine(requireContext().applicationContext, secure, p).run() }; result.text = r.message }
                })
                account.addView(result)
                account.addView(ui.button("Sign out", false) { viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { Api(secure).signOut() }; p.syncEnabled = false; paint() } })
            }
        }
        paint()

        col.addView(ui.section("Conflict policy"))
        col.addView(ui.body("If the same note is edited on two devices before they sync, NoteFloat never overwrites either. The newer version keeps the note and the other is saved beside it as \"(conflict copy)\"."))

        col.addView(ui.section("Recent sync log"))
        val log = ui.column(); col.addView(log)
        viewLifecycleOwner.lifecycleScope.launch {
            db.syncLogs().observe().collect { l -> log.removeAllViews(); if (l.isEmpty()) log.addView(ui.body("No sync attempts yet."))
                l.take(8).forEach { log.addView(ui.body("${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it.at))}   ${if (it.ok) "ok" else "failed"}   +${it.pushed} sent  ${it.pulled} received  ${it.conflicts} conflicts  ${it.message}")) } }
        }

        col.addView(ui.section("Your data"))
        col.addView(ui.group(
            ui.tapRow("Export all notes (JSON)", null) { export() },
            ui.tapRow("Delete everything on this device", null) { wipeLocal(m) },
            ui.tapRow("Delete my account and cloud data", null) { deleteAccount(secure, p) },
        ))
        col.addView(ui.body("Export creates one file with notes, categories, tags, reminders and clipboard history. Audio files are not included in the JSON."))
        return ui.scroll(col)
    }

    private fun toast(t: String) = android.widget.Toast.makeText(requireContext(), t, android.widget.Toast.LENGTH_LONG).show()

    private fun export() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val out = withContext(Dispatchers.IO) {
                val o = JSONObject().put("app", "NoteFloat").put("version", "1.1.0").put("exportedAt", System.currentTimeMillis())
                o.put("notes", JSONArray(db.notes().recent(100000).map { n -> JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("pinned", n.pinned).put("createdAt", n.createdAt).put("updatedAt", n.updatedAt) }))
                o.put("archived", JSONArray(db.notes().observeArchived().first().map { n -> JSONObject().put("id", n.id).put("title", n.title).put("body", n.body) }))
                o.put("categories", JSONArray(db.categories().all().map { JSONObject().put("id", it.id).put("name", it.name) }))
                o.put("reminders", JSONArray(db.reminders().allActive().map { JSONObject().put("id", it.id).put("title", it.title).put("fireAt", it.fireAt).put("repeat", it.repeat) }))
                o.put("clipboard", JSONArray(db.clipboard().observe().first().map { JSONObject().put("text", it.text).put("createdAt", it.createdAt) }))
                val dir = File(requireContext().cacheDir, "export").apply { mkdirs() }
                File(dir, "notefloat-export-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json").also { it.writeText(o.toString(2)) }
            }
            val uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".files", out)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export notes"))
        }
    }

    private fun wipeLocal(m: MainActivity) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Delete everything on this device?")
            .setMessage("All notes, recordings, reminders and settings on this phone will be removed. Cloud copies are not affected. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.get(requireContext()).clearAllTables()
                    File(requireContext().filesDir, "audio").deleteRecursively()
                    requireContext().getSharedPreferences("nf_drafts", 0).edit().clear().apply()
                    com.notefloat.app.reminders.ReminderScheduler.rescheduleAll(requireContext().applicationContext)
                    withContext(Dispatchers.Main) { toast("Everything on this device was deleted.") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteAccount(secure: SecureStore, p: com.notefloat.app.storage.Prefs) {
        if (!secure.signedIn) { toast("Sign in first."); return }
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Delete your account?")
            .setMessage("Your account and every synced note, reminder and recording detail on the server will be permanently deleted. Notes on this phone stay.")
            .setPositiveButton("Delete account") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api(secure).deleteAccount() } }
                    if (r.isSuccess) { p.syncEnabled = false; p.serverCursor = 0; toast("Account and cloud data deleted.") } else toast(r.exceptionOrNull()?.message ?: "Could not delete the account.")
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
