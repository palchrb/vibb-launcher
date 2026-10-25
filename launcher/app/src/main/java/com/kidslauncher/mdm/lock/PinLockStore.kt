package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.server.Sealed
import com.kidslauncher.mdm.server.SealedPrefs
import com.kidslauncher.mdm.server.Secrets
import java.security.MessageDigest

/**
 * The PIN lock's persisted state, in credential-encrypted prefs `pin_lock_state` (never device-
 * protected storage: the kid PIN's hash must not be readable before the first unlock, QA 10 #12;
 * excluded from backup and device transfer - `allowBackup="false"` plus the backup rules). Every
 * write is a synchronous `commit()`: a wrong PIN counted just before a kill or reboot must stay
 * counted (QA 10 #3).
 *
 * Design r10: the kid PIN's hash and salt are on disk only sealed ([SealedPrefs], AAD `pin_lock_state/<key>`), the
 * one copy (the cached policy's is redacted); a plaintext left by an older build is sealed at its first background read.
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
    private const val HASH_AAD = "pin_lock_state/pin_hash"
    private const val SALT_AAD = "pin_lock_state/pin_salt"
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
    private const val OWN_CHANGE_COUNT = "own_change_count"
    private const val OWN_CHANGE_BOOT = "own_change_boot"
    private const val OWN_CHANGE_UNTIL = "own_change_until_elapsed"
    private const val OWN_CHANGE_REV = "own_change_rev"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kid PIN as the last apply saw it ([KidLockConfig.usable] may be false: bad hash); `null` when none is
     * stored or it doesn't open (design r10: unreadable). Main thread: opens, never migrates (QA R10-7). */
    fun config(context: Context): KidLockConfig? = configFrom(prefs(context), Secrets.prefs, Secrets.migrateHere())

    internal fun configFrom(p: SharedPreferences, sealed: SealedPrefs, migrate: Boolean): KidLockConfig? {
        val hash = sealed.read(p, HASH_AAD, PIN_HASH, migrate) as? Sealed.Value ?: return null
        val salt = when (val read = sealed.read(p, SALT_AAD, PIN_SALT, migrate)) {
            is Sealed.Value -> read.plain
            Sealed.Absent -> ""
            Sealed.Unreadable -> return null
        }
        return KidLockConfig(hash.plain, salt, kidPinLength(p.getInt(PIN_LENGTH, 4)), p.getLong(PIN_REV, 0L))
    }

    /** A kid PIN is stored (sealed or not, readable or not) - no Keystore call (Home's pre-check, QA R10-7). */
    fun pinStored(context: Context): Boolean = prefs(context).contains(PIN_HASH)

    fun active(context: Context): Boolean = prefs(context).getBoolean(ACTIVE, false)

    fun inactive(context: Context): String? = prefs(context).getString(INACTIVE, null)

    /** Design 59: the policy asks for Android's lock (`kid_lock.keyguard`, committed with the hash by [saveConfig]). */
    fun keyguardRequested(context: Context): Boolean = prefs(context).getBoolean(KEYGUARD_REQUESTED, false)

    /** [write]'s PIN fields, `active`/`inactive` and - design 59 - [requested] in one commit (Q59-5: every rev names
     * the hash it came with). [requested] `null` keeps the stored value (the crash guard's save). */
    fun saveConfig(context: Context, write: PinWrite, active: Boolean, inactive: LockInactive?, requested: Boolean? = null): Boolean =
        saveConfigTo(prefs(context), Secrets.prefs, write, active, inactive, requested)

    /**
     * Design r10 §3.2: [PinWrite.Write] seals the hash and salt; [PinWrite.Remove] removes the fields; [PinWrite.Keep]
     * writes `active`/`inactive` only. A failed seal (QA R10-3) commits `active`/`inactive` and **removes** the PIN
     * fields - never keeps the old ones (a changed PIN may be the leaked one): the next start is `BAD_HASH` until a sync.
     */
    internal fun saveConfigTo(
        p: SharedPreferences,
        sealed: SealedPrefs,
        write: PinWrite,
        active: Boolean,
        inactive: LockInactive?,
        requested: Boolean?,
    ): Boolean {
        fun base(e: SealedPrefs.Editor) {
            e.raw.putBoolean(ACTIVE, active).putString(INACTIVE, inactive?.wire)
            if (requested != null) e.raw.putBoolean(KEYGUARD_REQUESTED, requested)
        }
        fun removePin(e: SealedPrefs.Editor) {
            e.remove(HASH_AAD, PIN_HASH).remove(SALT_AAD, PIN_SALT)
            e.raw.remove(PIN_LENGTH).remove(PIN_REV)
        }
        val ok = sealed.edit(p) { e ->
            base(e)
            when (write) {
                is PinWrite.Write -> {
                    e.putSealed(HASH_AAD, PIN_HASH, write.config.hashHex).putSealed(SALT_AAD, PIN_SALT, write.config.saltHex)
                    e.raw.putInt(PIN_LENGTH, write.config.length).putLong(PIN_REV, write.config.pinRev)
                }
                PinWrite.Remove -> removePin(e)
                PinWrite.Keep -> Unit
            }
        }
        if (ok || write !is PinWrite.Write) return ok
        return sealed.edit(p) { e ->
            base(e)
            removePin(e)
        }
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
