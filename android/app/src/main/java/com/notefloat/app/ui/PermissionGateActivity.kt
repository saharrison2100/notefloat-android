package com.notefloat.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Asks for exactly one runtime permission, then closes. Used by the floating panel, which cannot
 * show permission dialogs itself. If the user permanently denied it, we send them to app settings
 * with a clear message rather than trying to work around the denial.
 */
class PermissionGateActivity : AppCompatActivity() {

    companion object {
        const val MIC = "mic"; const val NOTIFY = "notify"
        fun request(c: Context, what: String) {
            c.startActivity(Intent(c, PermissionGateActivity::class.java).putExtra("what", what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val what = intent.getStringExtra("what")
        if (granted) Toast.makeText(this, if (what == MIC) "Microphone allowed. Tap Record again." else "Notifications allowed. Set the reminder again.", Toast.LENGTH_LONG).show()
        else {
            Toast.makeText(this, if (what == MIC) "Voice notes need the microphone. You can allow it in app settings." else "Reminders need notification permission.", Toast.LENGTH_LONG).show()
            val perm = if (what == MIC) Manifest.permission.RECORD_AUDIO else Manifest.permission.POST_NOTIFICATIONS
            if (!shouldShowRequestPermissionRationale(perm)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent.getStringExtra("what")
        val perm = when (what) {
            MIC -> Manifest.permission.RECORD_AUDIO
            NOTIFY -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
            else -> null
        }
        if (perm == null || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) { finish(); return }
        launcher.launch(perm)
    }
}
