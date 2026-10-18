package com.kidslauncher.mdm.calls

import java.util.IdentityHashMap

/**
 * The in-call service's emergency calls that connected (design 35 step 3), keyed by the call
 * object itself (Telecom hands the same `Call` to every callback; main thread only).
 *
 * [connected] runs at every state change that shows the call connected (active, held and active
 * again, disconnected after connecting): it records the call with the same instant each time - the
 * call's own connect time, else its creation time (both Telecom's, so a restart of our process
 * mid-call gives the same value), else the first time it was seen - so the report's
 * `last_emergency_call_at` doesn't move between the report sent while the call is on and the next
 * one (the server announces a newer instant as a new emergency call). After the record is
 * committed it asks for one sync per call, so the parent hears of it within seconds; never before
 * the first unlock ([unlocked] false: the anchor and the sync aren't direct-boot-aware, and the
 * first report after the unlock carries the call from the device-protected record), and a call
 * skipped then asks at its next connected state after the unlock.
 */
internal class EmergencyConnects(
    private val unlocked: () -> Boolean,
    private val requestSync: () -> Unit,
) {
    private class Seen(val atMs: Long, var synced: Boolean = false)

    private val calls = IdentityHashMap<Any, Seen>()

    /** [connectTimeMs]/[creationTimeMs]: `Call.Details.connectTimeMillis`/`creationTimeMillis` (0 =
     * not known); [record] commits the instant. */
    fun connected(call: Any, connectTimeMs: Long, creationTimeMs: Long, nowMs: Long, record: (atMs: Long) -> Unit) {
        val seen = calls.getOrPut(call) {
            Seen(connectTimeMs.takeIf { it > 0 } ?: creationTimeMs.takeIf { it > 0 } ?: nowMs)
        }
        record(seen.atMs)
        if (!seen.synced && unlocked()) {
            seen.synced = true
            requestSync()
        }
    }

    fun removed(call: Any) {
        calls.remove(call)
    }
}
