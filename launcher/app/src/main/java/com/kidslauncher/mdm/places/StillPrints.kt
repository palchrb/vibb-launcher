package com.kidslauncher.mdm.places

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.Secrets
import java.io.File
import java.io.PrintWriter
import javax.crypto.AEADBadTagException

private const val LOG_TAG = "StillPrint"

/** P1: the print and the ring, each one file sealed whole under its own AAD (the file's name). */
private const val FILE_PRINT = "still_print_v1"
private const val FILE_RING = "still_print_ring_v1"

/**
 * Design 134's fingerprint on the phone (rules in `StillPlan.kt`): the print in force and the ring of events, each an
 * `AtomicFile` in credential-encrypted `noBackupFilesDir` sealed whole with `Secrets.box` (P1), cached in memory for the
 * process and written only on a change. The device and network keys inside are the places table's HMACs. Never sent;
 * logs and the dump ([dump]) carry counts, levels, `J`, rounded distances and times only (P2). Cleared with the table
 * ([clear], P4). A transient Keystore error skips that read or write and keeps the files.
 */
internal object StillPrints {
    private val lock = Any()

    /** Guarded by [lock]: the print in force (read once a process), the ring (`null` = not read, or unreadable now). */
    private var printRead = false
    private var print: StillPrint? = null
    private var ring: PrintRing? = null

    private fun file(context: Context, name: String) = AtomicFile(File(context.applicationContext.noBackupFilesDir, name))

    /** A sealed file's bytes: `null` when there is none, or when it can't be opened now (a broken one is deleted -
     * `unreadable`; a transient error keeps it and [ok] says so). */
    private fun read(context: Context, name: String, keep: Boolean = false, ok: (Boolean) -> Unit = {}): ByteArray? {
        val f = file(context, name)
        if (!f.baseFile.exists()) return null
        return try {
            Secrets.box.open(name, f.readFully())
        } catch (e: Exception) {
            if (!keep && (e is AEADBadTagException || e is IllegalArgumentException || Secrets.box.keyBroken())) {
                f.delete()
                Log.i(LOG_TAG, "$name dropped (unreadable)")
            } else {
                ok(false)
                Log.w(LOG_TAG, "$name not opened now: ${e.javaClass.simpleName}")
            }
            null
        }
    }

