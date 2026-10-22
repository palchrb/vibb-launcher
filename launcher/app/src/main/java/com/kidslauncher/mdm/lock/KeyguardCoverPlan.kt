package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.keyguardDisabledFeatures as secureCameraRule

/*
 * The keyguard cover (design 32 step 2, docs/design/32-lock-flash.md §8 step 2) - pure, tested in
 * KeyguardCoverPlanTest. A debug-only device experiment: only `KeyguardCoverDebug`'s switch (src/debug,
 * the release one is always false) makes it COVER; otherwise the keyguard is written exactly as before.
 *
 * The system keyguard as a bridge across the sleep: a bare Swipe keyguard (no credential, every
 * feature off) that our showWhenLocked lock occludes while dark. While it is locked, AOSP shows no
 * activity that isn't showWhenLocked, so nothing of another app can be on screen before the lock's
 * first frame. The lock dismisses it after that frame; the lock delay is 0 by max-time-to-lock (H1).
 */

/** How [KeyguardCover] writes the keyguard ([keyguardMode]). */
enum class KeyguardMode {
    /** The bare Swipe keyguard as a cover across the sleep (design 32 step 2). */
    COVER,

    /** Today's `setKeyguardDisabled(true)`: screen lock "None" for handy's PIN lock (step 10). */
    DISABLED,

    /** Not ours to switch off: not managed, no kid PIN, or an Android credential - `setKeyguardDisabled(false)`
     * and the phone's own keyguard, as before design 32. */
    UNTOUCHED,

    /** Design 59's hybrid (debug switch only, [hybridFace]): the Android PIN is the gate - `setKeyguardDisabled(false)`,
     * [HYBRID_KEYGUARD_FEATURES] added, T = the screen timeout ([hybridWrite]). */
    HYBRID,
}

/**
 * Design 32 step 2 item 2. [coverWanted]: the debug switch is on and the cover isn't held off (a new
 * process before its first lock resume with the screen on, M4; the dismissal fallback, M7);
 * [voip]: the VoIP phase as last seen with the screen on (M5: the cover is off in a VoIP call);
 * [guardTripped]: the crash guard (L8).
 * - UNTOUCHED wherever today's code doesn't disable the keyguard ([wantKeyguardDisabled]);
 * - COVER only with the switch, managed, a kid PIN, no credential, no VoIP call or ring, the guard not tripped;
 * - DISABLED otherwise - today's behaviour.
 * [hybrid]: design 59's debug switch - HYBRID once the phone has the Android PIN ([hybridFace]); never the
 * cover while it is on.
 */
fun keyguardMode(
    managed: Boolean,
    hasKidPin: Boolean,
    deviceSecure: Boolean,
    coverWanted: Boolean,
    voip: VoipPhase,
    guardTripped: Boolean,
    hybrid: Boolean = false,
): KeyguardMode = when {
    hybridFace(hybrid, managed, hasKidPin, deviceSecure) -> KeyguardMode.HYBRID
    !wantKeyguardDisabled(managed, hasKidPin, deviceSecure) -> KeyguardMode.UNTOUCHED
    coverWanted && !hybrid && voip == VoipPhase.NONE && !guardTripped -> KeyguardMode.COVER
    else -> KeyguardMode.DISABLED
}

/** `DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL`. */
const val KEYGUARD_FEATURES_ALL = 0x7fffffff

/**
 * The keyguard-disabled features to write, and the new `coverAdded` (M6) - the bits the cover set that
 * weren't set before it (kept in CE prefs `keyguard_cover`). [cover]: ALL, adding to `coverAdded` what
 * [current] lacks. Off: exactly `coverAdded` taken out, a parent's or another tool's bits kept, plus
 * B2's SECURE_CAMERA while [managed] (minus it unmanaged), `coverAdded` 0. [hybrid] (design 59): what was
 * added before taken out, then [HYBRID_KEYGUARD_FEATURES] and B2's bit in; `coverAdded` = the hybrid's bits that
 * weren't set without it - so the switch back takes out exactly those, a parent's own bits stay.
 */
fun keyguardDisabledFeatures(current: Int, managed: Boolean, cover: Boolean, coverAdded: Int, hybrid: Boolean = false): Pair<Int, Int> {
    if (cover) return KEYGUARD_FEATURES_ALL to (coverAdded or (KEYGUARD_FEATURES_ALL and current.inv()))
    val base = current and coverAdded.inv()
    if (hybrid) return secureCameraRule(base or HYBRID_KEYGUARD_FEATURES, managed) to (HYBRID_KEYGUARD_FEATURES and base.inv())
    return secureCameraRule(base, managed) to 0
}

