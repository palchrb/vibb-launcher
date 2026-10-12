// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Spotify's package state as this app sees it: installed, hidden, suspended, stopped. */
object PackageProbe {
    const val SPOTIFY = "com.spotify.music"

    fun describe(context: Context): String {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(SPOTIFY, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            return "$SPOTIFY not installed"
        }
        val app = info.applicationInfo ?: return "$SPOTIFY: no application info"
        val flags = app.flags
        val parts = mutableListOf("version ${info.versionName}")
        // A package the device owner hid reads as not installed for this user.
        parts += if (flags and ApplicationInfo.FLAG_INSTALLED != 0) "installed" else "HIDDEN (not installed for this user)"
        if (flags and ApplicationInfo.FLAG_SUSPENDED != 0) parts += "SUSPENDED"
        if (flags and ApplicationInfo.FLAG_STOPPED != 0) parts += "stopped (force-stopped or never started)"
        if (!app.enabled) parts += "DISABLED"
        return "$SPOTIFY: ${parts.joinToString(", ")}"
    }
}
