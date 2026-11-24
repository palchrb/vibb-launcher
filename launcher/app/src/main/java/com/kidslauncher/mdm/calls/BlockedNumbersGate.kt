package com.kidslauncher.mdm.calls

import android.app.Activity
import android.os.Bundle

/**
 * The handler of `android.telecom.action.MANAGE_BLOCKED_NUMBERS` while the phone is managed: it closes at once.
 * AppEnforcer pins it as a persistent preferred activity on every apply; [BlockedNumbersRuntime] enables it only while
 * managed (disabled, Android skips the pin and resolves the action to Telecom's page). Checked on the emulator (14, 16)
 * with `am start -a` of the action. Not affected: an explicit start of Telecom's activity, and
 * `TelecomManager.createManageBlockedNumbersIntent()`, whose intent names Telecom's package (checked on 14 and 16);
 * [BlockedNumbersRuntime]'s observer clears what is added there (`docs/testing/emulator.md` §6bg).
 */
class BlockedNumbersGate : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