/**
 * H1: `setMaximumTimeToLock` under the cover - the effective screen timeout, so SystemUI's lock delay
 * after a timeout (`min(T - displayTimeout, lockAfter)`) is 0. 0 (none) without the cover or with no
 * known timeout.
 */
fun maxTimeToLock(coverOn: Boolean, effectiveTimeoutMs: Long?): Long =
    if (coverOn && effectiveTimeoutMs != null && effectiveTimeoutMs > 0L) effectiveTimeoutMs else 0L

/** Which of the screen timeout and the max time to lock is written first ([timeoutWriteOrder]). */
enum class TimeoutWriteOrder { SETTING_FIRST, T_FIRST }

/**
 * H1: T above the setting gives a lock delay > 0; T below it shortens the screen timeout. So a
 * growing timeout writes the setting first, a shrinking (or unknown old) one T first.
 */
fun timeoutWriteOrder(oldMs: Long?, newMs: Long): TimeoutWriteOrder =
    if (oldMs != null && newMs > oldMs) TimeoutWriteOrder.SETTING_FIRST else TimeoutWriteOrder.T_FIRST

/** The DevicePolicyManager calls of the one keyguard writer ([KeyguardCover]); each may throw. */
interface KeyguardPolicy {
    fun readFeatures(): Int
    fun writeFeatures(value: Int)
    fun readMaxTimeToLock(): Long
    fun writeMaxTimeToLock(ms: Long)
    fun writeDisabled(disabled: Boolean): Boolean
}

/** What [writeKeyguard] left in force. */
data class KeyguardWritten(
    /** COVER only when every step of it held; a failed cover is DISABLED with [coverFailed]. HYBRID stays HYBRID,
     * [coverFailed] naming a failed step (the Android PIN is the gate whatever these write). */
    val mode: KeyguardMode,
    /** `lockActivation`'s keyguard input: the cover is set, or `setKeyguardDisabled(true)` worked. */
    val lockMayRun: Boolean,
    /** The cover's step that failed (`features`, `cover_added`, `timeout`, `max_time_to_lock`,
     * `cover_written`, `disabled`) - the fallback ran; reported separately (H2). */
    val coverFailed: String? = null,
    /** Read back or written (`null`: unreadable). */
    val features: Int? = null,
    val maxTimeToLockMs: Long? = null,
    /** `setKeyguardDisabled`'s value in force (`null`: the call threw). */
    val disabled: Boolean? = null,
    /** What threw while the cover was off - logged always, release included (qa-32-s2-code nit 2). */
    val errors: List<Pair<String, Exception>> = emptyList(),
)

/**
 * Design 32 step 2 item 2 (H2, M6): writes [wanted].
 * - COVER, in this order: the features (`coverAdded` stored first), read back = ALL; T = [timeoutMs],
 *   read back; only then `setKeyguardDisabled(false)`. Any failure: DISABLED - the PIN lock is never
 *   switched off because of the cover.
 * - DISABLED: `setKeyguardDisabled(true)` first, then the features with the cover's bits out, T 0.
 * - UNTOUCHED: `setKeyguardDisabled(false)` (as before design 32), the features with the cover's bits
 *   out, T 0.
 * Writes only what differs. [storeCoverAdded] commits the new `coverAdded` (false = not stored).
 * [storeWritten] commits whether the device policy may hold the cover (qa-32-s2-code M2): true before
 * `setKeyguardDisabled(false)` (not stored = no cover), false after a `true` that worked, or after
 * UNTOUCHED took the cover's bits and T out. A new process reads it ([processStartWrite]).
 * - HYBRID (design 59): [hybridWrite].
 */
fun writeKeyguard(
    policy: KeyguardPolicy,
    wanted: KeyguardMode,
    managed: Boolean,
    coverAdded: Int,
    timeoutMs: () -> Long?,
    storeCoverAdded: (Int) -> Boolean,
    storeWritten: (Boolean) -> Boolean = { true },
): KeyguardWritten {
    var added = coverAdded
    fun store(value: Int): Boolean {
        if (value == added) return true
        if (!storeCoverAdded(value)) return false
        added = value
        return true
    }
    if (wanted == KeyguardMode.HYBRID) return hybridWrite(policy, managed, added, ::store, timeoutMs)
    if (wanted == KeyguardMode.COVER) {
        var step = "features"
        try {
            val current = policy.readFeatures()
            val (value, newAdded) = keyguardDisabledFeatures(current, managed, cover = true, coverAdded = added)
            // M6: what the cover adds is kept before it is written.
            if (!store(newAdded)) {
                step = "cover_added"
                throw IllegalStateException("coverAdded not stored")
            }
            if (value != current) policy.writeFeatures(value)
            if (policy.readFeatures() != KEYGUARD_FEATURES_ALL) throw IllegalStateException("features read back wrong")
            step = "timeout"
            val t = maxTimeToLock(coverOn = true, effectiveTimeoutMs = timeoutMs())
            if (t <= 0L) throw IllegalStateException("no screen timeout")
            step = "max_time_to_lock"
            if (policy.readMaxTimeToLock() != t) policy.writeMaxTimeToLock(t)
            if (policy.readMaxTimeToLock() != t) throw IllegalStateException("max time to lock read back wrong")
            step = "cover_written"
            if (!storeWritten(true)) throw IllegalStateException("cover_written not stored")
            step = "disabled"
            if (!policy.writeDisabled(false)) throw IllegalStateException("setKeyguardDisabled(false) refused")
            return KeyguardWritten(KeyguardMode.COVER, lockMayRun = true, features = KEYGUARD_FEATURES_ALL, maxTimeToLockMs = t, disabled = false)
        } catch (e: Exception) {
            return off(policy, KeyguardMode.DISABLED, managed, added, ::store, storeWritten, coverFailed = step)
        }
    }
    return off(policy, wanted, managed, added, ::store, storeWritten, coverFailed = null)
}

