package com.kidslauncher.mdm.calls

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract.BlockedNumbers
import android.util.Log
import com.kidslauncher.mdm.push.SyncRunner
import java.util.concurrent.Executors

/** Not "BlockedNumbers": Android's provider logs under that tag. */
private const val LOG_TAG = "VibbBlockedNumbers"

/**
 * Android's side of [blockedNumbersPlan]: reads and clears `BlockedNumberContract` after every `AppEnforcer.apply()`
 * and whenever the list changes (a [ContentObserver], once per process), counts what it cleared in its own prefs
 * (`commit()`), and asks for a sync after a clearing so the server's security-log line follows within seconds.
 *
 * Who may read and write the list (`BlockedNumberProvider`, the same on Android 14 to 17): the system and phone
 * processes, the default dialer, the default SMS app and carrier-privileged apps - never a device owner as such. Vibb One
 * holds the dialer role while calls are managed and the SMS role while SMS is; without either the list can't be read
 * and the report says `blocked_readable: false` (the Calls page's role findings say why). Not lifted by the pause or
 * the offline override (the call rules never are): a number blocked during a pause is cleared at once too.
 *
 * Also switches [BlockedNumbersGate], the handler of `MANAGE_BLOCKED_NUMBERS` that AppEnforcer pins as a persistent
 * preferred activity: enabled while managed, so Android's Blocked numbers page doesn't open from the kiosk.
 */
object BlockedNumbersRuntime {
    private const val PREFS = "blocked_numbers"
    private const val KEY_LAST = "last_cleared_ms"
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var observer: ContentObserver? = null

    @Volatile
    private var readable: Boolean = false

    @Volatile
    private var left: Int? = null

    /** From `AppEnforcer.apply()` (a background thread): the observer once, then a sweep, so the report that follows
     * the apply reads a current list. */
    fun afterApply(context: Context) {
        val app = context.applicationContext
        ensureObserver(app)
        sweep(app)
    }

    /** What the status report says. */
    fun report(context: Context): BlockedReport {
        val prefs = prefs(context)
        return BlockedReport(
            readable = readable,
            left = if (readable) left else null,
            cleared = BlockedClass.entries.associateWith { prefs.getInt(it.wire, 0) },
            lastClearedMs = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0L },
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureObserver(context: Context) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                worker.execute { sweep(context) }
            }
        }
        try {
            context.contentResolver.registerContentObserver(BlockedNumbers.CONTENT_URI, true, created)
            observer = created
        } catch (e: Exception) {
            // Tried again at the next apply.
            Log.w(LOG_TAG, "Couldn't watch the blocked numbers: ${e.javaClass.simpleName}")
        }
    }

    /** From [afterApply] or the observer's [worker]; one at a time. */
    @Synchronized
    private fun sweep(context: Context) {
        try {
            CallPolicyStore.ensureLoaded(context)
            val state = CallPolicyStore.state
            setGateEnabled(context, state.managed)
            val rows = read(context)
            if (rows == null) {
                readable = false
                left = null
                return
            }
            readable = true
            val plan = blockedNumbersPlan(rows, state.managed, (state as? CallPolicyState.Managed)?.rules)
            val cleared = mutableMapOf<BlockedClass, Int>()
            for ((id, kind) in plan.delete) {
                val gone = try {
                    context.contentResolver.delete(ContentUris.withAppendedId(BlockedNumbers.CONTENT_URI, id), null, null) > 0
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't clear a blocked number: ${e.javaClass.simpleName}")
                    false
                }
                if (gone) cleared[kind] = (cleared[kind] ?: 0) + 1
            }
            left = rows.size - cleared.values.sum()
            if (cleared.isEmpty()) return
            val prefs = prefs(context)
            prefs.edit().apply {
                for ((kind, n) in cleared) putInt(kind.wire, prefs.getInt(kind.wire, 0) + n)
                putLong(KEY_LAST, System.currentTimeMillis())
            }.commit()
            Log.i(LOG_TAG, "Cleared blocked numbers: ${cleared.entries.joinToString { "${it.key.wire}=${it.value}" }}")
            SyncRunner.request(context, "blocked_numbers")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The blocked-numbers sweep failed: ${e.javaClass.simpleName}")
        }
    }

    /** The list, or `null` when Vibb One may not read it (not the phone or SMS app) or it can't be read now. */
    private fun read(context: Context): List<BlockedRow>? = try {
        context.contentResolver.query(
            BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null, null, null,
        )?.use { cursor ->
            val rows = mutableListOf<BlockedRow>()
            while (cursor.moveToNext()) rows += BlockedRow(cursor.getLong(0), cursor.getString(1))
            rows
        }
    } catch (e: Exception) {
        // SecurityException without the role; IllegalStateException before the first unlock.
        null
    }

    private fun setGateEnabled(context: Context, on: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, BlockedNumbersGate::class.java)
        val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't switch the blocked-numbers gate: ${e.javaClass.simpleName}")
        }
    }
}
