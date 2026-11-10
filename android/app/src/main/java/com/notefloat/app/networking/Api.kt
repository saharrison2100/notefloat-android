package com.notefloat.app.networking

import com.notefloat.app.BuildConfig
import com.notefloat.app.storage.SecureStore
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTPS client for the Base44 backend functions. No third-party HTTP library, no secrets:
 * the only credential is the per-user session token issued at sign-in, stored encrypted.
 * Cleartext HTTP is blocked by network_security_config.xml.
 */
class Api(private val secure: SecureStore, private val base: String = BuildConfig.API_BASE_URL) {

    class ApiException(val code: Int, message: String) : Exception(message)

    fun post(fn: String, body: JSONObject, auth: Boolean = true): JSONObject {
        val url = URL(base + fn)
        require(url.protocol == "https") { "Refusing non-HTTPS endpoint" }
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
            if (auth) secure.token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            c.outputStream.use { os: OutputStream -> os.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) { secure.token = null; throw ApiException(401, "Session expired. Sign in again.") }
            if (code !in 200..299) throw ApiException(code, runCatching { JSONObject(text).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Server error $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { c.disconnect() }
    }

    fun signUp(email: String, password: String, name: String) = post("nfAuth", JSONObject().put("action", "signup").put("email", email).put("password", password).put("name", name), auth = false).also { save(it, email) }
    fun signIn(email: String, password: String) = post("nfAuth", JSONObject().put("action", "signin").put("email", email).put("password", password), auth = false).also { save(it, email) }
    /** Google sign-in: the backend verifies the token with Google before issuing a session. */
    fun signInWithGoogle(accessToken: String) =
        post("nfAuth", JSONObject().put("action", "google").put("accessToken", accessToken), auth = false).also { save(it, it.optString("email")) }

    fun signOut() { runCatching { post("nfAuth", JSONObject().put("action", "signout")) }; secure.clear() }
    fun deleteAccount() = post("nfAuth", JSONObject().put("action", "delete_account")).also { secure.clear() }
    fun exportAll(): JSONObject = post("nfSync", JSONObject().put("action", "export"))

    private fun save(r: JSONObject, email: String) {
        secure.token = r.getString("token"); secure.email = email
        secure.tokenExpiry = r.optLong("expiresAt", 0)
    }
}