    private fun write(context: Context, name: String, bytes: ByteArray?): Boolean {
        val f = file(context, name)
        if (bytes == null) {
            f.delete()
            return true
        }
        val sealed = try {
            Secrets.box.seal(name, bytes)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$name not sealed now: ${e.javaClass.simpleName}")
            return false
        }
        val out = try {
            f.startWrite()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$name not written: ${e.javaClass.simpleName}")
            return false
        }
        return try {
            out.write(sealed)
            f.finishWrite(out)
            true
        } catch (e: Exception) {
            f.failWrite(out)
            Log.w(LOG_TAG, "$name not written: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Under [lock]: the ring, read once (a file of another version is dropped: an empty ring). */
    private fun ringLocked(context: Context): PrintRing? {
        ring?.let { return it }
        var ok = true
        val bytes = read(context, FILE_RING) { ok = it }
        if (!ok) return null
        return (bytes?.let { decodeRing(it) ?: PrintRing().also { Log.i(LOG_TAG, "ring version dropped") } } ?: PrintRing()).also { ring = it }
    }

    /** Under [lock]: the ring after [change], written when it changed (only when it could be read). */
    private fun changeRing(context: Context, change: (PrintRing) -> PrintRing) {
        val before = ringLocked(context) ?: return
        val after = change(before)
        if (after == before) return
        if (write(context, FILE_RING, encodeRing(after))) ring = after
    }

    /** Under [lock]: the print as stored - read once a process. */
    private fun printLocked(context: Context): StillPrint? {
        if (printRead) return print
        var ok = true
        val bytes = read(context, FILE_PRINT) { ok = it }
        if (!ok) return null
        print = bytes?.let { b -> decodePrint(b).also { if (it == null) Log.i(LOG_TAG, "print version dropped") } }
        printRead = true
        return print
    }

    /** Under [lock]: the print goes, counted by [reason] and logged - its file deleted whether or not it can be opened now
     * (code QA L3: a print that can't be read must not outlive its drop). */
    private fun dropLocked(context: Context, reason: DropReason) {
        val had = (printRead && print != null) || file(context, FILE_PRINT).baseFile.exists()
        if (!had) return
        write(context, FILE_PRINT, null)
        print = null
        printRead = true
        changeRing(context) { ringDropped(it, reason) }
        Log.i(LOG_TAG, "print dropped (${reason.wire})")
    }

    /** D14: the print in force - dropped first at a boot or after 72 h ([printStale]). */
    fun current(context: Context): StillPrint? = synchronized(lock) {
        val p = printLocked(context) ?: return null
        val stale = printStale(p, BootClock.bootCount(), SystemClock.elapsedRealtime())
        if (stale != null) {
            dropLocked(context, stale)
            return null
        }
        p
    }

    /** D14: the print goes (GLA on, the still rule off, distrust, its vouch replaced, something not fresh reported). */
    fun drop(context: Context, reason: DropReason): Unit = synchronized(lock) { dropLocked(context, reason) }

    /** D8: [p] is the print of the new vouch. */
    fun take(context: Context, p: StillPrint): Unit = synchronized(lock) {
        if (!write(context, FILE_PRINT, encodePrint(p))) return
        print = p
        printRead = true
        changeRing(context, ::ringTaken)
        Log.i(LOG_TAG, "taken devices=${p.devices.size} cells=${p.cellCount}")
    }

    /** D8 (QA M5): the print grown at a renewal where the rule held. */
    fun grow(context: Context, before: StillPrint, after: StillPrint): Unit = synchronized(lock) {
        if (after == before || print != before) return
        if (write(context, FILE_PRINT, encodePrint(after))) {
            print = after
            Log.i(LOG_TAG, "grown devices=${after.devices.size}")
        }
    }

    /** D10, D19: [e] recorded in the ring and logged. */
    fun record(context: Context, e: PrintEvent): Unit = synchronized(lock) {
        changeRing(context) { ringWith(it, e, e.atMin) }
        Log.i(LOG_TAG, eventText(e))
    }

    /** D11: [fix] judges every pending event (against [vouch], the print in force before a new one is taken). */
    fun afterFix(context: Context, fix: OutcomeFix, vouch: VouchPoint?): Unit = synchronized(lock) {
        val printMin = printLocked(context)?.fixMin
        val before = ringLocked(context) ?: return
        if (before.events.none { it.outcome == OutcomeClass.PENDING }) return
        val after = ringAfterFix(before, fix, vouch, printMin)
        changeRing(context) { after }
        after.events.zip(ringAged(before, fix.atMin).events).filter { (a, b) -> a != b && b.outcome == OutcomeClass.PENDING }.forEach { (a, _) ->
            Log.i(LOG_TAG, "outcome ${outcomeText(a)}")
        }
    }

    /** P4: with the Wi-Fi places' table - location Off, unenrolled, a new enrollment, a broken or unreadable key: both
     * files and the memory. No Keystore call. */
    fun clear(context: Context, reason: String): Unit = synchronized(lock) {
        val had = print != null || file(context, FILE_PRINT).baseFile.exists()
        file(context, FILE_PRINT).delete()
        file(context, FILE_RING).delete()
        print = null
        printRead = true
        ring = PrintRing()
        if (had) Log.i(LOG_TAG, "print dropped ($reason)")
    }

    /**
     * D13: `adb shell dumpsys activity service me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService
     * still-print` - the summary and the ring ([dumpLines]); without the argument one line and nothing of the ring, so a
     * bug report carries none of it. The framework lets only a caller holding `DUMP` reach a service's dump.
     */
    fun dump(context: Context, writer: PrintWriter, args: Array<out String>?) {
        if (args?.contains("still-print") != true) {
            writer.println("Vibb: the place fingerprint's record needs the argument still-print")
            return
        }
        // Code QA L5: read only - nothing dropped, deleted or written, so a dump never changes what it shows (a stale print
        // is shown as stale). At most two Keystore opens on the dump's thread (the main one): adb-only, never a user's.
        val lines = synchronized(lock) {
            val p = if (printRead) print else read(context, FILE_PRINT, keep = true)?.let { decodePrint(it) }
            val r = ring ?: read(context, FILE_RING, keep = true)?.let { decodeRing(it) } ?: if (file(context, FILE_RING).baseFile.exists()) null else PrintRing()
            val stale = p?.let { printStale(it, BootClock.bootCount(), SystemClock.elapsedRealtime()) }
            if (r == null) listOf("still-print: the record can't be opened now") else dumpLines(p, r, (System.currentTimeMillis() / 60_000).toInt(), SystemClock.elapsedRealtime(), stale)
        }
        lines.forEach(writer::println)
    }
}
