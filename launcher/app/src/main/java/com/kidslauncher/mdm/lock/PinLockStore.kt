package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The lock's persisted state, in credential-encrypted prefs `pin_lock_state` (excluded from backup and device transfer -
 * `allowBackup="false"` plus the backup rules). Every write is a synchronous `commit()`.
 *
 * Design 132: no PIN material on the phone - Android holds the Child PIN; the keypad's sealed hash and salt, its
 * wrong-PIN count and back-off, `keyguard_requested` and the boot mark's count are removed once ([dropOldKeys]).
 *
 * No unlock time and no "unlocked" flag is ever stored: a new process starts LOCKED - except the boot's first CE start
 * (design 59 §3.1), which only the typed Child PIN can bring.
 */
object PinLockStore {
    private const val PREFS = "pin_lock_state"

    private const val ACTIVE = "active"
    private const val INACTIVE = "inactive"
    private const val GUARD_CRASHES_KEY = "guard_crashes"
    private const val GUARD_TRIPPED = "guard_tripped_at"

    /** Design 132 (QA L5): the keys only our own keypad lock wrote - removed once, never read. */
    internal val OLD_KEYS = listOf(
        "pin_hash", "pin_salt", "pin_length", "pin_rev", "failures", "hash_fingerprint", "backoff_wall_start",
        "backoff_elapsed_start", "backoff_boot", "backoff_duration", "keyguard_requested", "boot_mark_boot_count",
    )

    // Design 59 (M-1): we own Android's credential.
    private const val KEYGUARD_ANDROID = "keyguard_android"
    private const val ANDROID_PIN_REV = "android_pin_rev"
    private const val ANDROID_BOOT = "android_boot_count"
    private const val OWN_CHANGE_COUNT = "own_change_count"
    private const val OWN_CHANGE_BOOT = "own_change_boot"
    private const val OWN_CHANGE_UNTIL = "own_change_until_elapsed"
    private const val OWN_CHANGE_REV = "own_change_rev"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Design 132 (QA L5): the one-shot at the process start, before the first apply - the keys of our own keypad lock
     * (the sealed hash and salt, the back-off, `keyguard_requested`, the boot mark) are removed with `commit()` and logged
     * once. No migration: nothing is read from them.
     */
    fun dropOldKeys(context: Context) {
        val dropped = dropOldKeysFrom(prefs(context)) ?: return
        Log.i("PinLock", "The own lock's stored keys removed (${dropped.first}): ${dropped.second}")
    }

    /** [OLD_KEYS] found in [p] removed in one commit: how many, and whether it committed; `null` = none was there. */
    internal fun dropOldKeysFrom(p: SharedPreferences): Pair<Int, Boolean>? {
        val found = OLD_KEYS.filter { p.contains(it) }
        if (found.isEmpty()) return null
        val editor = p.edit()
        for (key in found) editor.remove(key)
        return found.size to editor.commit()
    }

    fun active(context: Context): Boolean = prefs(context).getBoolean(ACTIVE, false)

    fun inactive(context: Context): String? = prefs(context).getString(INACTIVE, null)

    /** `active`/`inactive` in one commit. */
    fun saveConfig(context: Context, active: Boolean, inactive: LockInactive?): Boolean =
        prefs(context).edit().putBoolean(ACTIVE, active).putString(INACTIVE, inactive?.wire).commit()

    /** Design 59 (M-1): we own Android's credential - set before a reset, cleared only once no credential is left. */
    fun keyguardAndroid(context: Context): Boolean = prefs(context).getBoolean(KEYGUARD_ANDROID, false)

    /** The rev Android's PIN was set from (`null` = unknown or none). */
    fun androidPinRev(context: Context): Long? =
        prefs(context).let { if (it.contains(ANDROID_PIN_REV)) it.getLong(ANDROID_PIN_REV, 0L) else null }

    /** One commit of the owned flag and the rev (`null` removes it). */
    fun saveAndroid(context: Context, owned: Boolean, rev: Long?): Boolean {
        val editor = prefs(context).edit().putBoolean(KEYGUARD_ANDROID, owned)
        if (rev == null) editor.remove(ANDROID_PIN_REV) else editor.putLong(ANDROID_PIN_REV, rev)
        return editor.commit()
    }

    /** Design 59 §3.7 (S4-1): our own credential changes still expected ([OwnChange]); `null` = none, or unreadable. */
    fun ownChange(context: Context): OwnChange? {
        val p = prefs(context)
        val count = p.getInt(OWN_CHANGE_COUNT, 0).takeIf { it > 0 } ?: return null
        val rev = if (p.contains(OWN_CHANGE_REV)) p.getLong(OWN_CHANGE_REV, 0L) else null
        return OwnChange(count, p.getInt(OWN_CHANGE_BOOT, -1), p.getLong(OWN_CHANGE_UNTIL, 0L), rev)
    }

    /** One commit of [change]; `null` removes it. Under `AndroidLock`'s monitor. */
    fun saveOwnChange(context: Context, change: OwnChange?): Boolean {
        val editor = prefs(context).edit()
        if (change == null) {
            editor.remove(OWN_CHANGE_COUNT).remove(OWN_CHANGE_BOOT).remove(OWN_CHANGE_UNTIL).remove(OWN_CHANGE_REV)
        } else {
            editor.putInt(OWN_CHANGE_COUNT, change.count).putInt(OWN_CHANGE_BOOT, change.boot).putLong(OWN_CHANGE_UNTIL, change.untilElapsed)
            if (change.settingRev == null) editor.remove(OWN_CHANGE_REV) else editor.putLong(OWN_CHANGE_REV, change.settingRev)
        }
        return editor.commit()
    }

    /**
     * Design 59 (L-4): the boot count the last CE process start saw, [boot] stored in its place - at **every** CE
     * process start ([PinLockRuntime.decide]), so a `set_kid_pin` later in a boot never makes a crash restart its
     * "first".
     */
    fun swapAndroidBoot(context: Context, boot: Int): Int? {
        val p = prefs(context)
        val stored = p.getInt(ANDROID_BOOT, -1).takeIf { it >= 0 }
        bootSwap(stored, boot)?.let { p.edit().putInt(ANDROID_BOOT, it).commit() }
        return stored
    }

    fun guard(context: Context): CrashGuard {
        val p = prefs(context)
        val starts = p.getString(GUARD_CRASHES_KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val tripped = p.getLong(GUARD_TRIPPED, 0L).takeIf { it > 0L }
        return CrashGuard(starts, tripped)
    }

    /** From the uncaught-exception handler while the lock screen exists (synchronous). */
    fun recordCrash(context: Context) {
        saveGuard(context, guardOnCrash(guard(context), System.currentTimeMillis()))
    }

    fun saveGuard(context: Context, guard: CrashGuard): Boolean = prefs(context).edit()
        .putString(GUARD_CRASHES_KEY, guard.crashes.joinToString(","))
        .putLong(GUARD_TRIPPED, guard.trippedAtMs ?: 0L)
        .commit()
}

/** `PolicyResponse.kidLock`'s hash, as `set_kid_pin`'s check uses it ([commandPinVerifies]) - never stored. */
data class KidLockConfig(val hashHex: String, val saltHex: String, val length: Int, val pinRev: Long = 0) {
    /** Never the hash or the salt (code QA N-c). */
    override fun toString() = "KidLockConfig(length=$length, pinRev=$pinRev, usable=$usable)"

    val usable: Boolean get() = com.kidslauncher.mdm.server.PinHash.usable(hashHex, saltHex)
}
