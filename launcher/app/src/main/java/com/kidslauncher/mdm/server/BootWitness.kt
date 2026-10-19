package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidslauncher.mdm.server.dto.UnseenBootsReport

/** What the witness keeps: the last boot it saw, how many it didn't see, when it last noticed one. */
data class BootWitnessState(val lastBoot: Int? = null, val unseen: Int = 0, val lastAtMs: Long? = null)

/**
 * The boot witness's rule (design 24b §3, H4): `Settings.Global.BOOT_COUNT` goes up by one per
 * boot - also a boot the launcher never saw (safe mode, a start cut short). A jump greater than 1
 * since the last process start adds `jump - 1`; the first start of this build (no `lastBoot`)
 * counts nothing (QA #10); an unknown count (-1) changes nothing. The count never goes down.
 */
fun witnessBoot(state: BootWitnessState, bootCount: Int, nowMs: Long): BootWitnessState {
    if (bootCount < 0) return state
    val last = state.lastBoot ?: return state.copy(lastBoot = bootCount)
    val jump = bootCount.toLong() - last
    return if (jump > 1) {
        val unseen = (state.unseen.toLong() + jump - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        BootWitnessState(lastBoot = bootCount, unseen = unseen, lastAtMs = nowMs)
    } else {
        state.copy(lastBoot = bootCount)
    }
}

/** This process start is the first in its boot (QA 24b Low 6): not a crash, LMK or update restart
 * within a boot the witness had seen. An unknown count or a first record counts as a boot. */
fun isNewBoot(lastBoot: Int?, bootCount: Int): Boolean = bootCount < 0 || lastBoot == null || bootCount != lastBoot

/**
 * Boots the launcher didn't see (design 24b §3): checked at every process start, early in
 * `Application.onCreate` (right after the call policy - it runs in direct boot too), in
 * device-protected prefs `boot_witness` (`commit()`; nothing about the PIN or the user is in them).
 * Reported as `unseen_boots`; the server warns.
 */
object BootWitness {
    private const val PREFS = "boot_witness"
    private const val LAST_BOOT = "last_boot"
    private const val UNSEEN = "unseen_count"
    private const val LAST_AT = "last_at_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(prefs: SharedPreferences) = BootWitnessState(
        lastBoot = if (prefs.contains(LAST_BOOT)) prefs.getInt(LAST_BOOT, 0) else null,
        unseen = prefs.getInt(UNSEEN, 0),
        lastAtMs = if (prefs.contains(LAST_AT)) prefs.getLong(LAST_AT, 0L) else null,
    )

    /** [isNewBoot] for this process, set by [record]: a relock at this start is `boot` only then. */
    @Volatile
    var newBoot: Boolean = true
        private set

    /** [record] found a last boot before it overwrote it (design 38, qa-38-design L2): a new boot's
     * start is then told apart from the first start of this build. */
    @Volatile
    var hadLastBoot: Boolean = false
        private set

    /** Process start. Never throws (the call path runs in this process). */
    fun record(context: Context) {
        try {
            val prefs = prefs(context)
            val before = load(prefs)
            val bootCount = BootClock.bootCount()
            newBoot = isNewBoot(before.lastBoot, bootCount)
            hadLastBoot = before.lastBoot != null
            val after = witnessBoot(before, bootCount, System.currentTimeMillis())
            if (after == before) return
            val editor = prefs.edit().putInt(UNSEEN, after.unseen)
            after.lastBoot?.let { editor.putInt(LAST_BOOT, it) }
            after.lastAtMs?.let { editor.putLong(LAST_AT, it) }
            if (!editor.commit()) Log.w("BootWitness", "Couldn't save the boot witness")
            if (after.unseen > before.unseen) Log.w("BootWitness", "Boots without the launcher: ${after.unseen - before.unseen}")
        } catch (e: Exception) {
            Log.e("BootWitness", "Boot witness failed", e)
        }
    }

    /** `unseen_boots` for the status report; `null` before the first record. */
    fun report(context: Context): UnseenBootsReport? = try {
        val state = load(prefs(context))
        if (state.lastBoot == null) null else UnseenBootsReport(state.unseen, state.lastAtMs)
    } catch (e: Exception) {
        null
    }
}