/**
 * Design 59's HYBRID ([writeKeyguard]): `setKeyguardDisabled(false)` (with a credential the keyguard is on
 * anyway), the features with [HYBRID_KEYGUARD_FEATURES] - the new `coverAdded` stored first, so the switch
 * back takes out exactly them - read back, then T = the screen timeout (lock delay 0, 59 §4.3), read back.
 * Never `cover_written` (that is the cover's bare keyguard). A failed step is reported in `coverFailed` and
 * logged, never a fallback: the Android PIN is the gate whatever these write. `lockMayRun`: the glance is
 * the face.
 */
private fun hybridWrite(policy: KeyguardPolicy, managed: Boolean, coverAdded: Int, store: (Int) -> Boolean, timeoutMs: () -> Long?): KeyguardWritten {
    val errors = mutableListOf<Pair<String, Exception>>()
    var failed: String? = null
    val disabled = try {
        if (!policy.writeDisabled(false)) throw IllegalStateException("setKeyguardDisabled(false) refused")
        false
    } catch (e: Exception) {
        errors += "Hybrid: setKeyguardDisabled(false) failed" to e
        failed = "disabled"
        null
    }
    val features = try {
        val current = policy.readFeatures()
        val (value, newAdded) = keyguardDisabledFeatures(current, managed, cover = false, coverAdded = coverAdded, hybrid = true)
        // M6's order: what is added is kept before it is written.
        if (!store(newAdded)) throw IllegalStateException("coverAdded not stored")
        if (value != current) policy.writeFeatures(value)
        val read = policy.readFeatures()
        if ((read and HYBRID_KEYGUARD_FEATURES) != HYBRID_KEYGUARD_FEATURES) throw IllegalStateException("features read back wrong")
        read
    } catch (e: Exception) {
        errors += "Hybrid: the keyguard features failed" to e
        failed = failed ?: "features"
        null
    }
    val t = try {
        val want = maxTimeToLock(coverOn = true, effectiveTimeoutMs = timeoutMs())
        if (want <= 0L) throw IllegalStateException("no screen timeout")
        if (policy.readMaxTimeToLock() != want) policy.writeMaxTimeToLock(want)
        val read = policy.readMaxTimeToLock()
        if (read != want) throw IllegalStateException("max time to lock read back wrong")
        read
    } catch (e: Exception) {
        errors += "Hybrid: the max time to lock failed" to e
        failed = failed ?: "max_time_to_lock"
        null
    }
    return KeyguardWritten(KeyguardMode.HYBRID, lockMayRun = true, coverFailed = failed, features = features, maxTimeToLockMs = t, disabled = disabled, errors = errors)
}

/** DISABLED or UNTOUCHED ([writeKeyguard]): the keyguard first, then the cover's bits and T out. */
private fun off(
    policy: KeyguardPolicy,
    mode: KeyguardMode,
    managed: Boolean,
    coverAdded: Int,
    store: (Int) -> Boolean,
    storeWritten: (Boolean) -> Boolean,
    coverFailed: String?,
): KeyguardWritten {
    val disable = mode == KeyguardMode.DISABLED
    val errors = mutableListOf<Pair<String, Exception>>()
    // Today's `setKeyguardDisabled(want) && want`: true also dismisses a showing keyguard.
    val result = try {
        policy.writeDisabled(disable)
    } catch (e: Exception) {
        errors += "setKeyguardDisabled($disable) failed" to e
        null
    }
    val features = try {
        val current = policy.readFeatures()
        val (value, newAdded) = keyguardDisabledFeatures(current, managed, cover = false, coverAdded = coverAdded)
        if (value != current) policy.writeFeatures(value)
        store(newAdded)
        value
    } catch (e: Exception) {
        // Without the disable-keyguard-features policy granted (AdminPolicies): retried at every apply.
        errors += "Failed to set the keyguard camera feature" to e
        null
    }
    val t = try {
        if (policy.readMaxTimeToLock() != 0L) policy.writeMaxTimeToLock(0L)
        0L
    } catch (e: Exception) {
        errors += "Failed to clear the maximum time to lock" to e
        null
    }
    // M2: the cover is out of the device policy - the keyguard disabled, or (UNTOUCHED) the phone's own
    // with the cover's bits and T gone.
    if (if (disable) result == true else features != null && t == 0L) storeWritten(false)
    return KeyguardWritten(
        mode = mode,
        lockMayRun = disable && result == true,
        coverFailed = coverFailed,
        features = features,
        maxTimeToLockMs = t,
        disabled = if (result == null) null else disable,
        errors = errors,
    )
}

