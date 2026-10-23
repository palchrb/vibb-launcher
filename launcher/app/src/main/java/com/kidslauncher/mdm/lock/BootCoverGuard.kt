package com.kidslauncher.mdm.lock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import java.io.File

private const val LOG_TAG = "BootCover"

/**
 * The boot cover's own record ([CoverRecord]) in device-protected storage (design 16b,
 * qa-16b-code #2): one small file written atomically by the cover's process (crashes, first frame,
 * hand-over) and read fresh by both processes - SharedPreferences cache per process and would
 * overwrite each other's keys. The main process only reads it and deletes it when the server
 * switch is off (which clears a tripped guard). Never throws.
 */
object BootCoverGuard {
    private const val FILE = "boot_cover_state"
    private const val ANDROID_MARKER = "boot_cover_android"

    @Volatile
    private var installed = false

    private fun file(context: Context) = AtomicFile(File(context.createDeviceProtectedStorageContext().filesDir, FILE))

    fun read(context: Context): CoverRecord? = try {
        val atomic = file(context)
        if (!atomic.baseFile.exists()) null else decodeCoverRecord(String(atomic.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        // Exists but unreadable: fail safe, the cover stays off.
        CoverRecord(tripped = true)
    }

    private fun write(context: Context, record: CoverRecord) {
        val atomic = file(context)
        val out = try {
            atomic.startWrite()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't write the boot cover record", e)
            return
        }
        try {
            out.write(encodeCoverRecord(record).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(LOG_TAG, "Couldn't write the boot cover record", e)
        }
    }

    private fun update(context: Context, change: (CoverRecord) -> CoverRecord): CoverRecord {
        val next = change(read(context) ?: CoverRecord(bootCount = bootCount(context)))
        write(context, next)
        return next
    }

    /** The cover's first frame of this start (status report) at [elapsedMs]; returns the boot's
     * first frame (elapsed) - a recreated cover doesn't start the 3 s again, and the main process's
     * Home counts on from it (design 16e, [coverFrameThisBoot]). */
    fun markShown(context: Context, elapsedMs: Long): Long? =
        update(context) { coverShown(it, bootCount(context), System.currentTimeMillis(), elapsedMs) }.shownElapsedMs

    /** The cover handed over (disabled itself) after the unlock. */
    fun markHandedOver(context: Context) {
        update(context) { it.copy(handedOverAtMs = System.currentTimeMillis()) }
    }

    /** The switch is off, or [coverGuardClearDue] (an update, the switch's off -> on edge - F3C-3): a trip and the old
     * times go (main process; the cover isn't running). */
    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't clear the boot cover record", e)
        }
    }

    /** Design 59 F3: the marker `boot_cover_android` (an empty device-protected file, written only by the main
     * process at an apply - [bootCoverAndroidMarker]): the cover waits for our Home, and the locked boot starts
     * it. Unreadable = absent (the 16b rules). */
    private fun androidMarker(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, ANDROID_MARKER)

    fun androidMode(context: Context): Boolean = try {
        androidMarker(context).exists()
    } catch (e: Exception) {
        false
    }

    /** Main process, every apply (background thread). Never throws. */
    fun setAndroidMode(context: Context, on: Boolean) {
        try {
            val marker = androidMarker(context)
            if (on == marker.exists()) return
            val done = if (on) marker.createNewFile() else marker.delete()
            Log.i(LOG_TAG, "Boot cover android mode ${if (on) "on" else "off"}${if (done) "" else " (not written)"}")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't write the boot cover's android mode", e)
        }
    }

    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    /**
     * From `Application.onCreate` in the cover's process (qa-16b-code #2), so a crash anywhere in
     * that process counts - not only after the activity's onCreate: counts it in the record and,
     * once the guard trips, disables the cover before the crash goes on.
     */
    fun install(app: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val next = coverCrashed(
                    read(app), bootCount(app), com.kidslauncher.mdm.BuildConfig.VERSION_CODE.toLong(),
                    com.kidslauncher.mdm.BuildConfig.VERSION_NAME,
                )
                write(app, next)
                if (coverGuardTripped(next)) disable(app, "crash guard (${next.crashes} crashes this boot)")
            } catch (t: Throwable) {
                // Never in the way of the crash itself.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Disables the cover (DONT_KILL_APP). Never throws. */
    fun disable(context: Context, why: String) {
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootCoverActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.i(LOG_TAG, "Boot cover disabled: $why")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't disable the boot cover ($why)", e)
        }
    }

    fun isDisabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(ComponentName(context, BootCoverActivity::class.java)) !=
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) {
        false
    }
}
