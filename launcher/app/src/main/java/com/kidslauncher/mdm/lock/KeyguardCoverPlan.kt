package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.keyguardDisabledFeatures as secureCameraRule

/*
 * The keyguard writer's plan (design 32 step 2's one writer, H2/M6; design 59's ANDROID) - pure, tested in
 * KeyguardCoverPlanTest. Design 132 removed the keyguard cover (COVER) and our own lock's disabled keyguard (DISABLED):
 * nothing writes `setKeyguardDisabled(true)` any more.
 */

/** How [KeyguardCover] writes the keyguard ([keyguardMode]). */
enum class KeyguardMode {
    /** Not ours to change: not managed, or no Android credential - `setKeyguardDisabled(false)`, the features with
     * Android's lock's bits out (B2's SECURE_CAMERA by the managed rule), T 0. */
    UNTOUCHED,

    /** Design 59: Android's PIN is the gate - `setKeyguardDisabled(false)`, [ANDROID_KEYGUARD_FEATURES] added, T = the
     * screen timeout ([androidWrite]). */
    ANDROID,
}

/**
 * Design 132 (§2.1, §2.1a): ANDROID on any managed phone with an Android credential - a Child PIN in the policy or not
 * (a fallback apply has no `kid_lock`), whatever the VoIP phase or the crash guard; UNTOUCHED otherwise. Both write
 * `setKeyguardDisabled(false)`, so a keyguard an older build left disabled is undone at the first apply; nothing
 * writes `setKeyguardDisabled(true)` any more.
 */
fun keyguardMode(managed: Boolean, deviceSecure: Boolean): KeyguardMode =
    if (managed && deviceSecure) KeyguardMode.ANDROID else KeyguardMode.UNTOUCHED

/**
 * The keyguard-disabled features to write, and the new `coverAdded` (M6) - the bits Android's lock set that weren't set
 * without it (kept in CE prefs `keyguard_cover`). [android] (design 59): what was added before taken out, then
 * [ANDROID_KEYGUARD_FEATURES] and B2's bit in; `coverAdded` = those of them that weren't set. Off: exactly `coverAdded`
 * taken out, a parent's or another tool's bits kept, plus B2's SECURE_CAMERA while [managed] (minus it unmanaged),
 * `coverAdded` 0.
 */
fun keyguardDisabledFeatures(current: Int, managed: Boolean, coverAdded: Int, android: Boolean): Pair<Int, Int> {
    val base = current and coverAdded.inv()
    if (android) return secureCameraRule(base or ANDROID_KEYGUARD_FEATURES, managed) to (ANDROID_KEYGUARD_FEATURES and base.inv())
    return secureCameraRule(base, managed) to 0
}

/**
 * H1: `setMaximumTimeToLock` under Android's lock - the effective screen timeout, so SystemUI's lock delay after a
 * timeout (`min(T - displayTimeout, lockAfter)`) is 0. 0 (none) with no known timeout.
 */
fun maxTimeToLock(effectiveTimeoutMs: Long?): Long =
    if (effectiveTimeoutMs != null && effectiveTimeoutMs > 0L) effectiveTimeoutMs else 0L

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
    val mode: KeyguardMode,
    /** The step that failed (`disabled`, `features`, `max_time_to_lock`) - logged; ANDROID never falls back. */
    val failed: String? = null,
    /** Read back or written (`null`: unreadable). */
    val features: Int? = null,
    val maxTimeToLockMs: Long? = null,
    /** `setKeyguardDisabled`'s value in force (`null`: the call threw) - always false. */
    val disabled: Boolean? = null,
    /** What threw - logged always, release included (qa-32-s2-code nit 2). */
    val errors: List<Pair<String, Exception>> = emptyList(),
)

/**
 * Writes [wanted], only what differs: ANDROID ([androidWrite]), or UNTOUCHED - `setKeyguardDisabled(false)` (which also
 * undoes a keyguard an older build left disabled), the features with Android's lock's bits out, T 0. [storeCoverAdded]
 * commits the new `coverAdded` (false = not stored).
 */
