package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.ParentPinResult

/**
 * Which local events ask for a sync at once (launcher round 6 §1) - pure, `SyncTriggersTest`. The
 * other triggers (foreign access, battery, the emergency call, every process start) decide in their
 * own code; this holds the parent PIN's rule.
 */
object SyncTriggers {
    /**
     * A parent-PIN check whose event the parent should see now: a use ([ParentPinResult.Ok]), a
     * wrong try, or a lockout that this try started. Not a lockout already running
     * ([lockoutAlreadyRunning], read before the check - also an unreadable state, which became a
     * lockout): no event was written, so a kid hammering OK causes no syncs. Not the results that
     * wrote nothing (too short, no PIN, storage or key unavailable, foreign access).
     */
    fun pin(result: ParentPinResult, lockoutAlreadyRunning: Boolean): Boolean = when (result) {
        ParentPinResult.Ok, is ParentPinResult.Wrong -> true
        is ParentPinResult.LockedOut -> !lockoutAlreadyRunning
        ParentPinResult.TooShort, ParentPinResult.NotConfigured, is ParentPinResult.Unavailable,
        ParentPinResult.ForeignAccessOn -> false
    }
}
