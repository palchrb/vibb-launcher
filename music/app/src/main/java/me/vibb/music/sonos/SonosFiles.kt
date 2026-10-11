package me.vibb.music.sonos

import android.content.Context
import java.io.File

/*
 * Where the Sonos state lives (design 26a §2.1, §4.1): noBackupFilesDir, like the own files' copies -
 * a uid -> ip cache and the renderer choice belong to this phone on this LAN and are never backed up.
 * The only Context in the package besides SsdpScanner.
 */
object SonosFiles {
    /** The uid -> ip cache (`sonos.json`, vibb's STATE_DIR/sonos.json). */
    fun cache(context: Context): File = File(context.applicationContext.noBackupFilesDir, "sonos.json")

    /** Who makes sound (`renderer.json`). */
    fun renderer(context: Context): File = File(context.applicationContext.noBackupFilesDir, "renderer.json")
}