fun writeKeyguard(
    policy: KeyguardPolicy,
    wanted: KeyguardMode,
    managed: Boolean,
    coverAdded: Int,
    timeoutMs: () -> Long?,
    storeCoverAdded: (Int) -> Boolean,
): KeyguardWritten {
    var added = coverAdded
    fun store(value: Int): Boolean {
        if (value == added) return true
        if (!storeCoverAdded(value)) return false
        added = value
        return true
    }
    return if (wanted == KeyguardMode.ANDROID) androidWrite(policy, managed, added, ::store, timeoutMs) else untouched(policy, managed, added, ::store)
}

/**
 * Design 59's ANDROID ([writeKeyguard]): `setKeyguardDisabled(false)` (with a credential the keyguard is on
 * anyway), the features with [ANDROID_KEYGUARD_FEATURES] - the new `coverAdded` stored first, so the switch
 * back takes out exactly them - read back, then T = the screen timeout (lock delay 0, 59 §4.3), read back.
 * A failed step is reported in `failed` and logged, never a fallback: the Android PIN is the gate whatever these
 * write. A failed features step leaves `features` null, so the camera lock stays engaged ([secureCameraHeld]).
 */
private fun androidWrite(policy: KeyguardPolicy, managed: Boolean, coverAdded: Int, store: (Int) -> Boolean, timeoutMs: () -> Long?): KeyguardWritten {
    val errors = mutableListOf<Pair<String, Exception>>()
    var failed: String? = null
    val disabled = try {
        if (!policy.writeDisabled(false)) throw IllegalStateException("setKeyguardDisabled(false) refused")
        false
    } catch (e: Exception) {
        errors += "Android lock: setKeyguardDisabled(false) failed" to e
        failed = "disabled"
        null
    }
    val features = try {
        val current = policy.readFeatures()
        val (value, newAdded) = keyguardDisabledFeatures(current, managed, coverAdded = coverAdded, android = true)
        // M6's order: what is added is kept before it is written.
        if (!store(newAdded)) throw IllegalStateException("coverAdded not stored")
        if (value != current) policy.writeFeatures(value)
        val read = policy.readFeatures()
        // P59-4: B2's SECURE_CAMERA too while managed - the camera lock stands down only on what is read back here.
        val want = ANDROID_KEYGUARD_FEATURES or (if (managed) KEYGUARD_SECURE_CAMERA else 0)
        if ((read and want) != want) throw IllegalStateException("features read back wrong")
        read
    } catch (e: Exception) {
        errors += "Android lock: the keyguard features failed" to e
        failed = failed ?: "features"
        null
    }
    val t = try {
        val want = maxTimeToLock(timeoutMs())
        if (want <= 0L) throw IllegalStateException("no screen timeout")
        if (policy.readMaxTimeToLock() != want) policy.writeMaxTimeToLock(want)
        val read = policy.readMaxTimeToLock()
        if (read != want) throw IllegalStateException("max time to lock read back wrong")
        read
    } catch (e: Exception) {
        errors += "Android lock: the max time to lock failed" to e
        failed = failed ?: "max_time_to_lock"
        null
    }
    return KeyguardWritten(KeyguardMode.ANDROID, failed = failed, features = features, maxTimeToLockMs = t, disabled = disabled, errors = errors)
}

/** UNTOUCHED ([writeKeyguard]): `setKeyguardDisabled(false)` first, then Android's lock's bits and T out. */
private fun untouched(policy: KeyguardPolicy, managed: Boolean, coverAdded: Int, store: (Int) -> Boolean): KeyguardWritten {
    val errors = mutableListOf<Pair<String, Exception>>()
    val result = try {
        policy.writeDisabled(false)
    } catch (e: Exception) {
        errors += "setKeyguardDisabled(false) failed" to e
        null
    }
    val features = try {
        val current = policy.readFeatures()
        val (value, newAdded) = keyguardDisabledFeatures(current, managed, coverAdded = coverAdded, android = false)
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
    return KeyguardWritten(KeyguardMode.UNTOUCHED, features = features, maxTimeToLockMs = t, disabled = if (result == null) null else false, errors = errors)
}
