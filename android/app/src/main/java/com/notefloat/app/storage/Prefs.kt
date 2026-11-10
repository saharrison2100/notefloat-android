package com.notefloat.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** User-editable settings. Defaults follow the v1.1 "confirmed user configuration". */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("nf_prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean get() = sp.getBoolean("onboarded", false); set(v) = sp.edit().putBoolean("onboarded", v).apply()

    // Feature switches, all on by default
    var textNotes: Boolean get() = sp.getBoolean("f_text", true); set(v) = sp.edit().putBoolean("f_text", v).apply()
    var voiceNotes: Boolean get() = sp.getBoolean("f_voice", true); set(v) = sp.edit().putBoolean("f_voice", v).apply()
    var reminders: Boolean get() = sp.getBoolean("f_rem", true); set(v) = sp.edit().putBoolean("f_rem", v).apply()
    var clipboard: Boolean get() = sp.getBoolean("f_clip", true); set(v) = sp.edit().putBoolean("f_clip", v).apply()

    // Floating handle
    /** "LEFT" or "RIGHT". User switches at any time. */
    var edge: String get() = sp.getString("edge", "RIGHT") ?: "RIGHT"; set(v) = sp.edit().putString("edge", v).apply()
    /** Vertical position as 0..1 of usable screen height. */
    var handleY: Float get() = sp.getFloat("handleY", 0.35f); set(v) = sp.edit().putFloat("handleY", v).apply()
    /** Handle opacity 30..100 (percent). */
    var handleOpacity: Int get() = sp.getInt("handleOpacity", 82); set(v) = sp.edit().putInt("handleOpacity", v.coerceIn(30, 100)).apply()
    /** Handle size in dp: height of the pill. */
    var handleSizeDp: Int get() = sp.getInt("handleSizeDp", 76); set(v) = sp.edit().putInt("handleSizeDp", v.coerceIn(56, 120)).apply()
    /** Double tap action: "QUICK_CAPTURE" or "NONE". */
    var doubleTap: String get() = sp.getString("doubleTap", "QUICK_CAPTURE") ?: "QUICK_CAPTURE"; set(v) = sp.edit().putString("doubleTap", v).apply()
    var panelWidthDp: Int get() = sp.getInt("panelW", 340); set(v) = sp.edit().putInt("panelW", v.coerceIn(260, 460)).apply()
    var panelHeightDp: Int get() = sp.getInt("panelH", 460); set(v) = sp.edit().putInt("panelH", v.coerceIn(320, 640)).apply()
    var autoCollapse: Boolean get() = sp.getBoolean("autoCollapse", true); set(v) = sp.edit().putBoolean("autoCollapse", v).apply()
    var floatingEnabled: Boolean get() = sp.getBoolean("floatingEnabled", false); set(v) = sp.edit().putBoolean("floatingEnabled", v).apply()
    var handleHidden: Boolean get() = sp.getBoolean("handleHidden", false); set(v) = sp.edit().putBoolean("handleHidden", v).apply()

    // Appearance
    /** "SYSTEM", "LIGHT", "DARK" */
    var theme: String get() = sp.getString("theme", "SYSTEM") ?: "SYSTEM"; set(v) = sp.edit().putString("theme", v).apply()
    /** Text scale 0.85..1.5 */
    var textScale: Float get() = sp.getFloat("textScale", 1.0f); set(v) = sp.edit().putFloat("textScale", v.coerceIn(0.85f, 1.5f)).apply()
    var highContrast: Boolean get() = sp.getBoolean("highContrast", false); set(v) = sp.edit().putBoolean("highContrast", v).apply()

    // Sync
    var syncEnabled: Boolean get() = sp.getBoolean("syncEnabled", false); set(v) = sp.edit().putBoolean("syncEnabled", v).apply()
    var syncWifiOnly: Boolean get() = sp.getBoolean("syncWifiOnly", false); set(v) = sp.edit().putBoolean("syncWifiOnly", v).apply()
    var uploadAudio: Boolean get() = sp.getBoolean("uploadAudio", false); set(v) = sp.edit().putBoolean("uploadAudio", v).apply()
    var lastSyncAt: Long get() = sp.getLong("lastSyncAt", 0); set(v) = sp.edit().putLong("lastSyncAt", v).apply()
    var serverCursor: Long get() = sp.getLong("serverCursor", 0); set(v) = sp.edit().putLong("serverCursor", v).apply()

    // Google Drive backup (independent of the account sync above)
    var driveEnabled: Boolean get() = sp.getBoolean("driveEnabled", false); set(v) = sp.edit().putBoolean("driveEnabled", v).apply()
    var driveLastSyncAt: Long get() = sp.getLong("driveLastSyncAt", 0); set(v) = sp.edit().putLong("driveLastSyncAt", v).apply()
    /** "GOOGLE" when the account was created through Google sign-in, "EMAIL" for password accounts. */
    var signInMethod: String get() = sp.getString("signInMethod", "EMAIL") ?: "EMAIL"; set(v) = sp.edit().putString("signInMethod", v).apply()
}

/** Auth tokens live in EncryptedSharedPreferences (AES-256, key in Android Keystore). */
class SecureStore(context: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "nf_secure",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var token: String? get() = sp.getString("token", null); set(v) = sp.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply()
    var email: String? get() = sp.getString("email", null); set(v) = sp.edit().apply { if (v == null) remove("email") else putString("email", v) }.apply()
    var tokenExpiry: Long get() = sp.getLong("expiry", 0); set(v) = sp.edit().putLong("expiry", v).apply()
    /** Google account email and the Drive access token, kept encrypted like the session token. */
    var googleEmail: String? get() = sp.getString("googleEmail", null); set(v) = sp.edit().apply { if (v == null) remove("googleEmail") else putString("googleEmail", v) }.apply()
    var googleDriveToken: String? get() = sp.getString("driveToken", null); set(v) = sp.edit().apply { if (v == null) remove("driveToken") else putString("driveToken", v) }.apply()
    var googleDriveTokenExpiry: Long get() = sp.getLong("driveTokenExpiry", 0); set(v) = sp.edit().putLong("driveTokenExpiry", v).apply()
    fun clear() = sp.edit().clear().apply()
    val signedIn: Boolean get() = !token.isNullOrEmpty() && (tokenExpiry == 0L || tokenExpiry > System.currentTimeMillis())
}
