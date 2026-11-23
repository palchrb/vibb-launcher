package com.kidslauncher.mdm.calls

import android.app.Activity
import android.os.Bundle

/**
 * `android.telecom.action.MANAGE_BLOCKED_NUMBERS` while the phone is managed: closes at once, so Android's Blocked
 * numbers page (Telecom's, a lock-task helper for the call screens) doesn't open from the kiosk - a dialer's Settings
 * row or any app firing the action lands here. AppEnforcer pins it as a persistent preferred activity on every apply;
 * [BlockedNumbersRuntime] enables it only while managed (disabled, Android skips the pin and its own page opens).
 * An explicit start of Telecom's activity still opens it; [BlockedNumbersRuntime]'s observer clears what is added there.
 */
class BlockedNumbersGate : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
