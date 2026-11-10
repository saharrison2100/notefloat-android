package com.notefloat.app.networking

import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The smallest possible Google Drive v3 client: find, download, create and update one file.
 *
 * Scope is drive.appdata, so every call is confined to the hidden application data folder. Nothing
 * else in the user's Drive is reachable, even if a request were wrong.
 */
class DriveClient(private val accessToken: String) {

    class DriveException(val code: Int, message: String) : Exception(message)

    data class DriveFile(val id: String, val modifiedTime: Long)

    private val base = "https://www.googleapis.com/drive/v3/files"
    private val uploadBase = "https://www.googleapis.com/upload/drive/v3/files"

    fun find(name: String): DriveFile? {
        val q = "name = '${name.replace("'", "\\'")}' and trashed = false"
        val url = "$base?spaces=appDataFolder&pageSize=10" +
            "&fields=files(id,name,modifiedTime)" +
            "&q=" + URLEncoder.encode(q, "UTF-8")
        val o = JSONObject(execute(url, "GET"))
        val files = o.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        return DriveFile(f.optString("id"), parseTime(f.optString("modifiedTime")))
    }

    fun download(id: String): String = execute("$base/$id?alt=media", "GET")

    fun create(name: String, json: String): String {
        val boundary = "nf-" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(json).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val c = connection("$uploadBase?uploadType=multipart&fields=id", "POST")
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        val text = send(c, body.toByteArray(Charsets.UTF_8))
        return JSONObject(text).optString("id")
    }

    fun update(id: String, json: String) {
        val c = connection("$uploadBase/$id?uploadType=media&fields=id", "PATCH")
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        send(c, json.toByteArray(Charsets.UTF_8))
    }

    private fun connection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            doInput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

    private fun execute(url: String, method: String): String = send(connection(url, method), null)

    private fun send(c: HttpURLConnection, payload: ByteArray?): String {
        try {
            if (payload != null) {
                c.doOutput = true
                c.outputStream.use { os: OutputStream -> os.write(payload) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val o = JSONObject(text).optJSONObject("error")?.optString("message")
                    o?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: "Drive error $code"
                throw DriveException(code, message)
            }
            return text
        } catch (e: DriveException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun parseTime(iso: String): Long {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            runCatching { return f.parse(iso)!!.time }
        }
        return 0
    }
}
