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
 * No unlock time and no "unlocked" flag is ever stored: a new process starts LOCKED.
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
    private const val GUARD_STARTS = "guard_starts"
    private const val GUARD_TRIPPED = "guard_tripped_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kid PIN as the last apply saw it ([KidLockConfig.usable] may be false: bad hash). */
    fun config(context: Context): KidLockConfig? {
        val p = prefs(context)
        val hash = p.getString(PIN_HASH, null) ?: return null
        return KidLockConfig(hash, p.getString(PIN_SALT, null).orEmpty(), kidPinLength(p.getInt(PIN_LENGTH, 4)))
    }

    fun active(context: Context): Boolean = prefs(context).getBoolean(ACTIVE, false)

    fun inactive(context: Context): String? = prefs(context).getString(INACTIVE, null)

    fun saveConfig(context: Context, config: KidLockConfig?, active: Boolean, inactive: LockInactive?) {
        val editor = prefs(context).edit()
            .putBoolean(ACTIVE, active)
            .putString(INACTIVE, inactive?.wire)
        if (config == null) {
            editor.remove(PIN_HASH).remove(PIN_SALT).remove(PIN_LENGTH)
        } else {
            editor.putString(PIN_HASH, config.hashHex).putString(PIN_SALT, config.saltHex).putInt(PIN_LENGTH, config.length)
        }
        editor.commit()
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
        val starts = p.getString(GUARD_STARTS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val tripped = p.getLong(GUARD_TRIPPED, 0L).takeIf { it > 0L }
        return CrashGuard(starts, tripped)
    }

    fun saveGuard(context: Context, guard: CrashGuard): Boolean = prefs(context).edit()
        .putString(GUARD_STARTS, guard.pendingStarts.joinToString(","))
        .putLong(GUARD_TRIPPED, guard.trippedAtMs ?: 0L)
        .commit()
}

/** `PolicyResponse.kidLock` as the lock uses it. */
data class KidLockConfig(val hashHex: String, val saltHex: String, val length: Int) {
    val usable: Boolean get() = com.kidslauncher.mdm.server.PinHash.usable(hashHex, saltHex)

    /** Identifies this PIN for the backoff reset without keeping a second copy of the hash. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest("$saltHex:$hashHex".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
}
