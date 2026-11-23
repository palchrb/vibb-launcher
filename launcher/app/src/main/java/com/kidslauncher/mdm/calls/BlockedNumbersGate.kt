package com.kidslauncher.mdm.calls

import android.app.Activity
import android.os.Bundle

/**
 * The handler of `android.telecom.action.MANAGE_BLOCKED_NUMBERS` while the phone is managed: it closes at once.
 * AppEnforcer pins it as a persistent preferred activity on every apply; [BlockedNumbersRuntime] enables it only while
 * managed (disabled, Android skips the pin and resolves the action to Telecom's page). Checked on the emulator (14, 16)
 * with `am start -a` of the action; `TelecomManager.createManageBlockedNumbersIntent()` and the Google Dialer's
 * settings row are not checked yet (`docs/testing/emulator.md` §6be). An explicit start of Telecom's activity is not
 * affected; [BlockedNumbersRuntime]'s observer clears what is added there.
 */
class BlockedNumbersGate : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
