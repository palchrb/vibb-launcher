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
