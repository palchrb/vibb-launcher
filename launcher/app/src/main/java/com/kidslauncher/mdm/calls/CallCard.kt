package com.kidslauncher.mdm.calls

/*
 * The caller's avatar on the call screens and the "ongoing call" card on Home (user requests from
 * the emulator run, 2026-10-06). Pure, tested in CallCardTest; InCallActivity and HomeActivity
 * draw them.
 */

/** What the call screens show in the avatar circle. */
enum class CallAvatar {
    /** The contact's avatar exactly as on Home and in the phone book: the cached photo, else the
     * initial on the contact's colour (`KidAvatars.bindContact`). */
    CONTACT,
    /** The peach silhouette: unknown or withheld numbers, emergency numbers, and before the first
     * unlock (contact photos and names' colours are credential-encrypted). */
    SILHOUETTE,
}

fun callAvatar(knownContact: Boolean, emergency: Boolean, unlocked: Boolean): CallAvatar =
    if (knownContact && !emergency && unlocked) CallAvatar.CONTACT else CallAvatar.SILHOUETTE

/**
 * The emergency verdict of the call on screen, computed once per number (qa-11b-code #4): the
 * platform check is a binder call, and Home's call card and the call screen re-render every
 * second on the main thread. `null` (no number) is never an emergency, as before.
 */
class EmergencyVerdictCache {
    private var number: String? = null
    private var verdict = false

    fun isEmergency(number: String?, check: (String) -> Boolean): Boolean {
        if (number == null) return false
        if (number != this.number) {
            verdict = check(number)
            this.number = number
        }
        return verdict
    }
}

/**
 * Home's card while a call exists. [name] `null` = no known contact ("Call in progress");
 * [elapsedSec] `null` until the call has connected (ringing, dialing).
 */
data class OngoingCallCard(val name: String?, val elapsedSec: Long?)

/**
 * The card for our call: shown while any call is live (ringing, dialing, connecting, active, held
 * - [liveCall] = `OngoingCalls.hasLiveCall`), gone when it ends. [contactName] is the known
 * contact's name (blank = unknown), [connectTimeMs] `Call.Details.connectTimeMillis` (0 = not
 * connected yet). Emergency calls show no name.
 */
fun ongoingCallCard(liveCall: Boolean, contactName: String?, emergency: Boolean, connectTimeMs: Long, nowMs: Long): OngoingCallCard? {
    if (!liveCall) return null
    val name = contactName?.trim()?.takeIf { it.isNotEmpty() && !emergency }
    val elapsed = if (connectTimeMs > 0) ((nowMs - connectTimeMs) / 1000).coerceAtLeast(0) else null
    return OngoingCallCard(name, elapsed)
}
