// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The Web API calls of the Web Playback probe (item 10), straight from the phone with the user's
 * token - no server: the PKCE authorization (no client secret) and "play on this device". Tokens
 * and the client ID are registered with [SpikeLog.secret] and never logged.
 */
object WebApi {
    const val SCOPES = "streaming user-read-email user-read-private user-modify-playback-state user-read-playback-state"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    class Pkce(val verifier: String, val state: String, val authorizeUrl: String)

    fun startPkce(clientId: String): Pkce {
        val verifier = randomString(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = randomString(16)
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", Prefs.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build().toString()
        return Pkce(verifier, state, url)
    }

    /** Exchanges the code for an access token; [done] gets the token or null (the error is logged). */
    fun exchange(clientId: String, code: String, pkce: Pkce, done: (String?) -> Unit) {
        io.execute {
            val form = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to Prefs.REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to pkce.verifier,
            ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val token = try {
                val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    SpikeLog.log("PKCE token exchange: HTTP $status ${body.take(300)}")
                    null
                } else {
                    val json = JSONObject(body)
                    json.optString("access_token").takeIf { it.isNotBlank() }?.also {
                        SpikeLog.secret(it)
                        SpikeLog.secret(json.optString("refresh_token"))
                        SpikeLog.log("PKCE token exchange: OK, scopes '${json.optString("scope")}', expires in ${json.optInt("expires_in")} s")
                    }
                }
            } catch (e: Exception) {
                SpikeLog.log("PKCE token exchange failed: ${SpikeLog.describe(e)}")
                null
            }
            main.post { done(token) }
        }
    }

    /** PUT /v1/me/player/play?device_id=… with a context URI: start playback on the WebView's player. */
    fun playOnDevice(token: String, deviceId: String, contextUri: String) {
        SpikeLog.secret(token)
        SpikeLog.log("Web API: PUT /v1/me/player/play device=$deviceId context=$contextUri")
        io.execute {
            try {
                val url = "https://api.spotify.com/v1/me/player/play?device_id=${URLEncoder.encode(deviceId, "UTF-8")}"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("context_uri", contextUri).toString().toByteArray()) }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                SpikeLog.log("Web API play: HTTP $status ${body.take(300)}")
            } catch (e: Exception) {
                SpikeLog.log("Web API play failed: ${SpikeLog.describe(e)}")
            }
        }
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
