// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The spike's log: timestamped lines in logcat (tag SpotifySpike), in memory for the screen and in
 * [FILE_NAME] under the app's external-files dir (adb pull
 * /sdcard/Android/data/me.vibb.spotifyspike/files/spike-log.txt). Secrets (client ID, access
 * token) are never passed in; [redact] scrubs any that a third party (a WebView console line, an
 * HTTP error body) might echo.
 */
object SpikeLog {
    const val FILE_NAME = "spike-log.txt"
    private const val TAG = "SpotifySpike"
    private const val KEEP = 400
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val secrets = CopyOnWriteArraySet<String>()
    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    fun path(): String = file?.absolutePath ?: "(not initialised)"

    /** Registers a value that must never reach the log (the token, the client ID). */
    fun secret(value: String?) {
        if (!value.isNullOrBlank() && value.length >= 8) secrets += value
    }

    fun redact(text: String): String = secrets.fold(text) { acc, s -> acc.replace(s, "<redacted>") }

    fun log(message: String) {
        val clean = redact(message)
        val line = "${LocalDateTime.now().format(format)} $clean"
        Log.i(TAG, clean)
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > KEEP) recent.removeFirst()
        }
        val target = file
        if (target != null) {
            io.execute {
                try {
                    target.appendText(line + "\n")
                } catch (e: Exception) {
                    Log.w(TAG, "log file: ${e.javaClass.simpleName}")
                }
            }
        }
        main.post { listeners.forEach { it() } }
    }

    fun recentText(): String = synchronized(recent) { recent.joinToString("\n") }

    /** The whole log file (at most its last [maxLines] lines), read off the main thread. */
    fun readFile(maxLines: Int, done: (String) -> Unit) {
        val target = file
        io.execute {
            val text = try {
                target?.takeIf { it.isFile }?.readLines()?.takeLast(maxLines)?.joinToString("\n") ?: ""
            } catch (e: Exception) {
                "(log file unreadable: ${e.javaClass.simpleName})"
            }
            main.post { done(text) }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun describe(t: Throwable): String {
        val cause = t.cause?.let { " (cause ${it.javaClass.simpleName}: ${it.message})" } ?: ""
        return "${t.javaClass.simpleName}: ${t.message}$cause"
    }
}
