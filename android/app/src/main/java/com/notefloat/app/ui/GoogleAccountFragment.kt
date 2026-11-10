package com.notefloat.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notefloat.app.networking.Api
import com.notefloat.app.networking.DriveSync
import com.notefloat.app.networking.GoogleAuth
import com.notefloat.app.networking.SyncWorker
import com.notefloat.app.storage.Prefs
import com.notefloat.app.storage.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google account and Google Drive backup.
 *
 * Every Google action starts with a tap: sign-in asks for the email and profile scopes, connecting
 * Drive asks for drive.appdata only. Nothing is requested in the background while the user is not
 * looking, and the token is kept in EncryptedSharedPreferences.
 */
class GoogleAccountFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var secure: SecureStore

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val m = activity as MainActivity
        prefs = m.prefs
        secure = SecureStore(requireContext())
        val ui = Ui(requireContext(), prefs)

        val col = ui.column()
        col.addView(ui.title("Google and Drive"))

        col.addView(ui.section("Account"))
        val accountBox = ui.column()
        col.addView(accountBox)

        col.addView(ui.section("Google Drive backup"))
        val driveBox = ui.column()
        col.addView(driveBox)

        col.addView(ui.section("What is backed up"))
        col.addView(ui.body("Notes with their formatting, categories, tags, reminders and clipboard history. Voice notes back up as entries with their name and length; the audio files stay on this phone."))
        col.addView(ui.body("The backup is one file, notefloat-notes.json, kept in this app's own hidden folder in your Drive. NoteFloat cannot see or change anything else in your Drive, and no other app can read the file."))

        col.addView(ui.section("If Google says the app is not registered"))
        col.addView(ui.body("Google only allows sign-in for apps that are registered, once, in a Google Cloud project: an Android OAuth client for package com.notefloat.app and the app's signing certificate. The steps are in documentation/GOOGLE_DRIVE_SETUP.md and take about three minutes. Nothing in NoteFloat stores a client id or any Google secret."))

        val root = ui.scroll(col)
        paint(ui, accountBox, driveBox)
        return root
    }

    private fun paint(ui: Ui, accountBox: LinearLayout, driveBox: LinearLayout) {
        accountBox.removeAllViews()
        driveBox.removeAllViews()

        if (!secure.signedIn) {
            accountBox.addView(ui.body("Sign in to keep your notes and reminders attached to a real account, and to sync between phones."))
            accountBox.addView(ui.button("Continue with Google") { googleSignIn(ui, accountBox, driveBox) })
            accountBox.addView(ui.body("Password sign-in is still there, under Storage and sync."))
        } else {
            val method = if (prefs.signInMethod == "GOOGLE") "Google" else "Email and password"
            accountBox.addView(ui.group(
                ui.tapRow("Signed in as", secure.email) {},
                ui.tapRow("Signed in with", method) {},
                ui.tapRow("Sync between phones", if (prefs.syncEnabled) "On" else "Off") {},
            ))
            accountBox.addView(ui.spacer(10f))
            accountBox.addView(ui.button("Manage sync", false) { (activity as MainActivity).open(StorageSyncFragment()) })
        }

        val tokenValid = DriveSync(requireContext(), secure, prefs).driveToken() != null
        if (!tokenValid) {
            driveBox.addView(ui.body("Connect your Google account to keep a copy of everything in your own Drive. You choose the account, and you can disconnect at any time."))
            driveBox.addView(ui.button("Connect Google Drive") { connectDrive(ui, accountBox, driveBox) })
            if (secure.googleEmail != null) driveBox.addView(ui.body("Last connected as ${secure.googleEmail}. Access expired or was removed, so it needs renewing."))
        } else {
            val last = prefs.driveLastSyncAt
            driveBox.addView(ui.group(
                ui.tapRow("Drive account", secure.googleEmail ?: "Google account") {},
                ui.switchRow("Back up to Drive", "Keep a copy in your own Drive and merge changes from other phones", prefs.driveEnabled) { on ->
                    prefs.driveEnabled = on
                    if (on) SyncWorker.syncSoon(requireContext().applicationContext)
                },
                ui.tapRow("Last backup", if (last > 0) SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(last)) else "Not yet") {},
            ))
            driveBox.addView(ui.spacer(10f))
            driveBox.addView(ui.button("Back up now") { runAction(ui, accountBox, driveBox) { DriveSync(requireContext(), secure, prefs).sync() } })
            driveBox.addView(ui.spacer(6f))
            driveBox.addView(ui.button("Restore from Drive", false) { runAction(ui, accountBox, driveBox) { DriveSync(requireContext(), secure, prefs).restore() } })
            driveBox.addView(ui.spacer(6f))
            driveBox.addView(ui.button("Disconnect Google Drive", false) {
                secure.googleDriveToken = null
                secure.googleDriveTokenExpiry = 0
                prefs.driveEnabled = false
                toast("Google Drive disconnected. The copy already in your Drive stays there until you delete it in Google Drive settings.")
                paint(ui, accountBox, driveBox)
            })
        }
    }

    private fun googleSignIn(ui: Ui, accountBox: LinearLayout, driveBox: LinearLayout) {
        viewLifecycleOwner.lifecycleScope.launch {
            val activity = activity ?: return@launch
            try {
                val token = GoogleAuth.authorize(activity, GoogleAuth.SIGN_IN_SCOPES)
                val email = withContext(Dispatchers.IO) { GoogleAuth.accountEmail(token.accessToken) }
                val api = Api(secure)
                withContext(Dispatchers.IO) { api.signInWithGoogle(token.accessToken) }
                secure.googleEmail = email ?: secure.email
                prefs.signInMethod = "GOOGLE"
                prefs.syncEnabled = true
                SyncWorker.schedulePeriodic(requireContext().applicationContext)
                SyncWorker.syncSoon(requireContext().applicationContext)
                toast("Signed in as ${secure.email ?: email}. Sync is on.")
                paint(ui, accountBox, driveBox)
            } catch (e: Exception) {
                toast(GoogleAuth.describe(e))
            }
        }
    }

    private fun connectDrive(ui: Ui, accountBox: LinearLayout, driveBox: LinearLayout) {
        viewLifecycleOwner.lifecycleScope.launch {
            val activity = activity ?: return@launch
            try {
                val token = GoogleAuth.authorize(activity, GoogleAuth.DRIVE_SCOPES)
                secure.googleDriveToken = token.accessToken
                secure.googleDriveTokenExpiry = token.expiresAt
                if (secure.googleEmail == null) secure.googleEmail = withContext(Dispatchers.IO) { GoogleAuth.accountEmail(token.accessToken) }
                prefs.driveEnabled = true
                val r = withContext(Dispatchers.IO) { DriveSync(requireContext().applicationContext, secure, prefs).sync() }
                toast(r.message)
                paint(ui, accountBox, driveBox)
            } catch (e: Exception) {
                toast(GoogleAuth.describe(e))
            }
        }
    }

    private fun runAction(ui: Ui, accountBox: LinearLayout, driveBox: LinearLayout, action: suspend () -> DriveSync.Result) {
        viewLifecycleOwner.lifecycleScope.launch {
            toast("Working...")
            val r = withContext(Dispatchers.IO) { runCatching { action() } }
            toast(r.getOrNull()?.message ?: "Could not reach Google Drive. Check your connection.")
            paint(ui, accountBox, driveBox)
        }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(requireContext(), t, android.widget.Toast.LENGTH_LONG).show()
}
