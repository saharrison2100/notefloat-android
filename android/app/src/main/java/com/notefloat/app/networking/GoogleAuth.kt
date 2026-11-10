package com.notefloat.app.networking

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Sign-In and Google Drive authorisation through Google Identity Services.
 *
 * No client id is stored in the app or in the source: Play services resolves the OAuth client from
 * this app's package name plus the signing certificate. That means the app must be registered once
 * in a Google Cloud project (Android OAuth client, package com.notefloat.app, the release SHA-1).
 * See documentation/GOOGLE_DRIVE_SETUP.md.
 *
 * Nothing here records audio, reads the clipboard or touches Drive on its own: a token is only
 * requested when the user taps a button, and only the scopes that button needs are asked for.
 */
object GoogleAuth {

    /** The user's email and basic profile, for identifying the account that is signing in. */
    val SIGN_IN_SCOPES = listOf(
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
    )

    /**
     * The narrowest Drive scope that works: [drive.appdata] reaches only the hidden application data
     * folder. The app cannot see, list or modify anything else in the user's Drive.
     */
    val DRIVE_SCOPES = listOf("https://www.googleapis.com/auth/drive.appdata")

    /** Access tokens last an hour. Refresh a few minutes early so a sync never starts on a dead token. */
    private const val TOKEN_LIFETIME_MS = 55 * 60 * 1000L

    class AuthToken(val accessToken: String, val expiresAt: Long, val scopes: List<String>)

    /** Shows Google's account picker and consent screen when needed. Must be called from an Activity. */
    suspend fun authorize(activity: Activity, scopes: List<String>): AuthToken {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map { Scope(it) })
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        val token = result.accessToken
            ?: throw IllegalStateException("Google did not return an access token.")
        return AuthToken(token, System.currentTimeMillis() + TOKEN_LIFETIME_MS, result.grantedScopes ?: scopes)
    }

    /** Renews a token in the background for a scheduled backup. Google may still show a prompt. */
    suspend fun authorize(context: Context, scopes: List<String>): AuthToken {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map { Scope(it) })
            .build()
        val result = Identity.getAuthorizationClient(context.applicationContext).authorize(request).await()
        val token = result.accessToken
            ?: throw IllegalStateException("Google did not return an access token.")
        return AuthToken(token, System.currentTimeMillis() + TOKEN_LIFETIME_MS, result.grantedScopes ?: scopes)
    }

    /**
     * The email address the token belongs to. Google confirms it server side, and the backend checks
     * the same token again before it creates a session, so a spoofed client is not enough to sign in.
     */
    fun accountEmail(accessToken: String): String? = runCatching {
        val c = (java.net.URL("https://www.googleapis.com/oauth2/v2/userinfo").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        org.json.JSONObject(text).optString("email").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Plain-language explanation for the failures a real phone actually produces. */
    fun describe(e: Throwable): String = when (e) {
        is ApiException -> when (e.statusCode) {
            16 -> "Google sign-in was cancelled."
            4 -> "Add a Google account to this phone first (Android Settings, Accounts), then try again."
            7 -> "No connection. Check your internet and try again."
            10 -> "This build is not registered for Google access yet. Follow documentation/GOOGLE_DRIVE_SETUP.md once, then try again."
            17 -> "Google Play services is out of date. Update it in the Play Store, then try again."
            else -> e.status?.statusMessage ?: "Google sign-in failed. Try again."
        }
        is IllegalStateException -> e.message ?: "Google sign-in failed."
        else -> e.message ?: "Google sign-in failed."
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
