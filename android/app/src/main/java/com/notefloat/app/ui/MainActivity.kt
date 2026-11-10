package com.notefloat.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.notefloat.app.notes.NoteRepository
import com.notefloat.app.storage.Prefs
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Single-activity shell. Bottom bar: Home, Notes, Voice, Remind, More.
 * Everything else (editor, clipboard, categories, pinned, archive, search, settings, floating config,
 * storage and sync) is pushed on the back stack from those.
 */
class MainActivity : AppCompatActivity() {

    lateinit var prefs: Prefs
    lateinit var repo: NoteRepository
    private lateinit var container: FrameLayout
    private lateinit var bar: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); repo = NoteRepository(this)
        container = FrameLayout(this).apply { id = View.generateViewId() }
        bar = BottomNavigationView(this).apply {
            menu.add(0, 1, 0, "Home"); menu.add(0, 2, 1, "Notes"); menu.add(0, 3, 2, "Voice"); menu.add(0, 4, 3, "Remind"); menu.add(0, 5, 4, "More")
            for (n in 0 until menu.size()) menu.getItem(n).setIcon(android.R.drawable.ic_menu_agenda)
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { switchTab(it.itemId); true }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(container, LinearLayout.LayoutParams(-1, 0, 1f)); addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
                else if (bar.selectedItemId != 1) bar.selectedItemId = 1 else finish()
            }
        })
        supportFragmentManager.addOnBackStackChangedListener { bar.visibility = if (supportFragmentManager.backStackEntryCount > 0) View.GONE else View.VISIBLE }

        if (savedInstanceState == null) {
            if (!prefs.onboarded) show(OnboardingFragment(), false) else { bar.selectedItemId = 1 }
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("noteId")?.let { id -> open(EditorFragment.newInstance(id)); i.removeExtra("noteId") }
        if (i.action == Intent.ACTION_SEND && i.type == "text/plain") {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> open(EditorFragment.newInstance(null, t)) }
            i.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.onboarded && repo.hasUnsavedDraft() && supportFragmentManager.backStackEntryCount == 0) offerDraftRecovery()
    }

    private var draftAsked = false
    private fun offerDraftRecovery() {
        if (draftAsked) return
        val key = repo.pendingDrafts().firstOrNull() ?: return
        val id = if (key == "new") null else key
        val text = repo.loadDraft(id) ?: return
        draftAsked = true
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Recover unsaved note?")
            .setMessage(text.take(160) + if (text.length > 160) "..." else "")
            .setPositiveButton("Recover") { _, _ -> open(EditorFragment.newInstance(id, if (id == null) text else null)) }
            .setNegativeButton("Discard") { _, _ -> repo.clearDraft(id) }
            .setNeutralButton("Later", null).show()
    }

    private fun switchTab(id: Int) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        show(when (id) { 1 -> DashboardFragment(); 2 -> NotesFragment.of(NotesFragment.Source.ALL); 3 -> VoiceFragment(); 4 -> RemindersFragment(); else -> MoreFragment() }, false)
    }

    fun show(f: Fragment, addToBack: Boolean) {
        supportFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(container.id, f).also { if (addToBack) it.addToBackStack(null) }.commit()
    }
    fun open(f: Fragment) = show(f, true)
    fun finishOnboarding() { prefs.onboarded = true; bar.selectedItemId = 1; switchTab(1) }
}
