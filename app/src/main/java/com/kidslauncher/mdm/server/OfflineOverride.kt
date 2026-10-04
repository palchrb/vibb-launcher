package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.security.MessageDigest
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val LOG_TAG = "OfflineOverride"

/** Must match PIN_PBKDF2_ROUNDS/PIN_HASH_LEN in kid-phone-server's src/security.rs - the server
 * computes the hash+salt this device caches and verifies against, so both sides need the exact
 * same PBKDF2 parameters or a correct PIN would simply never verify. */
private const val PIN_PBKDF2_ROUNDS = 210_000
private const val PIN_HASH_LEN_BYTES = 32

private const val MAX_FAILED_ATTEMPTS = 5
private const val LOCKOUT_MS = 15 * 60 * 1000L
private const val OVERRIDE_DURATION_MS = 2 * 60 * 60 * 1000L

/**
 * The offline "unlock code" failsafe: verifies a PIN entered directly on the phone against the
 * hash+salt cached from the last successful policy sync ([PolicyResponse.overridePinHash]/
 * [PolicyResponse.overridePinSalt]), entirely without network. On a match, immediately re-runs
 * [AppEnforcer] treating the policy as fully open so restrictions lift right away rather than
 * waiting for the next scheduled sync - [AppEnforcer.apply] already treats `offline_override_active`
 * as "ignore whatever policy I'm handed," so this just passes `null`. See
 * [com.kidslauncher.mdm.ui.LockActivity] for the entry point.
 */
object OfflineOverride {

    fun isConfigured(): Boolean {
        val mdm = LauncherPreferences.mdm()
        return !mdm.overridePinHash().isNullOrEmpty() && !mdm.overridePinSalt().isNullOrEmpty()
    }

    /** True while a locally-verified override is still within its time window - self-clears (and
     * returns false) once expired, so a stale flag can never linger past its own timeout even if
     * the device never manages to sync again. Checked by both [AppEnforcer] (to treat the policy
     * as fully open) and [MdmSyncWorker] (to force the lock decision to [LockReason.NONE]). */
    fun isActive(): Boolean {
        val mdm = LauncherPreferences.mdm()
        if (!mdm.offlineOverrideActive()) return false
        val start = WindowStart(
            mdm.offlineOverrideExpiresAt(),
            mdm.offlineOverrideElapsedStart(),
            mdm.offlineOverrideBoot(),
        )
        // Both the wall clock and elapsed time since boot must agree the window is still open,
        // so setting the clock back can't stretch it - see timedWindowActive.
        if (!BootClock.isActive(start, OVERRIDE_DURATION_MS)) {
            clear()
            return false
        }
        return true
    }

    /** Called once real server contact is restored (a successful policy fetch, not the
     * cached-fallback path) - the override's whole job is done the moment the device can hear
     * from the server again, so real policy should reassert immediately rather than waiting out
     * the rest of the time window. */
    fun clear() {
        val mdm = LauncherPreferences.mdm()
        mdm.offlineOverrideActive(false)
        mdm.offlineOverrideExpiresAt(0)
    }

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < LauncherPreferences.mdm().offlineOverrideLockedUntil()

    /**
     * Returns true on a match, false otherwise - having recorded a failed attempt and triggered a
     * 15-minute local lockout once [MAX_FAILED_ATTEMPTS] is reached, mirroring the server's own
     * admin-login lockout in security.rs, tracked purely locally since this must keep working
     * with zero network. Pure verification only - does NOT lift restrictions; callers that want
     * the full "unlock the device" behavior must also call [activate] on success (see
     * [com.kidslauncher.mdm.ui.LockActivity]). Kept separate so the Settings PIN-gate can reuse
     * the same code+PIN without also triggering a 2-hour restrictions-off window.
     */
    fun verifyPin(pin: String): Boolean {
        val mdm = LauncherPreferences.mdm()
        val hashHex = mdm.overridePinHash()
        val saltHex = mdm.overridePinSalt()
        if (hashHex.isNullOrEmpty() || saltHex.isNullOrEmpty()) return false

        val matches = try {
            val salt = hexToBytes(saltHex)
            val expected = hexToBytes(hashHex)
            val spec: KeySpec =
                PBEKeySpec(pin.toCharArray(), salt, PIN_PBKDF2_ROUNDS, PIN_HASH_LEN_BYTES * 8)
            val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            MessageDigest.isEqual(actual, expected)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to verify offline override PIN", e)
            false
        }

        if (matches) {
            mdm.offlineOverrideFailedAttempts(0)
        } else {
            val attempts = mdm.offlineOverrideFailedAttempts() + 1
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                mdm.offlineOverrideFailedAttempts(0)
                mdm.offlineOverrideLockedUntil(System.currentTimeMillis() + LOCKOUT_MS)
            } else {
                mdm.offlineOverrideFailedAttempts(attempts)
            }
        }
        return matches
    }

    /** Lifts all restrictions for [OVERRIDE_DURATION_MS] - call only after [verifyPin] succeeds. */
    fun activate(context: Context) {
        val mdm = LauncherPreferences.mdm()
        val start = BootClock.windowStart(OVERRIDE_DURATION_MS)
        mdm.offlineOverrideExpiresAt(start.untilWallMs)
        mdm.offlineOverrideElapsedStart(start.elapsedStartMs)
        mdm.offlineOverrideBoot(start.bootCount)
        mdm.offlineOverrideActive(true)
        mdm.offlineOverrideUsedPendingReport(true)
        AppEnforcer.apply(context, null)
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}
