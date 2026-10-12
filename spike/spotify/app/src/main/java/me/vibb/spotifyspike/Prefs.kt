// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.content.SharedPreferences

/** The spike's settings (SharedPreferences "spike"; backup is off). The client ID is never logged. */
object Prefs {
    /** Fixed; register it in Spotify's dashboard exactly like this. */
    const val REDIRECT_URI = "vibb-spotify-spike://callback"

    private const val CLIENT_ID = "client_id"
    private const val PLAY_URI = "play_uri"
    private const val SKIP_INDEX = "skip_index"
    private const val SEEK_MS = "seek_ms"
    private const val AUTO_RECONNECT = "auto_reconnect"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("spike", Context.MODE_PRIVATE)

    fun clientId(context: Context): String = prefs(context).getString(CLIENT_ID, "").orEmpty().also { SpikeLog.secret(it) }

    fun playUri(context: Context): String = prefs(context).getString(PLAY_URI, "").orEmpty()

    fun skipIndex(context: Context): String = prefs(context).getString(SKIP_INDEX, "0").orEmpty()

    fun seekMs(context: Context): String = prefs(context).getString(SEEK_MS, "0").orEmpty()

    fun autoReconnect(context: Context): Boolean = prefs(context).getBoolean(AUTO_RECONNECT, false)

    fun save(context: Context, clientId: String, playUri: String, skipIndex: String, seekMs: String) {
        SpikeLog.secret(clientId)
        prefs(context).edit()
            .putString(CLIENT_ID, clientId.trim())
            .putString(PLAY_URI, playUri.trim())
            .putString(SKIP_INDEX, skipIndex.trim())
            .putString(SEEK_MS, seekMs.trim())
            .apply()
    }

    fun setAutoReconnect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(AUTO_RECONNECT, on).apply()
    }
}
