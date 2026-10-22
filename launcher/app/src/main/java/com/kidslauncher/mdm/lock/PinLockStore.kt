package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * The PIN lock's persisted state, in credential-encrypted prefs `pin_lock_state` (never device-
 * protected storage: the kid PIN's hash must not be readable before the first unlock, QA 10 #12;
 * excluded from backup and device transfer - `allowBackup="false"` plus the backup rules). Every
 * write is a synchronous `commit()`: a wrong PIN counted just before a kill or reboot must stay
 * counted (QA 10 #3).
 *
 * No unlock time and no "unlocked" flag is ever stored: a new process starts LOCKED - except, with Android's lock as
 * the gate (design 59 §3.1), the boot's first CE start, which only the typed Child PIN can bring.
 */
object PinLockStore {
    private const val PREFS = "pin_lock_state"

    private const val ACTIVE = "active"
    private const val INACTIVE = "inactive"
    private const val PIN_HASH = "pin_hash"
    private const val PIN_SALT = "pin_salt"
    private const val PIN_LENGTH = "pin_length"
    private const val FAILURES = "failures"
    private const val HASH_FP = "hash_fingerprint"
    private const val BO_WALL = "backoff_wall_start"
    private const val BO_ELAPSED = "backoff_elapsed_start"
    private const val BO_BOOT = "backoff_boot"
    private const val BO_DURATION = "backoff_duration"
    private const val GUARD_CRASHES_KEY = "guard_crashes"
    private const val GUARD_TRIPPED = "guard_tripped_at"
    private const val MARK_BOOT = "boot_mark_boot_count"

    // Design 59 (M-1): what the policy asks and what we own are two flags, so an android -> own apply never
    // erases the rollback's edge.
    private const val PIN_REV = "pin_rev"
    private const val KEYGUARD_REQUESTED = "keyguard_requested"
    private const val KEYGUARD_ANDROID = "keyguard_android"
    private const val ANDROID_PIN_REV = "android_pin_rev"
    private const val ANDROID_BOOT = "android_boot_count"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kid PIN as the last apply saw it ([KidLockConfig.usable] may be false: bad hash). */
    fun config(context: Context): KidLockConfig? {
        val p = prefs(context)
        val hash = p.getString(PIN_HASH, null) ?: return null
        return KidLockConfig(hash, p.getString(PIN_SALT, null).orEmpty(), kidPinLength(p.getInt(PIN_LENGTH, 4)), p.getLong(PIN_REV, 0L))
    }

    fun active(context: Context): Boolean = prefs(context).getBoolean(ACTIVE, false)

    fun inactive(context: Context): String? = prefs(context).getString(INACTIVE, null)

    /** Design 59: the policy asks for Android's lock (`kid_lock.keyguard`, committed with the hash by [saveConfig]). */
    fun keyguardRequested(context: Context): Boolean = prefs(context).getBoolean(KEYGUARD_REQUESTED, false)

    /** The hash, its `pin_rev`, `active` and - design 59 - [requested] in one commit (Q59-5: every rev names the hash
     * it came with). [requested] `null` keeps the stored value (the crash guard's save). */
    fun saveConfig(context: Context, config: KidLockConfig?, active: Boolean, inactive: LockInactive?, requested: Boolean? = null) {
        val editor = prefs(context).edit()
            .putBoolean(ACTIVE, active)
            .putString(INACTIVE, inactive?.wire)
        if (requested != null) editor.putBoolean(KEYGUARD_REQUESTED, requested)
        if (config == null) {
            editor.remove(PIN_HASH).remove(PIN_SALT).remove(PIN_LENGTH).remove(PIN_REV)
        } else {
            editor.putString(PIN_HASH, config.hashHex).putString(PIN_SALT, config.saltHex).putInt(PIN_LENGTH, config.length)
                .putLong(PIN_REV, config.pinRev)
        }
        editor.commit()
    }

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

    /**
     * Design 59 (L-4): the boot count the last CE process start saw, [boot] stored in its place - at **every** CE
     * process start in every mode ([PinLockRuntime.decide]), so a migration later in a boot never makes a crash
     * restart its "first". Not [MARK_BOOT] (the boot mark's, written only when it is due).
     */
    fun swapAndroidBoot(context: Context, boot: Int): Int? {
        val p = prefs(context)
        val stored = p.getInt(ANDROID_BOOT, -1).takeIf { it >= 0 }
        bootSwap(stored, boot)?.let { p.edit().putInt(ANDROID_BOOT, it).commit() }
        return stored
    }

    fun backoff(context: Context): BackoffState {
        val p = prefs(context)
        val window = if (p.getLong(BO_DURATION, 0L) > 0L) {
            BackoffWindow(p.getLong(BO_WALL, 0L), p.getLong(BO_ELAPSED, 0L), p.getInt(BO_BOOT, -1), p.getLong(BO_DURATION, 0L))
        } else {
            null
        }
        return BackoffState(p.getInt(FAILURES, 0), window, p.getString(HASH_FP, null))
    }

    fun saveBackoff(context: Context, state: BackoffState): Boolean {
        val w = state.window
        return prefs(context).edit()
            .putInt(FAILURES, state.failures)
            .putString(HASH_FP, state.hashFingerprint)
            .putLong(BO_WALL, w?.wallStartMs ?: 0L)
            .putLong(BO_ELAPSED, w?.elapsedStartMs ?: 0L)
            .putInt(BO_BOOT, w?.bootCount ?: -1)
            .putLong(BO_DURATION, w?.durationMs ?: 0L)
            .commit()
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

    /**
     * Design 16e: the boot count the last process start saw ([bootMarkDue]); [boot] is stored in its
     * place when known and new - with `commit()` like every write here, once per boot, so no restart
     * in the same boot ever finds it missing (qa-16e-code #4).
     */
    fun swapMarkBoot(context: Context, boot: Int): Int? {
        val p = prefs(context)
        val stored = p.getInt(MARK_BOOT, -1).takeIf { it >= 0 }
        if (boot >= 0 && boot != stored) p.edit().putInt(MARK_BOOT, boot).commit()
        return stored
    }

    fun saveGuard(context: Context, guard: CrashGuard): Boolean = prefs(context).edit()
        .putString(GUARD_CRASHES_KEY, guard.crashes.joinToString(","))
        .putLong(GUARD_TRIPPED, guard.trippedAtMs ?: 0L)
        .commit()
}

/** `PolicyResponse.kidLock` as the lock uses it. */
data class KidLockConfig(val hashHex: String, val saltHex: String, val length: Int, val pinRev: Long = 0) {
    val usable: Boolean get() = com.kidslauncher.mdm.server.PinHash.usable(hashHex, saltHex)

    /** Identifies this PIN for the backoff reset without keeping a second copy of the hash. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest("$saltHex:$hashHex".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
}
