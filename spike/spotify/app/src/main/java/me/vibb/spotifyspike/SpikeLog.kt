// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The spike's log: timestamped lines in logcat (tag SpotifySpike), in memory for the screen and in
 * [FILE_NAME] under the app's external-files dir (adb pull
 * /sdcard/Android/data/me.vibb.spotifyspike/files/spike-log.txt). Secrets (client ID, access
 * token) are never passed in; [redact] scrubs any that a third party (a WebView console line, an
 * HTTP error body) might echo.
 *
 * The file is opened, appended and closed for every line (no handle survives Doze or a storage
 * remount). A line that can't be written stays pending and goes out with the next one; a failure
 * is one logcat warning per failure streak, never the end of the file log. After [SWITCH_AFTER]
 * failed writes in a row, or when the external-files dir is unavailable, the log moves to the
 * internal files dir for the rest of the process (adb exec-out run-as me.vibb.spotifyspike cat
 * files/spike-log.txt), carrying the pending lines with it.
 */
object SpikeLog {
    const val FILE_NAME = "spike-log.txt"
    private const val TAG = "SpotifySpike"
    private const val KEEP = 400
    private const val MAX_PENDING = 20_000
    private const val SWITCH_AFTER = 3
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val secrets = CopyOnWriteArraySet<String>()

    // The io thread's state; the @Volatile ones are read by the screen's status line.
    private var appContext: Context? = null
    private var internalOnly = false
    private var failStreak = 0
    private val pending = ArrayDeque<String>()
    private var dropped = 0

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var linesInFile = 0

    @Volatile
    private var lastWrite: LocalTime? = null

    @Volatile
    private var failing: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val file = target()
        currentFile = file
        io.execute {
            linesInFile = try {
                if (file.isFile) file.useLines { it.count() } else 0
            } catch (_: Exception) {
                0
            }
        }
    }

    fun path(): String = currentFile?.absolutePath ?: "(resolving)"

    /** One line for the screens: is the file log alive? */
    fun fileStatus(): String {
        val base = "log file: ${path()}, $linesInFile lines, last write ${lastWrite?.format(timeFormat) ?: "-"}"
        val error = failing ?: return base
        return "$base - WRITES FAILING ($error), ${synchronized(pending) { pending.size }} lines pending"
    }

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
        io.execute {
            write(line)
            main.post { listeners.forEach { it() } }
        }
    }

    /** The external-files dir while it works, else the internal files dir. io thread only. */
    private fun target(): File {
        val context = appContext ?: throw IllegalStateException("SpikeLog.init not called")
        val external = if (internalOnly) null else context.getExternalFilesDir(null)
        if (external == null && !internalOnly) {
            internalOnly = true
            Log.w(TAG, "external files dir unavailable: the log file moves to the internal files dir")
        }
        return File(external ?: context.filesDir, FILE_NAME)
    }

    private fun write(line: String) {
        synchronized(pending) {
            pending.addLast(line)
            while (pending.size > MAX_PENDING) {
                pending.removeFirst()
                dropped++
            }
        }
        val file = try {
            target()
        } catch (e: Exception) {
            fail(e)
            return
        }
        if (file != currentFile) {
            Log.w(TAG, "log file is now ${file.absolutePath}")
            currentFile = file
            linesInFile = 0
        }
        try {
            val batch = synchronized(pending) { pending.toList() }
            val gap = dropped
            FileOutputStream(file, true).use { out ->
                val text = buildString {
                    if (gap > 0) append("(log file: $gap lines lost while writes failed)\n")
                    batch.forEach { append(it).append('\n') }
                }
                out.write(text.toByteArray())
            }
            synchronized(pending) { repeat(batch.size) { pending.removeFirst() } }
            dropped -= gap
            linesInFile += batch.size
            lastWrite = LocalTime.now()
            if (failing != null) Log.i(TAG, "log file writable again (${batch.size} pending lines written)")
            failing = null
            failStreak = 0
        } catch (e: Exception) {
            fail(e)
            if (failStreak >= SWITCH_AFTER && !internalOnly) {
                internalOnly = true
                Log.w(TAG, "log file: $failStreak failed writes in a row, moving to the internal files dir")
            }
        }
    }

    private fun fail(e: Exception) {
        failStreak++
        val text = "${e.javaClass.name}: ${e.message}"
        // One warning per failure streak (and per new kind of error), never one per line.
        if (failing != text) Log.w(TAG, "log file write failed, retrying with the next line: $text")
        failing = text
    }

    fun recentText(): String = synchronized(recent) { recent.joinToString("\n") }

    /** The whole log file (at most its last [maxLines] lines), read off the main thread. */
    fun readFile(maxLines: Int, done: (String) -> Unit) {
        io.execute {
            val text = try {
                currentFile?.takeIf { it.isFile }?.readLines()?.takeLast(maxLines)?.joinToString("\n") ?: ""
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
