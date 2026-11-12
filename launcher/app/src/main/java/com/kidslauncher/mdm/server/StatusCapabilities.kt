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
    // Design 72: the policy's `calendar.feeds` written into local calendars, `calendar.feeds` in every report.
    com.kidslauncher.mdm.day.CALENDAR_FEEDS_CAPABILITY,
    // The quiet row plus a ping: the policy's `lock_reminders: "ping"` (the server sends `row` without this).
    com.kidslauncher.mdm.day.CALENDAR_PING_CAPABILITY,
    // Design 72b: the kid's own events handed over for the marked CalDAV calendar (`calendar.kid_caldav`), the
    // parent's edits applied back.
    com.kidslauncher.mdm.day.CALENDAR_KID_CALDAV_CAPABILITY,
    // Design 68: the policy's `app_permissions` (camera off per app) and `installed_apps[].access` in every report.
    APP_PERMISSIONS_CAPABILITY,
    // Server 0121: the policy's `time_policy.rules[].silent` - a silent rule turns on our own Do Not Disturb rule.
    RULE_SILENCE_CAPABILITY,
    // Design 75: the lock glance shows the weather (`launcher_ui.lock_weather`, `GET /api/devices/weather`); the
    // server's Device › Weather says so until a phone reports it.
    com.kidslauncher.mdm.weather.LOCK_WEATHER_CAPABILITY,
    // Design 76: the policy's `server_tls` (the certificate check the server switches on) and `server_tls` in every
    // report.
    SERVER_PIN_CAPABILITY,
    // Design 77: the per-channel `calls_mode`/`sms_mode`, the SMS keys of `call_policy`, `sms_state` in every report
    // and `POST /api/devices/sms-held`. The server offers "Styrt av Vibb" for SMS only to a phone that claims it.
    com.kidslauncher.mdm.sms.SMS_ENGINE_CAPABILITY,
    // Design 80 D13: the policy's `call_policy.sms_groups` ("Gruppemeldinger") and `sms_state.own_learned` (the own
    // number learned from a message, §4.4). Until a phone claims it the server draws the switch disabled.
    com.kidslauncher.mdm.sms.SMS_GROUPS_CAPABILITY,
    // Design 82: Meldinger's lock level (`lock_glance.sms`), its `app_display` entry and `sms_state.has_history`.
    // Until a phone claims it the server's rows say "after Vibb One is updated".
    com.kidslauncher.mdm.sms.MESSAGES_APP_CAPABILITY,
    // Design 87: `installed_apps[].logo` in every report, the answer's `wanted_logos` read and those logos uploaded.
    com.kidslauncher.mdm.server.dto.APP_LOGOS_CAPABILITY,
)

/** A time rule's "Stille" switch (server migration 0121): `rules[].silent` is enforced. */
const val RULE_SILENCE_CAPABILITY = "rule_silence_v1"

/** Design 68's capability: the server's `app_permissions::CAPABILITY`. */
const val APP_PERMISSIONS_CAPABILITY = "app_permissions_v1"
