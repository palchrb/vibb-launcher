# contracts/

Files that two or three builds read, kept once (design 133). The server reads them through
`crate::contracts::contract!("<root>/<file>")` (compiled in) or `contracts::path("<root>/<file>")` (at run time); the
launcher and the music app read them as test resources by their bare names (`getResource("phone_vectors.json")`),
the AIDL and the DoH hosts as main sources.

## Layout

- `core/` - read by the server and the launcher. Flat, the files' own names.
- `integrations/<key>/` - read by the server and the launcher, one integration's (design 110's boundary):
  `integrations/<key>/contract.json`.
- `music/` - everything the music app reads; the server and the launcher may read it too.
- `shipped/` - compiled into a release build, the only root that is: `bridge-aidl/` (both apps' AIDL source set) and
  `launcher-assets/` (the launcher's assets). Everything outside `shipped/` is test data only.

A new contract: a file under the root of its readers, a row below (the server's `contracts::tests` fails on a file
without a row and on a row without a file), and both sides' tests read it in place. No copy goes into a module's own
`src/test/resources/`: the shadow tests (the launcher's `BuildChainTest`, the music app's `ContractFilesTest`) fail on
one.

## Build from the monorepo

The server, the launcher and the music app read `../contracts/` from their own folders, and the server binary compiles
in `shipped/launcher-assets/dns/doh_hosts.txt`. Every build (CI, `scripts/dev-rebuild.sh`, the release workflows) runs
from a whole checkout of the repo; a copy of `server/`, `launcher/` or `music/` alone does not build, and a future
Dockerfile that compiles must use the repo root as its context.

Prose written before 2026-11-25 names `server/testdata/<file>` (and the apps' copies); that is now
`contracts/<root>/<file>`.

## Files

| File | Design | Written by | Read by |
|---|---|---|---|
| `core/airplane_mode.json` | 98 | by hand | server `tests::airplane_mode`; launcher `AirplaneModeTest` |
| `core/app_access.json` | 127 step 2 | by hand | server `app_permissions::tests`; launcher `AppAccessContractTest` |
| `core/app_icons.json` | 14 | by hand; `scripts/material-symbols.sh` generates `app_icons.rs` and the launcher's table from it | server `app_display::tests`; launcher `AppGlyphsTest` |
| `core/app_logos.json` | 87, 87b | by hand | server `tests::app_logos`; launcher `AppLogosTest` |
| `core/blocked_numbers.json` | blocked numbers (2026-11) | by hand | server `blocked_numbers::tests`, `tests::blocked_numbers`; launcher `BlockedNumbersTest` |
| `core/calendar_feeds.json` | 72 §3 | `CALENDAR_FEEDS_CONTRACT_WRITE=1` (`calendar_feeds::tests::the_contract`) | server `calendar_feeds::tests`; launcher `CalendarFeedsTest` |
| `core/calendar_kid_events.json` | 72b §8 | `CALENDAR_KID_EVENTS_CONTRACT_WRITE=1` (`calendar_kid_events::tests::the_contract`) | server `calendar_kid_events::tests`, `tests::intake_limits`; launcher `KidCaldavTest` |
| `core/calendar_status_report.json` | 69 §4.2 | by hand | server `tests::calendar`; launcher `CalendarReportTest` |
| `core/calendar_title_rules.json` | 69 §3.4 | by hand | server `calendar::tests`; launcher `TitleRulesTest` |
| `core/clock_skew.json` | the phone's clock (`TrustedClock`) | by hand | server `tests::clock_skew`; launcher `ClockSkewContractTest` |
| `core/contact_request.json` | 77 §14 | by hand | server `tests::contact_requests`, `handlers::contact_requests::tests`; launcher `ContactRequestsTest` |
| `core/device_key.json` | 129 §7.1, 129b | `scripts/device-key-vectors.py --write` (`--check` in `server-ci.yml`) | server `tests::device_key`, `tests::enrol_key`, `enrol_key::tests`; launcher `DeviceKeyContractTest` |
| `core/dns_upstream_status.json` | 36 §4.2 | by hand | server `tests::custom_dns`; launcher `DnsUpstreamStatusTest` |
| `core/emergency_numbers.json` | 73 §4 | by hand | server `emergency::tests`; launcher `EmergencyMenuTest` |
| `core/install_failure.json` | upstream review 2026-11-20 | by hand | server `install_failure::tests`, `tests::tracked_apps`; launcher `InstallFailureTest` |
| `core/installed_apps_report.json` | app updates (2026-10-25), `other_signer` (2026-11-24) | by hand | server `tests::app_updates`, `tests::music`; launcher `SyncScheduleTest` |
| `core/launcher_tag_codes.json` | release tags (`scripts/release-version.sh`) | by hand, a row per tag | server `install_now::tests`, `device_pages::tests`, `launcher_check::tests`; launcher `SelfUpdatePlanTest`; `scripts/release-version.sh --selftest`, through which `launcher.yml`'s and `music.yml`'s changes jobs and release builds read it |
| `core/leave_tailnet.json` | security review S-L10 | by hand | server `tests::leave_tailnet`; launcher `SecurityLowsBTest` |
| `core/lock_now.json` | 93, 123 | `LOCK_NOW_CONTRACT_WRITE=1` (`lock_now::tests::the_contract`) | server `lock_now::tests`; launcher `LockNowTest` |
| `core/login_windows.json` | 102 | by hand | server `tests::logins`; launcher `LoginWindowsTest` |
| `core/mobile_network.json` | 94, 95 rev. 3, 97, 116 | by hand | server `tests::mobile_network`; launcher `MobileNetworkTest` |
| `core/notification_channels.json` | 107 | by hand | server `notification_channels::tests`, `tests::notification_channels`; launcher `NotificationChannelsTest` |
| `core/notification_listeners.json` | Android Auto (2026-11) | by hand | server `notification_listeners::tests`, `tests::notification_listeners`; launcher `SystemReadersTest` |
| `core/phone_vectors.json` | 02 §3.4 | by hand | server `phone::tests`; launcher `PhoneNumbersTest` |
| `core/power_report.json` | 38 §2 | by hand | server `tests::power`; launcher `PowerEventsTest` |
| `core/restrictions_pause.json` | pause-end-report M1, 114 §6.1 | by hand | server `models::paused_at_tests`; launcher `RestrictionsPauseTest` |
| `core/rule_pause.json` | 98 §4.4 | by hand | server `tests::rule_pause`; launcher `RulePauseTest` |
| `core/server_tls_report.json` | 76 | by hand | server `tests::server_tls`; launcher `ServerPinsTest` |
| `core/server_url_vectors.json` | security review S-M1 | by hand | server `config::tests`; launcher `ServerTransportTest` |
| `core/setup_steps.json` | 114 | by hand | server `setup_status::tests`, `tests::setup_status`, `integrations::map_links::tests`; launcher `SetupStepsTest` |
| `core/shortcuts.json` | 99 | by hand | server `tests::shortcuts`; launcher `ShortcutsTest` |
| `core/sms_contract.json` | 77 §7, 80, 82, 83 | by hand | server `sms::tests`, `tests::sms`; launcher `SmsContractTest` |
| `core/status_heartbeat.json` | 95 §3.5 | by hand | server `tests::status_heartbeat`; launcher `StatusReportsTest` |
| `core/time_request.json` | r13 §2.5, 100 §14.5 | by hand | server `tests::time_request`; launcher `TimeRequestsTest` |
| `core/time_status_report.json` | 34 P13 | by hand | server `tests::devices_list`; launcher `TimeStateZoneTest` |
| `core/unified_push.json` | 108, 108b, 115 | by hand | server `tests::unified_push`; launcher `UnifiedPushContractTest` |
| `core/weather.json` | 75 §7.1 | `WEATHER_CONTRACT_WRITE=1` (`weather::tests`) | server `weather::tests`; launcher `WeatherContractTest` |
| `core/zone_status_report.json` | 64 step 2, 131 | by hand | server `tests::zone_watch`; launcher `ZoneReportTest` |
| `integrations/map_links/contract.json` | 110 | by hand | server `integrations::map_links::tests`; launcher `MapLinksContractTest` |
| `integrations/wifi_lookup/contract.json` | 125 step 2, 125b | by hand | server `integrations::wifi_lookup::tests`; launcher `WifiLookupContractTest` |
| `music/bridge_contract.json` | 21 §2 | by hand | launcher `MusicPlanTest`; music `BridgeTest` |
| `music/music_icons.json` | 21 | by hand; `scripts/material-symbols.sh` generates `music_icons.rs` and `MusicGlyphs.kt` from it | server `tests::music`; music `SourceScanTest` |
| `music/music_library.json` | 21 §1.3 | `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` (`tests::music::music_library_snapshot`) | server `tests::music`; launcher `MusicPlanTest`; music `ContractFilesTest` and others; `scripts/fake-spotify.py` |
| `music/music_listing.json` | 21b §4.2 | `MUSIC_LISTING_SNAPSHOT_WRITE=1` (`tests::music_sweep::music_listing_snapshot`) | server `tests::music_sweep`; launcher `MusicPlanTest`; music `ContractFilesTest` |
| `music/music_state.json` | 21 step 3 | by hand | server `music::tests`; launcher `MusicPlanTest`; music `BridgeTest` |
| `music/spotify_hosts.json` | 26c §6.2 | by hand (provisional until the DNS capture) | server `spotify::tests`; launcher `SpotifyHostsTest`; music `SpotifyHostsTest` |
| `music/storytel_token_answers.json` | 58 §2.2 | by hand | server `tests::music_storytel`; launcher `StorytelTokenCourierTest`; music `StorytelBridgeTokenTest`; `scripts/fake-storytel.py` |
| `shipped/launcher-assets/dns/doh_hosts.txt` | 67 §2.2 | by hand | launcher: the asset `dns/doh_hosts.txt` (`DnsFilterEngine`, a release build fails without it), `DnsFilterEngineTest`; server: compiled in (`dns_server::is_builtin_doh_host`), `dns_server::tests` |