/**
 * M7: the resumed lock asks for the keyguard's dismissal - the cover on, the screen on, the keyguard
 * locked, the first frame since this resume drawn, and not asked yet in this resume (later asks come
 * from the state check, [dismissFailed]).
 */
fun dismissDue(
    coverOn: Boolean,
    resumed: Boolean,
    interactive: Boolean,
    keyguardLocked: Boolean,
    drawnSinceResume: Boolean,
    triedThisResume: Boolean,
): Boolean = coverOn && resumed && interactive && keyguardLocked && drawnSinceResume && !triedThisResume

/** How long after a dismissal request the keyguard's state is read (M7). */
const val DISMISS_CHECK_MS = 1_000L

/**
 * M7: one failure - the keyguard still locked [DISMISS_CHECK_MS] after the request, whatever the
 * callbacks said (KVM answers nothing while it goes away, and they die with the activity).
 */
fun dismissFailed(requestedAtMs: Long?, nowMs: Long, keyguardLocked: Boolean): Boolean =
    requestedAtMs != null && keyguardLocked && nowMs - requestedAtMs >= DISMISS_CHECK_MS

/** M7: at the 2nd consecutive failure `setKeyguardDisabled(true)`, which dismisses. */
fun dismissFallback(failures: Int): Boolean = failures >= 2

/**
 * M4/M7: the cover is written again at a lock resume with the screen on, when it is held off -
 * [heldOff]: this process hasn't armed it yet (a boot: off since `ACTION_SHUTDOWN`), or the fallback
 * ran - and no [wakePending]: the fallback's own wake isn't over yet (it waits for the next dark
 * screen-off, so a dismissal that keeps failing falls back at most once per wake).
 */
fun coverRearmDue(heldOff: Boolean, interactive: Boolean, lockResumed: Boolean, wakePending: Boolean): Boolean =
    heldOff && interactive && lockResumed && !wakePending

/**
 * M5: a screen of another app sent from the lock (the call screens, the VoIP ring screen, the
 * time-rule screen at a screen-on) waits while the cover's keyguard is locked - a non-showWhenLocked
 * activity on top would un-occlude it (SystemUI's lockscreen) and fail the lock's own dismissal.
 * True = wait for the unlock.
 */
fun sendAfterUnlock(coverOn: Boolean, keyguardLocked: Boolean): Boolean = coverOn && keyguardLocked

/**
 * qa-32-s2-code M2: the device policy outlives the process (an update, a crash, a reboot without
 * `ACTION_SHUTDOWN`), the in-memory state doesn't. A new process whose stored flag says the cover may
 * be written ([coverWritten]) writes DISABLED at once - `setKeyguardDisabled(true)` also dismisses a
 * Swipe screen a non-clean reboot left up (this replaces Home's boot dismissal, qa-32-s2-code L1) -
 * unless an apply already wrote in this process ([configured]).
 */
fun processStartWrite(coverWritten: Boolean, configured: Boolean): Boolean = coverWritten && !configured

/** M2: a rewrite (the VoIP edge, the re-arm, the fallback) needs an apply's inputs first - before one,
 * nothing is written (it would be UNTOUCHED from empty inputs). */
fun rewriteDue(configured: Boolean): Boolean = configured

/** M2: `disableNow` (the shutdown, the crash guard) acts on the cover in memory or the stored flag -
 * the guard trips in a new process, where nothing is in memory. */
fun disableNowDue(coverOn: Boolean, coverWritten: Boolean): Boolean = coverOn || coverWritten

/** The debug switch's test knobs for the dismissal (`--ez skipDismiss`, `--ez failDismiss`; memory only). */
enum class DismissTest {
    NONE,

    /** The lock never asks and never counts: the keyguard stays locked behind the lock. */
    SKIP,

    /** The lock "asks" without the call: the state check counts each one, the 2nd falls back. */
    FAIL,
}
