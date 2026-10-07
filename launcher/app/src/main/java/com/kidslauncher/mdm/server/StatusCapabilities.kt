package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CALL_POLICY_CAPABILITY
import com.kidslauncher.mdm.lock.BOOT_COVER_CAPABILITY
import com.kidslauncher.mdm.lock.PIN_LOCK_CAPABILITY
import com.kidslauncher.mdm.play.PLAY_POLICY_CAPABILITY

/** What this launcher tells the server it enforces (`StatusReportRequest.capabilities`). Pure (the
 * names are constants), tested in SyncScheduleTest. `fcm_push_v1` is gone since design 19 - the
 * server never read it. */
val STATUS_CAPABILITIES: List<String> = listOf(
    CALL_POLICY_CAPABILITY,
    TIME_RULES_CAPABILITY,
    PLAY_POLICY_CAPABILITY,
    PIN_LOCK_CAPABILITY,
    KIOSK_ESCAPES_CAPABILITY,
    BOOT_COVER_CAPABILITY,
)
