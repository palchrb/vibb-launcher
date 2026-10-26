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
    com.kidslauncher.mdm.music.MUSIC_CAPABILITY,
    PARENT_PIN_EVENTS_CAPABILITY,
    WINDOW_RELOCK_CAPABILITY,
    COMMAND_RECEIPT_CAPABILITY,
    com.kidslauncher.mdm.lock.LOCK_GLANCE_CAPABILITY,
    DNS_CUSTOM_CAPABILITY,
    // Design 67: the browsers' managed config, the built-in secure-DNS hosts and Firefox's canary.
    DNS_BYPASS_CAPABILITY,
    BATTERY_CAPABILITY,
    INSTALL_NOW_CAPABILITY,
    // The RC-to-RC fix: a same-code release under a newer name installs (launcherBuildAhead). Legacy since
    // launcher-v0.36.0-rc.4 (every build its own versionCode); kept for builds tagged before it.
    SAME_CODE_UPDATE_CAPABILITY,
    POWER_CAPABILITY,
    // Design 64 step 2: the zone watch - fences, the crossing's fixes, `zone_watch` in every report.
    ZONES_CAPABILITY,
    com.kidslauncher.mdm.music.STORYTEL_JWT_CAPABILITY,
    // Design 59 step 4: claimed only with `set_kid_pin`'s handler (Q59-13) - the server then sends this phone the PIN.
    com.kidslauncher.mdm.lock.ANDROID_KEYGUARD_CAPABILITY,
    // Design 69: Dagen - the policy's `calendar`, `calendar` in every report.
    com.kidslauncher.mdm.day.CALENDAR_CAPABILITY,
    // Design 68: the policy's `app_permissions` (camera off per app) and `installed_apps[].access` in every report.
    APP_PERMISSIONS_CAPABILITY,
)

/** Design 68's capability: the server's `app_permissions::CAPABILITY`. */
const val APP_PERMISSIONS_CAPABILITY = "app_permissions_v1"
