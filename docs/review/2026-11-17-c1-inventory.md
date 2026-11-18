# C1 inventory: removing compatibility code (read-only, 2026-11-17)

Scope: PLAN.md "Consolidation before new features", round C1, and audit R4. Main is at `c9591f06f`. This is a read-only
inventory: nothing was edited, built or committed.

Paths start at the repo root. Launcher Kotlin paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/` and
are written `L/...`. Music Kotlin paths are under `music/app/src/main/java/...` and written `M/...`. Server Rust paths
are written `server/src/...`.

**Verdicts**
- **REMOVE**: compatibility code with an older launcher, server, page or build. It goes in C1.
- **KEEP**: the code stays, for the reason given.
- **C3**: the own PIN lock (phase D). It is listed here and handled in C3.
- **ASK**: a real judgment call for the user.

"Wording" means the code stays and only the comment loses its "older server/launcher" phrasing.

**Ground rules this inventory follows**
- Server migrations are append-only. No C1 item edits or deletes a migration, and none needs a new one. The DB
  columns that only old launchers used stay. Dropping them is a later, optional migration and is not in C1.
- A shared contract file changes on both sides in the same commit. That commit also updates
  `policy_json_keys_snapshot` and `PolicyResponseCompatTest` whenever a policy key goes.
- The launcher, the music app and the server ship together after C1. Install the launcher and music RCs on the
  emulators before tagging the server. Watchtower pulls a new server image as soon as it is tagged, and the current
  launcher still requires `music.mobile_data` (see M-1 below).
- The Jelly Star arrives with a fresh install of stable v0.40.0, built after C1. Everything it needs stays: the
  provisioning QR path, release signing (`RELEASE_CERT_SHA256`), today's versionCode rule, the self-update and
  install path (`pendingApkCheck`, `NOT_OURS`), the update fence's write and release path, and `FcmHosts` (other
  apps' FCM).
- From v0.40.0 on, a real phone carries a real build. Compatibility rules return for every change after it. That is
  why A-1 keeps the `capabilities` mechanism, emptied.
- The update fence: PLAN lifts its "never remove the release code" rule for this round only. There is no legacy in
  the fence to remove: `UpdateFencePlan.kt` has only v1, and its release path is live code. So the fence stays
  whole, and its rule in `launcher/CLAUDE.md:48` stays, with one line added: "lifted only for C1, back in force from
  v0.40.0".

---

## 1. Capability flags (cross-cutting: server, launcher and contracts)

The launcher claims 52 strings, listed in `L/server/StatusCapabilities.kt:11-110`. All of them are static per build,
and none is claimed at runtime, so every gate on them is compatibility between versions and nothing else.

| # | Item | file:line | Keeps compatible | Verdict | Risk | Tests touched |
|---|---|---|---|---|---|---|
| A-1 | The claims list | `L/server/StatusCapabilities.kt:11-110` (52 entries) and each `*_CAPABILITY` const across about 30 launcher files | Every server gate below | **REMOVE** the claims and constants. **KEEP** the report's `capabilities` field (an empty list) and the `capabilities_json` column as the mechanism from v0.40.0 on (ASK-1) | med: one list, many test references | 46 launcher test files, 110 lines: CalendarReportTest, ZoneReportTest, StatusReportsTest, InstallNowPlanTest, ServerPinsTest, AirplaneModeTest, ShortcutsTest, LoginWindowsTest, NotificationChannelsTest, UnifiedPushContractTest, MobileNetworkTest, SmsContractTest, LockNowTest, RulePauseTest, TimeRequestsTest, AppLogosTest, ... |
| A-2 | Shared `has_capability` helpers | `server/src/handlers/device_pages.rs:457`, `server/src/sms.rs:52` (`has_capability`/`has_engine`), `server/src/handlers/dns_filter.rs:278` (`claims`), `server/src/mobile_network.rs:138` (`has`), and inline `caps.iter().any(...)` in about 12 places | - | **REMOVE**. Where a gate also meant "the phone has reported at all", replace it with `latest.is_some()` or with the report field being present (for example `roaming_report` becomes "parse `data_roaming_json`") | med: the meaning of "no report yet" changes per page | 48 server test files, about 177 lines |
| A-3 | `call_policy_v1`, used as the retention keeper | `server/src/retention.rs:125-145` (`replace_over_budget`) and `:297-310` (`prune`): `capabilities_json LIKE '%call_policy_v1%'` | "Never the newest report that said it enforces calls" | **REMOVE**: the keeper becomes "the phone's newest report" | **high if missed**: once the claims go, the LIKE matches nothing and the keeper clause guards nothing | retention tests in `server/src/tests/` (status history budget) |
| A-4 | `call_policy_v1` page warning | `server/src/handlers/calls.rs:1560,1676,1956` | Calls page "not enforced" for a launcher without it | REMOVE | low | `tests/calls.rs` `calls_page_warns_without_capability` (768), `status_stores_capabilities_and_call_state` (715) |
| A-5 | `time_rules_v1` | `server/src/handlers/devices.rs:450-462` `launcher_without_rules` | The old schedule on the phone | REMOVE (together with B-1) | low | `tests/time_rules.rs` |
| A-6 | `sms_engine_v1`, `sms_groups_v1` | `server/src/sms.rs:21-60,665-666`, `older_launcher_expresses`/`older_launcher_loses` at `:23-37`; `server/src/handlers/calls.rs:405-411,1233` (`NotEnforced{stopped}`), `:1636-1651,2076-2100`; `server/src/handlers/calls_sms.rs:631,1038-1048` (`groups_shown`) | Calls page offers and explanations for a phone without the engine | REMOVE | med: the Calls page's offer logic | `tests/sms.rs` `the_calls_page_gates_what_an_older_launcher_cant_do` (678), `a_stored_pair_an_older_launcher_cant_do_is_explained` (766); `tests/calls_simpler.rs` `the_group_switch_needs_the_capability_and_its_marker` (976), `a_page_drawn_before_the_capability_never_turns_groups_off` (1170); `sms.rs:661-690` in-file; `sms_contract.json` `capability`, `groups_capability` |
| A-7 | `command_receipt_v1` | `server/src/handlers/device_api.rs:445-452,474,495-497,2219,2227`; `server/src/models.rs:962,1035-1040,1175-1181` (`Unconfirmed::CantConfirm`) | At-most-once delivery for an older launcher | REMOVE (always redeliver; `receipts = true`) | med: the command queue | `tests/command_receipts.rs` `an_older_launcher_gets_a_command_once` (139) |
| A-8 | `calendar_v1`, `calendar_feeds_v1`, `calendar_ping_v1`, `calendar_kid_caldav_v1` | `server/src/calendar.rs:20-23,441,448,537-540` (`served_lock_reminders`: `row` to an old launcher); `server/src/handlers/calendar.rs:176,204,215-216,426,815,868-881,1106`; `server/src/handlers/calendar_sources.rs:401`; `server/src/handlers/device_api.rs:456-466`; `server/src/handlers/device_pages.rs:2490` | Dagen pages wait for the phone; `ping` sent as `row`; `kid_caldav: null` | REMOVE | low | `calendar_kid_events/tests.rs` `an_older_launcher_and_an_unwritable_calendar_cant_be_picked` (1709), `:71`; `tests/calendar*.rs`; `ReminderPingTest`, `CalendarFeedsTest`; `calendar_status_report.json` `capabilities` |
| A-9 | `data_roaming_v1`, `mobile_data_v1`, `mobile_network_v2` | `server/src/mobile_network.rs:16-21,138-150,262,432,505-506,725,977`; `server/src/handlers/device_api.rs:1780` | Mobilnett page and kid switch wait for the phone | REMOVE | low | `tests/mobile_network.rs` (12 lines); `mobile_network.json` `capabilities` |
| A-10 | `kiosk_escapes_v1`, `boot_cover_v1` | `server/src/kiosk_escapes.rs:14,470,541`; `server/src/handlers/device_pages.rs:2695-2715` | Kiosk card says "update" | REMOVE | low | `kiosk_escapes.rs` `card_without_the_capability_only_says_update` (769); `tests/step11.rs`, `tests/step10.rs` |
| A-11 | `dns_custom_v1`, `dns_bypass_v1` | `server/src/dns_server.rs:195-199`; `server/src/handlers/dns_filter.rs:278,431` (`update_for_custom`), `:495` | DNS page notes | REMOVE | low | `tests/custom_dns.rs`; `dns_upstream_status.json` `capabilities` |
| A-12 | `zones_v1` | `server/src/models.rs:994`; `server/src/handlers/device_api.rs:371`; `server/src/zones.rs:735-742` and `server/src/handlers/locate.rs:317-318` (`served_interval` without a watch) | Zones `[]` for an old launcher | REMOVE | low | `tests/zone_watch.rs`; `zone_status_report.json` `capabilities` |
| A-13 | `app_permissions_v1`, `app_logos_v1` | `server/src/models.rs:998`; `server/src/handlers/device_pages.rs:2598-2599`; `server/src/app_logos.rs:290-297` (204 for an old launcher) | Apps page "update" note; no wanted list | REMOVE | low | `tests/app_logos.rs`; `app_logos.json` `capability`; AppLogosTest |
| A-14 | `parent_pin_events_v1`, `window_relock_v1` | `server/src/parent_pin.rs:194`; `server/src/handlers/parent_pin.rs:24-27,40`; `server/src/handlers/device_pages.rs:1058,1480`; `templates/device_lock.html:141`; `server/src/window_relock.rs:20`; `server/src/handlers/window_relock.rs:19,75` | `offline_override_used` flag; relock commands | REMOVE | low | `tests/window_relock.rs`, `tests/parent_pin*.rs` |
| A-15 | `lock_glance_v1`, `messages_app_v1`, `lock_weather_v1`, `lock_now_v1` | `server/src/lock_glance.rs:24-28,326`; `server/src/sms.rs:712`; `server/src/handlers/devices.rs:1235,1277`; `server/src/handlers/device_pages.rs:1494-1498` (`lock_buzz_needs_update`), `:2446-2447`; `server/src/handlers/weather_page.rs:28,45,65`; `server/src/handlers/quick_actions.rs:241` (the rc.8 warning) | Page notes "after Vibb One is updated" | REMOVE | low | `tests/lock_glance.rs` `meldingers_row_says_what_an_older_phone_shows_and_the_access_note_counts_it` (1078); `tests/weather.rs`; `lock_now.json` `capability`; LockNowTest |
| A-16 | `airplane_mode_v1`, `shortcuts_v1`, `notification_channels_v1`, `rule_pause_v1`, `time_answer_v1` | `server/src/airplane_mode.rs:168-171`, `handlers/device_pages.rs:1628`; `server/src/shortcuts.rs:144-147`; `server/src/notification_channels.rs:258-261`; `server/src/handlers/schedules.rs:2044`; `server/src/handlers/time_answer.rs:137`; the kept bits for the hidden rows, `server/src/handlers/devices.rs:3159-3176` | Rows hidden until the phone claims them | REMOVE (`kept_bits` goes too once the rows are always drawn) | low | `tests/notification_channels.rs` `an_older_launcher_or_a_failed_read_has_no_row` (271); `tests/time_request.rs` `no_with_and_without_the_capability` (718); `tests/airplane_mode.rs:162`; `tests/shortcuts.rs:188`; their contract `capability` keys |
| A-17 | `captive_portal_v1`, `login_browser_v1`, `server_pin_v1`, `unified_push_v1` | `server/src/logins.rs:22-24,518`; `server/src/handlers/logins.rs:93,263,373-374`; `server/src/handlers/device_api.rs:1772`; `server/src/server_tls.rs:753-759,1051-1055`; `server/src/unified_push/mod.rs:60,447-453`; `server/src/handlers/device_pages.rs:2570` | Policy key left out or page row hidden for an old launcher | REMOVE (always send `server_tls` and `unified_push`) | med: `server_tls` and `unified_push` become always present in the policy | `tests/server_tls.rs`, `tests/unified_push.rs`, `tests/logins.rs`; `login_windows.json`, `unified_push.json` `capability`/`capabilities` |
| A-18 | `music_v1` | `server/src/handlers/music.rs:4727-4733,4907` (`cant_deliver`) | "Phone can't deliver music" | REMOVE | low | `tests/music.rs` |
| A-19 | `install_now_v1`, `same_code_update_v1` | `server/src/install_now.rs:20,23-31,172-175,298-327` (`Standing::TooOld`), `:447` (`too_old`); `server/src/handlers/install_now.rs:57-58`; `L/server/SelfUpdatePlan.kt:405-418` | Install Now for a launcher without it, and RC-to-RC under the old rule | REMOVE (with E-1) | low | `tests/install_now.rs` `the_same_code_under_a_newer_name_is_offered_only_with_the_capability` (211); `install_now.rs` `the_same_code_needs_the_launchers_capability` (882); InstallNowPlanTest |
| A-20 | Claimed, never checked by the server: `battery_v1`, `power_v1`, `play_policy_v1`, `storytel_jwt_v1`, `rule_silence_v1`, `status_heartbeat_v1` | `L/server/StatusCapabilities.kt` entries; `server/src/status_heartbeat.rs:27-30` (dead-code const) | Nothing | REMOVE | none | `status_heartbeat.json` `capability`; StatusReportsTest; PowerEventsTest |
| A-21 | `pin_lock_v1`, `android_keyguard_v1` | `server/src/kid_lock.rs:18-20,73-74,123,153,180-191,285,891-893`; `server/src/attention.rs:656-657`; `server/src/handlers/device_pages.rs:1307-1308`; `server/src/handlers/device_api.rs:1688`; `server/src/models.rs:1524`; `L/lock/PinLockRuntime.kt:43`, `L/lock/AndroidLockPlan.kt:271` | The own lock and Android's lock for older launchers | **C3**: tangled with `kid_lock.rs`; take both out with phase D | - | `tests/android_lock_page.rs`, AndroidLockPlanTest |

## 2. Server (non-capability)

| # | Item | file:line | Keeps compatible | Verdict | Risk | Tests touched |
|---|---|---|---|---|---|---|
| B-1 | Frozen legacy schedule fields in the policy | `server/src/models.rs:108-113,156-162,489-495,1282-1287`; `server/src/handlers/device_api.rs:315-351,531-536` | Launchers before time rules | REMOVE from the wire and the structs. The DB columns stay | low (the launcher DTO fields are nullable) | `tests/time_rules.rs` `legacy_fields_are_still_sent_for_older_launchers` (1030); `policy_json_keys_snapshot` (`tests/device_api.rs:334-375`); PolicyResponseCompatTest |
| B-2 | `call_policy.managed`/`calls_enabled`/`sms_enabled` derived for old launchers | `server/src/models.rs:431-457,1443-1446`; `server/src/handlers/device_api.rs:742,773-774` | Launchers before design 77 | REMOVE the three wire fields. KEEP and rename the internal `policy.managed()` / `sms_enabled()` (live logic at `devices.rs:447,2208,2223`, `device_api.rs:743`, `calls.rs:1721`) | med (contract) | `tests/sms.rs` `every_mode_sends_its_derived_old_keys` (171), `a_new_phone_reads_as_before_0130` (211); `tests/calls.rs:464`; `sms_contract.json` `modes`; snapshot |
| B-3 | Old columns written only for a rollback | `server/src/handlers/calls.rs:2103-2125`; doc `server/src/models.rs:164-167` | An older server binary after a rollback | REMOVE the writes; the columns stay. C1 ships forward only | low | `tests/sms.rs:270,610` |
| B-4 | `call_policy.sms_lock_text` | `server/src/lock_glance.rs:55-58`; `server/src/handlers/device_api.rs:406,418,725-731,780`; `server/src/models.rs:1457-1458` | Launchers before design 82 | REMOVE | low (contract) | `tests/lock_glance.rs` (400, 1007); `sms_contract.json` `app.lock` |
| B-5 | `data_roaming` derived from `abroad` | `server/src/mobile_network.rs:38-42` (`derived_roaming`); `server/src/handlers/device_api.rs:613-616`; `server/src/models.rs:1391-1394` | Launchers before design 97 | REMOVE (with L-4) | low (contract) | `mobile_network.json` `policy` rows; `tests/mobile_network.rs`; snapshot |
| B-6 | `music.mobile_data` one switch | `server/src/music.rs:107-111` (`mobile_data_wire`), `:1356-1358,1407` | Older launcher and music app | REMOVE **only in lockstep with M-1** | **high if out of order**: today's launcher rejects the music object without it (`L/music/MusicPlan.kt:133`) | `tests/music.rs:1268-1273` |
| B-7 | "Update the app" for the old music switch | `server/src/music.rs:113-169` (`OlderApps`, `older_phone`, `*_EXTRA_BYTES_FROM`); `server/src/attention.rs:85,170,281-282,342,392,446,796`; `server/src/handlers/music.rs:4587,4652,4751,5060-5063`; i18n `device.music.update_*`, `older_phone_sub` | Older launcher or music app, by versionCode | REMOVE | low | `music.rs` in-file (2066, 2086); `tests/music.rs` `the_page_says_an_older_phone_uses_mobile_data_only_with_both` (1580) |
| B-8 | Relock fallback when a report has no `relock_blocks` | `server/src/window_relock.rs:383,434-435,545-578,968`; `server/src/handlers/window_relock.rs:126-145` | An older launcher's relock report | REMOVE | med | `tests/window_relock.rs` (111, 207); `window_relock.rs` `an_older_launchers_relock_lists_its_blocked_apps` (1245) |
| B-9 | Update Location failure strings | `server/src/handlers/locate.rs:511-517,569-573` | Old "done + failure text" answers | REMOVE | low | `locate.rs` in-file (3121) |
| B-10 | Report intake tolerant of absent fields (`time_state` → `lock_reason`, `requested_at` absent, `audio_bytes` absent, NULL `success`) | `server/src/time_state.rs:94-100`; `server/src/handlers/devices.rs:399-410`; `server/src/models.rs:1045,1971-1974`; `server/src/music.rs:171-178` | Older launchers, but also malformed or "unknown" input from today's | KEEP (lenient intake; the current launcher also omits `requested_at`). Wording | - | - |
| B-11 | Tests for dropped old report keys | `tests/intake_limits.rs` `an_older_launchers_battery_exempt_is_accepted_and_dropped` (498); `tests/play.rs` `an_old_launchers_push_report_is_accepted_and_dropped` (79); `server/CLAUDE.md:78-79` | Old launchers' `battery_exempt`, `push` | REMOVE (tests and docs only; serde already drops unknown keys) | none | the two tests |
| B-12 | Older admin-page posts: display `action=catalog/own`; tracked-app display `own`; quick-controls form without `section`; `clock_format` missing; `anchor=music`; music settings without `v=2` and a missing `storage_limit`; schedules `section=switch` or none; add contact without `switches`; calls form without `section`; blocklist `clear/enabled`; the `/lock-screen/apps/text` route and `stale_page` | `server/src/handlers/devices.rs:1323-1326,1421-1426,2683,2762-2767,3098-3109,3618-3645`; `server/src/handlers/tracked_apps.rs:4243,4254`; `server/src/handlers/music.rs:5554-5594`; `server/src/handlers/schedules.rs:29-32,1625-1641`; `server/src/handlers/calls.rs:2031-2034,2194-2260`; `server/src/handlers/dns_filter.rs:1137-1155`; `server/src/main.rs:1561-1565`; `server/src/handlers/lock_glance.rs:91-94,139-144`; i18n `error.out_of_date` | PWA pages and tabs cached from before an update | REMOVE (a missing field becomes a 400). Every save path keeps "no jump to top" | low | `tests/app_display.rs` (153); `tests/device_pages.rs` (1409-1459); `tests/launcher_ui.rs:598`; `tests/music.rs` `a_page_from_before_the_update_sets_both_switches_from_mobile_data` (1360); `tests/time_pages.rs` `a_switch_only_post_keeps_the_limits` (689-740); `tests/sms_page.rs` (196); `tests/lock_glance.rs` `refused_saves_write_nothing` (666) |
| B-13 | `on_home_shown` marker; `keyguard_needs_pin` refusal | `server/src/handlers/devices.rs:1452-1454,3228-3292`; `server/src/kid_lock.rs:1145` | Marker logic is live (forms without the switch) | KEEP (wording); the keyguard refusal is C3 | - | - |
| B-14 | `both_ways` without `*_shown` | `server/src/handlers/calls_form.rs:59-61` | Older page | REMOVE (optional; refuse a missing value) | low | `calls_form.rs:266`; `tests/calls_simpler.rs` (295) |
| B-15 | Old bookmark redirects: `/dns?device=`, `/schedules?device=`, GET `/devices/{id}/weather` | `server/src/handlers/dns_filter.rs:586-590,608,1234`; `server/src/handlers/schedules.rs:363-375`; `server/src/handlers/weather_page.rs:77-81`; `server/src/main.rs:1166-1167` | Old bookmarks and links | REMOVE (the PWA's start URL is `/`; ASK-4 only if the user keeps such bookmarks) | low | `tests/device_pages.rs` (1481); `tests/time_pages.rs` (207); `tests/music_spotify.rs:5124`; `tests/weather.rs:1119` |
| B-16 | Pre-monorepo config defaults: `LEGACY_SERVER_RELEASE_REPO`, `LEGACY_LAUNCHER_APK_URL`, `LEGACY_LAUNCHER_ADMIN_COMPONENT`, `migrate_legacy` | `server/src/config.rs:23-53,137-162` | The `kid-phone-server` repo, `com.kidslauncher.mdm/...` and the old APK URL in a `.env` | REMOVE **after ASK-2** | **high if the `.env` still holds the old admin component**: the Jelly Star's QR would name a component that doesn't exist | `config.rs` in-file (661-675) |
| B-17 | Deploy scripts' old-install cleanup: `remove_host_helpers`, `startup::HOST_FILES`, `KPS_REPO` mapping, `docs/legacy/kid-phone-server-update.sh` stub | `server/deploy/install.sh`, `server/deploy/update.sh`, `server/src/startup.rs`; DEPLOY.md 454-473 | Pi installs from server 0.66 and older, and pre-monorepo installs | REMOVE (the only install is the Docker VM, well past 0.67) | low | the deploy scripts' smoke steps; `server/deploy/image-smoke.sh` if it references them |
| B-18 | Sessions from before 0.55.0 (no sign-in time) and before slice 3a (`LoginWarning` as a string) | `server/src/security.rs:972-980,1131`; `server/src/admin_auth.rs:539-541`; `server/src/auth.rs:1192-1197` | Old admin sessions | REMOVE (sessions expire after `session_max_days`; the worst case is one sign-out) | low | auth tests touching the fallback |
| B-19 | Startup data conversions on the real DB: `time_rules::migrate_legacy` (pre-0025 schedules), `migrate_crossing`/`normalise_all` (pre-design-66 rules), the easy-PIN sweep (`parent_pin.rs:120-124`), catalog backfills (`tracked_apps.rs:663-666,926-930,972-975,1015-1020`) | `server/src/time_rules.rs:387-620,813-1000`; `server/src/main.rs:590-611,2011-2019`; `server/src/parent_pin.rs:120-124` | Old **rows in the user's real DB**, or a restored old backup | **KEEP this round** (they act as data migrations; PLAN keeps those). ASK-3 if the user wants them gone | - | `tests/time_rules.rs` `legacy_schedules_become_rules_once` (908), 1691-1745; `time_rules.rs` in-file 1118-1265, 1365-1615 |
| B-20 | Old DB rows shown as stored: security-log prose before design 70, `parent_events` words and rows before 0098, NULL location provider, free-text catalog packages (`devices.rs:2644,2688-2692,2776`), parent usernames lowercased | `server/src/security.rs:133-137,379,428`; `server/src/handlers/admin.rs:26,63-69,193`; `server/src/parent_events.rs:739,1005,1093,2763-2853,4314,4597`; `server/src/retention.rs:409`; `server/src/models.rs:882`; `server/src/parents.rs:50` | Real rows in the user's DB (logs under 180 or 90 days old, catalog rows) | KEEP (real data) | - | `tests/notifications.rs` `older_rows_keep_their_stored_english` (218); `tests/device_pages.rs` `a_legacy_catalog_package_stays_switchable` (2735) |
| B-21 | Status heartbeat: a full report without a hash, `{"full_report": true}`, the config switch | `server/src/status_heartbeat.rs:3-30,247` | It is the protocol and a kill switch, not compatibility | KEEP (wording) | - | - |
| B-22 | Comment-only "older launcher / absent from older launchers / older server" | about 45 field docs in `server/src/models.rs`, plus about 60 single mentions across about 35 files | - | Wording only (the `Option` fields with serde default stay for heartbeats and lenient intake) | none | - |

## 3. Launcher (non-capability, non-versionCode)

| # | Item | file:line | Keeps compatible | Verdict | Risk; a very old dev emulator | Tests touched |
|---|---|---|---|---|---|---|
| L-1 | µLauncher prefs migration chain | `L/preferences/Preferences.kt:11-16,19-24,28-87`; `L/preferences/legacy/Version{1,2,3,4,100,Unknown}.kt` (287 lines); `L/Application.kt:23,410-411` | µLauncher and early prefs (versions 1-100) | REMOVE (a fresh install needs none of it; optionally also the `version_code` pref) | low | `AirplaneRouteTest.kt:130` uses `"migratePreferencesToNewVersion"` as an ordering anchor and needs a new one |
| L-2 | `legacyTimePolicy` (fixed windows to rules) | `L/timerules/TimeRules.kt:13-14,288-331`; `L/server/KidModeEnforcer.kt:33-40`; DTO `L/server/dto/PolicyResponse.kt:37-41,49-54`; `LastEnforcedPlan` `L/server/PolicyGate.kt:93-95,146-154,179-184,202-207` | Server without `time_policy` | REMOVE (with B-1, same commit) | low | TimeRulesTest (304, 331), KidModeEnforcerTest (41-80), PolicyResponseCompatTest (1183), PolicyGateTest |
| L-3 | `LEGACY_LOCATION_POLICY` (every 10 min) | `L/server/LocationPolicies.kt:38-40,49,104`; `L/server/MdmSyncWorker.kt:301`; `L/push/BackstopAlarm.kt:11,49`; `L/ui/quickcontrols/WifiNetworksActivity.kt:20,132`; `L/server/PowerEvents.kt:403` | Server without `location_policy` | REMOVE (null becomes `LocationPolicy()`, on request) | low | LocationPolicyTest `an older server keeps the old 10-minute behaviour` (39), ZonePlanTest |
| L-4 | Abroad `LEGACY` choice from `data_roaming` | `L/server/MobileNetwork.kt:24-25,35,38-46`; `L/server/AppEnforcer.kt:381`; `L/server/MobileNetworkRuntime.kt:100`; DTO `PolicyResponse.kt:159-166`; `PolicyGate.kt:162-166,190,213` | Revision-3 server | REMOVE the `data_roaming` read. A **null policy** (Device Owner before its first policy) keeps today's "touch nothing" under a new name (`NONE`). That is the pre-enrolment state, not compatibility, so it needs no ASK | med (contract, with B-5) | MobileNetworkTest (283), PolicyResponseCompatTest; `mobile_network.json:13-14` `abroad_choice` legacy rows |
| L-5 | Lock glance: `apps` as strings, missing `sms` (`GlanceSms.LEGACY`), missing `lock_glance` → `SERVER_DEFAULT`; `sms_lock_text` | `L/badges/GlanceRows.kt:41-96`; `L/sms/SmsGlance.kt:28-36`; `L/server/dto/CallPolicy.kt:30-31`; `L/calls/CallRules.kt:93`; `L/calls/CallPolicyStates.kt:32` | Servers before designs 51 and 82 | REMOVE (missing `sms` becomes OFF; fresh null becomes UNKNOWN; fail closed) | low (contract, with B-4) | GlanceRowsTest, SmsGlanceTest, SmsContractTest, PolicyResponseCompatTest; `sms_contract.json:192-196` `lock_read` legacy rows |
| L-6 | D2 channel-mode fallback, and the DTO's `managed`/`calls_enabled`/`sms_enabled` | `L/calls/CallPolicyStates.kt:39-53`; `L/server/dto/CallPolicy.kt:15-17,24-28`; `L/server/PolicyGate.kt:115` | Server before the SMS engine | REMOVE the fallback (null modes become OFF/OFF) **and** the three DTO fields, in the same commit as B-2. The fixture churn is mechanical. KEEP the internal `CallRules.callsEnabled/smsEnabled` (10 readers plus the BootCallPolicy DE witness) | med (contract) | CallPolicyStateTest, PolicyGateTest, SmsContractTest `an older server's switches read as D2's migration table` (83), DialerRoleTest `an old server releases the role...` (33), many `CallPolicy(...)` fixtures; `sms_contract.json:4-11` `migration` |
| L-7 | Older-server lift closes a time request; `requestAnswers` flag; `liftIdAtAsk` | `L/timerules/TimeRequests.kt:25-26,66-90,169-171,187,300-313`; `L/timerules/TimeRules.kt:88-93` | Server without `request_answers` | REMOVE | low (contract) | TimeRequestsTest (143), PolicyResponseCompatTest; `time_request.json:106` `older_server`, `:415-427` its `states` row |
| L-8 | `allow_blocked` with null `allow` lets every block out | `L/server/dto/PendingCommand.kt:18-20`; `L/server/WindowRelockPlan.kt:345-361`; `L/server/WindowRelock.kt:405-410` | Command rows queued before server 0068 | REMOVE (null lets nothing out: fail closed) | low | RelockBlockTest |
| L-9 | Calendar `OLDER_SERVER` (missing = usable, every calendar shown) | `L/day/CalendarPolicy.kt:86-87,96-103` | Older server | REMOVE (null becomes `UNKNOWN`, Dagen off) | low-med: the fixture constant is used by 6 tests | CalendarPolicyTest (16), DayModelTest, FeedPlanTest, KidEventTest, RemindersTest, PolicyResponseCompatTest |
| L-10 | `sms_groups` absent = true | `L/server/dto/CallPolicy.kt:43-46`; `L/calls/CallRules.kt:99-101` | Older server and pre-D13 stored rules | REMOVE (absent becomes false: fail closed; the server always sends it) | low | PolicyResponseCompatTest, SmsContractTest, MessagesModelTest, MmsEngineTest |
| L-11 | Rollback guards `time_policy_seen` / `channel_modes_seen` / `calls_managed_last` | `L/server/PolicyGate.kt:83-124`; `L/server/MdmSyncWorker.kt:147,1414-1415`; `L/preferences/Preferences.kt:~92-124` | A rolled-back server | KEEP: defence in depth that fails closed (PLAN keeps those). Simplify only where it reads the removed `managed` (L-6) | - | PolicyGateTest |
| L-12 | Parent PIN vault migration (plain hash before design 24b) | `L/server/PinVault.kt:30-39,53-55,76,80-84,166-170,175-217,229,256-268`; `L/server/ParentPin.kt:25-51`; `L/Application.kt:480-483`; prefs `override_pin_hash/salt` (`LauncherPreferences$Config.java:87-91`, `res/values/donottranslate.xml:58-59`) | A pre-24b build's stored parent PIN | REMOVE (keep `FingerprintChange` for re-wraps) | low: the parent PIN reads as "can't check" until the next sync | PinVaultTest (125, 153) |
| L-13 | Parent PIN counter migration | `L/server/ParentPinStore.kt:12-19,34,45-53,96-101,161-180`; `L/server/ParentPinLockout.kt:174-190`; prefs `$Config.java:103-107` | Pre-24 counter | REMOVE; **replace the first-load init** (`load()`'s migrate path also creates a fresh phone's state) with `ParentLockoutState(0, fp)` and `save` | low | ParentPinStoreTest (283, 297), ParentPinLockoutTest (184), ParentPinCallersTest (58) |
| L-14 | `SealedPrefs` plaintext sealed on read; `Secrets.migrateAll`; the `migrate` parameter | `L/server/SealedPrefs.kt:50-60,101-107,160-197,211`; `L/Application.kt:484-486`; `L/server/PolicyEtags.kt:28,41`; `L/server/WindowRelockStore.kt:23,185`; `L/push/up/UpStore.kt:116`; `L/lock/PinLockStore.kt:60-64` (C3) | A pre-r10 build's plaintext secrets | REMOVE (unprefixed becomes `Unreadable`, fail closed). Every emulator that ran any post-r10 build has migrated already (`migrateAll` runs at start) | med: only a never-updated pre-r10 emulator would lose its token and need re-enrolling | SealedPrefsTest (109-171), SealedGlueTest (15), KidPinSealTest (142, C3) |
| L-15 | Small one-time cleanups: old `launcher:messages` channel deleted; old crash channel deleted; old APK cache sweep; `TimeRuleAlarm.legacyCancelled`; `openUntilMs`; `clearRadioRestrictions` | `L/sms/SmsNotifier.kt:28-33,74`; `L/Notifications.kt:15,113-114`; `L/server/AppDownloadPlan.kt:252-259` + `L/server/AppDownloads.kt:264-297`; `L/timerules/TimeRuleAlarm.kt:30-47`; `L/timerules/TimeRequests.kt:27-34`; `L/server/AppEnforcer.kt:651,1456-1470` | State left by older builds | REMOVE | low (a stale channel, cache file or restriction on an ancient emulator) | AppDownloadPlanTest, TimeLeftNoticeTest |
| L-16 | Optional small ones: cached fix without provider; emergency and role record defaults; `InstallNow` record's older shape | `L/server/LocateCommands.kt:47,62-63,246-250,380`; `L/calls/CallSystem.kt:324-388`; `L/calls/CallRules.kt:345-349`; InstallNowPlanTest (194, 216) | Older build's stored records | REMOVE if cheap; harmless defaults otherwise | low | ZonePlanTest, EmergencyConnectsTest, InstallNowPlanTest |
| L-17 | Adopted relock marker; record without catalog pins; HTTP "no route / 404" handling; fail-closed DTO defaults (`login_browser`, UP off, `copy_of`, screen timeout, clock format) | `L/server/WindowRelockPlan.kt:104-109`; `L/server/AppDownloads.kt:732-739`; `L/timerules/TimeRequests.kt:137`, `L/calls/ContactRequests.kt:56,148`, `L/music/*TokenCourier.kt`; `L/logins/LoginWindows.kt:20-41`; `L/push/up/UpRules.kt:20` | Also catch failed commits, malformed input and absent routes | KEEP (fail-closed or generic). Wording | - | - |
| L-18 | Platform "legacy", not ours: `phoneinECMState`, Android 14/15 keyguard offset, the non-adaptive icon inset, `NOTE_OLD_CONNECTOR` (a third-party UP connector), Snarveier's channel re-create | `L/server/SelfUpdate.kt:38,266`; `L/lock/LockGlance.kt:574-600`; `L/ui/home/KidAvatars.kt:331`; `L/push/up/UpStore.kt:61-66`; `L/shortcuts/ShortcutsRuntime.kt:326-336` | Android versions 14-17 and OEMs | KEEP | - | - |
| L-19 | Package rename `com.kidslauncher.mdm` → `me.vibb.launcher` | no code handles the old applicationId; `NOT_OURS` (`L/server/SelfUpdatePlan.kt:178,221`) is the generic `archivePackage != ownPackage` | - | KEEP (generic). Server migration 0037 stays. Docs history goes (D-*) | - | - |
| L-20 | FCM leftovers | none in code. `L/push/FcmHosts.kt` (other apps' FCM, Element X) and the release-classpath ban (`launcher/app/build.gradle.kts:241-351`) | - | KEEP both. Only doc mentions go (`PolicyResponse.kt:43`, `StatusCapabilities.kt:9`) | - | FcmHostsTest stays |
| L-21 | Own PIN lock (`PinLock*`, `PinLockRuntime`, `PinLockActivity`, `ui/LockActivity.kt`, `KidLock` missing `keyguard` = own, keypad→Android migration `L/lock/AndroidLock.kt:30,183-206`, `WindowRelockPlan.kt:135-137`) | - | - | **C3** | - | - |
| L-22 | Comment-only "missing (an older server) = off" | about 125 lines in about 50 files (35 in `PolicyResponse.kt`) | - | Wording ("missing = off"); the defaults stay | none | - |

## 4. Music app

| # | Item | file:line | Keeps compatible | Verdict | Risk | Tests touched |
|---|---|---|---|---|---|---|
| M-1 | `mobile_data` one-switch fallback, three-sided | `M/.../bridge/BridgeConfig.kt:55,129-136`; `M/.../data/Store.kt:115-117` (debug feed); launcher `L/music/MusicPlan.kt:67-68,116-117,130-135,176-188,286`; server B-6 | Launcher and server before design 30. The user's music app is 0.6.1-rc.1, past design 30 | REMOVE in one commit across all three, then ship the launcher and music RCs before the server | **high if out of order** (the current launcher requires `mobile_data`) | BridgeTest (82-190), GuardsTest (446-472); launcher MusicPlanTest (79-163), MusicMediaPlanTest, MusicFileDownloadsTest; `bridge_contract.json` in both apps (byte-identical) |
| M-2 | Old Storytel login purge | `M/.../bridge/StorytelLegacy.kt` (whole file); `M/.../sync/Sync.kt:24,303-304`; `M/.../storytel/StorytelAccount.kt:26` | Music app with its own login before design 58 | REMOVE | low (at worst a dead sealed file on a phone that never synced since) | StorytelLegacyTest (delete); SourceScanTest:955-975 |
| M-3 | Debug feed's `storytel.json` drop | `music/app/src/debug/.../DebugStorytel.kt:38-46`; `DebugFeedReceiver.kt:43,89` | Debug feed seeded the pre-58 way | REMOVE | none | SourceScanTest:654-668 (keep the manifest asserts) |
| M-4 | AIDL `getStorytelLogin` and `IStorytelCallback.aidl` | `*/app/src/main/aidl/me/vibb/bridge/IMusicBridge.aidl:28-31` (6th method); `IStorytelCallback.aidl`; launcher `MusicBridgeService.kt:91-95` with `answerStorytel` | Music app before design 58 step 2 | REMOVE the method's meaning but **keep the slot**: swap it for a no-argument `oneway void reserved();` at the same position (same transaction code), so `IStorytelCallback.aidl` and `answerStorytel` can go | **high if deleted outright**: 8 later methods would shift their transaction codes (AIDL numbers by order) | BridgeTest (26, 37), MusicPlanTest (42-53); both AIDL copies byte-identical |
| M-5 | Tolerant reads: timeouts for a launcher without the token method; storage limit absent = 2 GB; art URL dropped; `sonos_rooms` absent = all; "a newer server never makes an older app prune"; own copies without a SHA record; Room AutoMigrations 1-8; `heartedAt` 0; SpeakerCache null | `M/.../bridge/SpotifyBridgeToken.kt:35,72`; `StorytelBridgeToken.kt:34,78`; `BridgeConfig.kt:75,138-145`; `model/Listing.kt:79`; `model/Library.kt:20-30,114,186-191`; `logic/OwnFiles.kt:18-33`; `data/MusicDb.kt:506-535`; `data/Snapshot.kt:121,129`; `sonos/SpeakerCache.kt:47,155` | Also hung launchers, junk input, live semantics, and on-device DB data | KEEP (wording only) | - | - |
| M-6 | `wantArt` comment; Storytel backoff and Spotify audio-mode comments | `M/.../bridge/BridgeSync.kt:115-117`; `storytel/StorytelSession.kt:217`; `logic/Spotify.kt:239` | - | Wording | none | - |

## 5. Contracts (shared JSON, both sides in one commit)

| # | File | Rows | Verdict | Goes with |
|---|---|---|---|---|
| K-1 | 16 files with `capability`/`capabilities`/`groups_capability`: `calendar_status_report`, `dns_upstream_status`, `status_heartbeat`, `lock_now`, `airplane_mode`, `mobile_network`, `notification_channels`, `app_logos`, `time_request`, `sms_contract`, `rule_pause`, `login_windows`, `time_status_report`, `shortcuts`, `unified_push`, `zone_status_report` | the `capability` keys, and the `capabilities` arrays in report bodies (these become `[]`) | REMOVE | A-1 (launcher claims) |
| K-2 | `sms_contract.json` | `migration` (4-11), `modes` derived old keys, `app.lock` `sms_lock_text`, `lock_read` legacy rows (192-196) | REMOVE | B-2, B-4, L-5, L-6 |
| K-3 | `mobile_network.json` | `abroad_choice` legacy rows (13-14), the `policy` rows' `data_roaming` | REMOVE | B-5, L-4 |
| K-4 | `time_request.json` | `older_server` (106) and its `states` row (415-427) | REMOVE | L-7 |
| K-5 | `launcher_tag_codes.json` | the old-rule `codes` rows (`launcher-v0.2.7` … `launcher-v0.36.0-rc.3`, `-rc.1@70`, `0.34.2`, `0.0.1`) and the old-rule `music_codes` rows (`music-v0.0.1` … `-v0.5.0-rc.3`). The new-rule rows and the invalid-tag `null` rows stay; rewrite `about` | REMOVE | E-1 |
| K-6 | `bridge_contract.json` (launcher and music) | `mobile_data` | REMOVE | M-1 |
| K-7 | `login_windows.json` `older_server`, `unified_push.json` `older_server`, `rule_pause.json` "copy without `copy_of`" (107), `airplane_mode.json:160` | These pin the launcher's **fail-closed** reading of a missing key | KEEP. Optional: rename `older_server` to `missing_keys` (both sides) | - |
| K-8 | `CLAUDE.md` contract paragraphs | each paragraph's "capability", "older server", "migration" and "old keys" clauses | REMOVE with the rows | K-1 … K-6 |

## 6. versionCode, update path and release (cross-cutting)

| # | Item | file:line | Keeps compatible | Verdict | Risk | Tests touched |
|---|---|---|---|---|---|---|
| E-1 | Old tag rule read (`X*1_000_000+Y*1_000+Z`, RC one less) | `L/server/SelfUpdatePlan.kt:50-75` (`NEW_RULE_FROM`, old branch of `launcherTagVersionCode`); `server/src/install_now.rs:43-47,74-112` (`old_rule_code`, `FIRST_NEW_RULE_CODE`, `tag_code_as_read_by`), `:313-320`; `server/src/launcher_check.rs:353,373` (callers stay, simplified); `server/src/handlers/tracked_apps.rs:2508-2515` (stays) | Phones on builds before `launcher-v0.36.0-rc.4` | REMOVE the old branch. Old tags still on GitHub then read by today's rule (`launcher-v0.35.0` becomes 350009), which is still below every build since rc.4, so "newest" and "behind" stay correct | low | SelfUpdatePlanTest (tag rows), `install_now.rs` `old_rule_phones_read_new_rule_tags_as_they_do` (838), `launcher_tags_read_as_the_shared_contract`; K-5 |
| E-2 | `launcherBuildAhead` / `build_ahead` name rule (the same code under a newer name) | `L/server/SelfUpdatePlan.kt:93-114,152-154,224,452,532-541`; `server/src/install_now.rs:115-160` | Every RC of a version sharing a code under the old rule | REMOVE the name rule (the code alone decides; the same code means the same build). `trippedVersionName` in `L/lock/BootCoverPlan.kt:110-115` follows | low | `install_now.rs` (713-769), SelfUpdatePlanTest, BootCoverPlanTest |
| E-3 | Music version from code, old-rule branch | `server/src/handlers/device_pages.rs:297-322` (`MUSIC_NEW_RULE_FROM`, `version_of_code`), `:2793` | Music builds up to `music-v0.5.0-rc.3` | REMOVE the old branch. A debug build's default code (music 1, launcher 116) reads as a nonsense version either way | low | `device_pages.rs` `music_versions_read_as_the_shared_contract` (2825); K-5 |
| E-4 | `scripts/release-version.sh` | `:14-24` (history comment), `:30-32` and the self-test rows (`check launcher-v0.36.0-rc.3 -`); the refusal of a tag below the first new-rule tag | - | REMOVE the history and the old-rule rows; **KEEP the refusal** (a lower tag would also be lower than every build out there; harmless guard) | low | `--selftest` |
| E-5 | CI comments on the old rule | `.github/workflows/launcher.yml:197-200`, `music.yml:207` | - | REMOVE (comments) | none | - |
| E-6 | Today's versionCode rule, release signing, `RELEASE_CERT_SHA256`, the provisioning QR's `releases/latest` URL, `pendingApkCheck` (`NOT_OURS`, `DOWNGRADE`), the update fence (`L/server/UpdateFencePlan.kt`, v1 only, no legacy inside), `PolicyEtags` versionCode match | - | Real phones from v0.40.0 | **KEEP** | - | - |
| E-7 | `HANDY_FCM_*` repository variables (GitHub settings, not code) | root `CLAUDE.md:453-455`, DEPLOY.md 652-655 | Tags from before design 19 | REMOVE the docs. The variables themselves are the user's to delete (ASK-5) | none | - |

## 7. Docs (living docs only; design notes, reviews, `plan-archive.md` and emulator run records untouched)

| # | File (size) | What goes | Verdict | Out |
|---|---|---|---|---|
| D-1 | `CLAUDE.md` (49.6 KB) | fork and monorepo dates (3-26, keep "old hashes resolve"), FCM clause (102-104), contract compatibility clauses (256-259, 310-313, the 17 capability names), the old versionCode rule (448-451), `HANDY_FCM_*` (453-455), music cutover and "server 0.21.0+" (458-461), "since 0.2x" (466, 470) | History out; compatibility out with K/E | about 3.5 KB |
| D-2 | `launcher/CLAUDE.md` (35.3 KB) | rename history (5), "this fork"/upstream comparisons (13, 25-29, 32, 34-39, 50, 54, 64), old versionCode rule and `same_code_update_v1` (64-66). KEEP the µLauncher lineage (7, MIT), the fence rule (48, plus the "lifted only for C1" line), the formula, downgrade and release key | History out; compatibility out with E | about 4.5 KB |
| D-3 | `server/CLAUDE.md` (77.8 KB) | fork and prototype history (5-13, 21-23, 29-31, 35-36), `battery_exempt` (78-79), branch-era migration hazards (212-225), the struck `device_push` row (322), capability and legacy schedule mentions (337, 342). The migration "follows" chain (229-294, 10.6 KB): strip the branch-renumber history, keep each file's order facts. KEEP the skipped 0058-0060 rule, "a migration file is frozen", and the next free number | History out | 5-8 KB |
| D-4 | `server/DEPLOY.md` (82 KB) | old-install sections (454-473), the rename rollout (615-631), "Removing FCM" (633-659; move the `SSE_KEEPALIVE_SECS` paragraph first), upgrade notes for 0.23, 0.24 and the parent-app rename (792-843), version headings. **Fix** 1001-1010 ("UnifiedPush distributor removed", wrong since design 108). KEEP release signing and rollback (479-538) | History out | about 11 KB |
| D-5 | `docs/notes/server/status.md` (8.8 KB) | the whole file (August history behind a "gone" banner). Check that no live fact is left in it first | REMOVE (file) | 8.8 KB |
| D-6 | `docs/notes/launcher/gotchas-and-history.md` (50.5 KB) | 66-99 "Current status (v0.15.0)", labelled historical (13.7 KB); 58-61 removed monitoring. Rewrite 19-46: keep the live gotchas (VPN tile, WifiManager needs Location), drop the removal story | History out, gotchas kept | 15-25 KB |
| D-7 | `docs/notes/launcher/dns-and-vpn.md` (39.5 KB) | 5-34 "Architecture change in progress (2026-08-07)": rewrite as today's architecture; 140, 199, 207 | Rewrite | 4-8 KB |
| D-8 | `docs/notes/server/play-and-updates.md`, `launcher/push-and-play.md`, `server/pwa.md` 55-61, `launcher/hardening.md:38`, `server/retention-and-crashes.md` 99-110, `server/architecture.md:3,11`, `emulator.md` §6b (1825-1842) | FCM and removed-monitoring history (keep the SSE keepalive and stream facts, and the "no Firebase on the release classpath" rule) | History out | about 6 KB |
| D-9 | Every note naming a capability or an "older server/launcher" path: `server/{calendar,calls-and-contacts,connectivity,dns,hardening-and-pins,launcher-ui,logins,music,notifications,pwa,time-rules,unified-push,zones,architecture}.md`; `launcher/{app-permissions,calls,dagen-and-calendar,home-and-photos,kid-settings,kiosk-escapes-and-updates,lock-and-glance,logins,music-bridge,security,time-rules,ui-polish,unified-push,zones-and-power}.md`; `music/{storytel,stack-by-package}.md`; `emulator.md` 950-998 (StorytelLegacy) | The sentences that go with the code they describe. KEEP: "legacy" meaning Android's or the browser's, DB data notes (legacy PIN before 0085, rules kept from before 66), and fail-closed rules | Out with the code commit | about 15-20 KB |
| D-10 | `music/CLAUDE.md:161-163` | the `mobile_data` fallback | With M-1 | 0.15 KB |
| D-11 | Stale (not compatibility, found on the way): `server/CLAUDE.md:161` ("no Android screen lock", before design 59); PLAN Goals 17-18 | Fix in the docs commit | Fix | small |

Docs total: about 45-55 KB certain (history plus compatibility), and about 70-85 KB with the rewrites (D-3's chain, D-6, D-7).
In-scope docs total 2.6 MB. Notes over 50 KB, which C1 does not split (audit R6): `server/pwa.md` 276, `launcher/calls.md` 132,
`launcher/lock-and-glance.md` 93, `server/notifications.md` 86, `launcher/ui-polish.md` 81, `server/music.md` 67,
`server/connectivity.md` 61, `server/calls-and-contacts.md` 59, `server/play-and-updates.md` 58,
`launcher/home-and-photos.md` 57, `launcher/gotchas-and-history.md` 51 (below 50 after D-6).

---

## Counts per verdict (table rows)

| Verdict | Rows | Which |
|---|---|---|
| REMOVE | 78 | Code: A-1…A-20 (20); B-1…B-9, B-11, B-12, B-14…B-18 (16); L-1…L-10, L-12…L-16 (15); M-1…M-4 (4); E-1…E-5 (5). Contracts: K-1…K-6, K-8 (7). Docs: D-1…D-11 (11) |
| KEEP | 13 | B-10, B-13, B-19, B-20, B-21; L-11, L-17, L-18, L-19, L-20; M-5; K-7; E-6 (the fence, signing, today's versionCode rule and the QR path) |
| Wording only | 3 | B-22, L-22, M-6 (the code stays) |
| C3 | 2 | A-21 (`pin_lock_v1`, `android_keyguard_v1`) and L-21 (the own lock), plus `L/lock/PinLockStore.kt`'s migrate read inside L-14 |
| ASK | 5 | Listed below. B-16 and the final step of A-1 wait on them; the rest of the build does not |

Under these rows, the grep counts: about 255 "older server/launcher" mentions in code (137 server, 118 launcher).
About 160 are real branches covered above; the rest are comment-only (B-22, L-22).

## Proposed build order (each commit green on its own; Sonnet for the mechanical ones, Opus for 6, 9 and 10)

1. **Docs, pure history only** (D-1…D-8 history parts, D-11). No code. Zero risk.
2. **Music app, music-only**: M-2, M-3 (Storytel purge, debug drop), M-6 wording. The music tests.
3. **Launcher on-device migrations, launcher-only**: L-1, L-12, L-13 (keep the first-load init), L-14, L-15, L-16.
   Fix `AirplaneRouteTest`'s anchor. The launcher unit tests.
4. **Launcher older-server reads with no contract row**: L-3, L-8, L-9, L-10. Launcher-only.
5. **Server older-page and older-session paths**: B-12, B-14, B-15, B-18, B-11, B-17 (deploy scripts). Server-only;
   every form keeps "no jump to top".
6. **Contract: time rules**: B-1 + L-2 + K (`policy_json_keys_snapshot`, `PolicyResponseCompatTest`); A-5 with it.
   Both sides.
7. **Contract: SMS and the glance**: B-2, B-3, B-4 + L-5, L-6 + K-2 (+ `L-11`'s `managed` read). Both sides. Medium
   size; the fixture churn of `CallPolicy(...)`.
8. **Contract: Mobilnett**: B-5 + L-4 + K-3. Both sides.
9. **Contract: time requests**: L-7 + K-4. Both sides (the server only compares the copies).
10. **Three-sided: music `mobile_data`**: B-6, B-7 + M-1 + K-6 + the launcher's MusicPlan. M-4's AIDL `reserved()`
    swap in the same or the next commit (both AIDL copies byte-identical).
11. **versionCode legacy**: E-1, E-2, E-3, E-4, E-5 + K-5 + A-19 (`install_now_v1`, `same_code_update_v1`,
    `TooOld`). Both sides and the script.
12. **Capability gates, server side, area by area** (one commit each, server-only, the claims still sent):
    12a calls/SMS (A-4, A-6); 12b calendar (A-8); 12c mobile, kiosk and DNS (A-9, A-10, A-11); 12d zones, apps,
    logos, PIN events, relock (A-12, A-13, A-14); 12e glance, weather, lock-now (A-15); 12f airplane, shortcuts,
    channels, rule pause, answers, logins, TLS, UP, music (A-16, A-17, A-18); 12g command receipts (A-7). Plus
    **A-3, the retention keeper, before or with the last of these**.
13. **The claims go**: A-1, A-2's leftovers, A-20 + K-1 in all 16 contract files. Both sides. The launcher sends
    `capabilities: []`. The server's `has_capability` helpers are deleted. **Needs ASK-1.**
14. **Config**: B-16 after ASK-2.
15. **Comment sweep**: B-22, L-22, M-5/M-6 wording (mechanical), and D-9's compatibility sentences not taken with
    their code commits. Then the docs rewrites D-6, D-7 and the D-3 chain.
16. **Ship together**: launcher and music RCs installed on the emulators first, then the server tag (Watchtower). An
    emulator check of a fresh QR enrolment plus one sync and one status report: the policy decodes, every PWA page
    draws without "Oppdater Vibb One", and an Install Now offers the next RC.

The PLAN entry is marked in the merge commit as each step lands (the queue upkeep rule).

## Size

- **Code**: about 50 call sites and about 160 real branches. Roughly −2.5k to −3.5k lines on the server (code and
  tests), −2.5k to −3k on the launcher (287 of them the prefs chain), about −150 on the music app.
- **Contracts**: 21 shared files touched (16 for capabilities, plus `sms_contract`, `mobile_network`, `time_request`,
  `launcher_tag_codes` and `bridge_contract`), each on both sides.
- **Docs**: −45 to −85 KB.
- **Effort**: about 16-20 commits, roughly two nights of agent work. Steps 7, 10, 12 and 13 carry the risk; the rest
  are mechanical.
- **No new migration and no edited migration.**

## ASK - the real judgment calls

1. **Capabilities: do all of them go?** Recommended: remove every claim and every gate (A-1…A-20, K-1). Keep the
   empty `capabilities` field in the status report and the `capabilities_json` column as the mechanism for the
   first feature after v0.40.0, since the Jelly Star then carries a real build. Two visible effects:
   - A phone that hasn't reported yet shows every row and switch, with no "Oppdater Vibb One først" anywhere.
   - `server_tls` and `unified_push` are always in the policy.
   The alternative, deleting the field and the helpers altogether, means rebuilding the mechanism later.
2. **The server's `.env`**: does it still set `LAUNCHER_ADMIN_COMPONENT=com.kidslauncher.mdm/...`, the old
   `SERVER_RELEASE_REPO` or the old `LAUNCHER_APK_URL`? The mapping (B-16) goes only if not. Otherwise the Jelly
   Star's provisioning QR breaks.
3. **The server's startup data conversions** (B-19: `migrate_legacy`, `migrate_crossing`/`normalise_all`, the
   easy-PIN sweep, the catalog backfills): recommended KEEP this round. They act on the real DB, like migrations,
   and matter if an old backup is ever restored. Remove them only if the user says no backup from before early
   October will ever be restored.
4. **Old PWA bookmarks** (B-15): recommended REMOVE (the PWA's start URL is `/`). Keep them only if the user has
   bookmarked `/dns?device=`, `/schedules?device=` or `/devices/{id}/weather`.
5. **The `HANDY_FCM_*` repository variables**: the user deletes them in GitHub's settings. The docs lose the
   sentence either way.

Decided here without an ASK (reasons in the rows):
- Abroad null policy keeps "touch nothing" (L-4).
- The rollback guards stay (L-11).
- `sms_groups` absent becomes false (L-10).
- The D2 DTO fields go (L-6).
- The AIDL slot becomes `reserved()` (M-4).
- The rollback-only column writes go (B-3).
- Old DB rows stay (B-20).
- The update fence stays whole (E-6).
