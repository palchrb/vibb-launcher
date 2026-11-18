# Design QA: 116 (failures made visible, consolidation C2) and the dependency register

Read-only QA of `docs/design/116-failures-visible.md` and `docs/plan/dependency-register.md` at 2edba8049 (branch
`design-116-c2`), against the code in that worktree (main at 226468b0c). Paths: `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`, `T/` = `server/templates/`. Ranked H (blocks
the build or breaks a settled rule), M (wrong or incomplete; fix in the note before that step is built), L (tidy).

Counts: **3 H, 11 M, 12 L.**

What checked out (no finding):
- **The inventory's call sites and stores.** `fetch_releases` / `github_problem` (`S/handlers/tracked_apps.rs:226-268`;
  404 is `RepoNotFound`, every other status `GithubError`), `AppSyncs` / `SyncStatus.last_error` in memory
  (`:1128-1148`), the hourly loop without backoff (`run_scheduled_tracked_app_sync`, `:2639`); F-Droid's problems
  (`S/fdroid.rs`); `weather_cells.last_error` / `failures` with 403 = 6 h and quick retries (`S/weather_cache.rs:465-530`,
  `:551-570`); CalDAV's `login_refused` / `host_changed` / `key_lost` stop (`S/caldav.rs:948-1001`); the kid's writes
  (`S/calendar_kid_events.rs:2499`, `device_kid_events.error`, `write_backoff_until`); `calendar_sources.error`,
  `failures`, `ok_at` (migration 0105); `music_listings.failing_since` / `failures` (`S/music_sweep.rs:4219-4300`;
  backoff 15 min x4 up to the interval, default 6 h); `music_storytel.login_error`; `music_spotify_accounts.refresh_error`,
  `needs_login`; `CompiledList.last_error` in memory and the 5 min / 1 h loop (`S/dns_engine.rs:28-41`,
  `S/handlers/dns_filter.rs:1344-1358`). The mapping tables exist with the columns named (`device_tracked_apps`,
  `device_calendar_feeds`, `calendar_sources.account_id`, `device_music_entries`, `device_policy.music_storytel`,
  `music_spotify_account`, `lock_weather`, `vpn_filter_enabled`).
- **The targets** exist: `/apps` with `apps.list.update_failed`, `/devices/{id}/lock` with `#weather`,
  `/devices/{id}/calendar`, `/music`, `/music/settings` with `#storytel`, `/devices/{id}/music` with `#music-spotify`,
  `/dns`, `/devices/{id}/filter`, `/backups`.
- **Attention's shape.** `Kind` is `Copy + Eq`, `items()` adds kinds in display order (alarms first by placement),
  `facts_of` is pure over `Sources`, the Devices list counts `items(..).len()` per phone (`S/handlers/devices.rs:165`)
  and already has the family push row (`T/devices_list.html:38-42`) to put the backup row beside.
- **The VPN facts.** `retriesFailedEstablish` is sink-only (`L/server/MobileNetwork.kt:230`), `switchTo` /
  `reshapeRetry` (`L/server/KidVpnService.kt:196-215`), `onRevoke` stops until the next `apply()` (`:576-588`), the
  read loop's chain is the main `Handler` (`:466-494`, `VpnTunnelState`), the launcher is outside its own VPN in every
  shape (`addDisallowedApplication`, and the always-on owner is exempt from lockdown with the empty allow list), the
  sink answers DNS `REFUSED` (so `UP` for `SINK` is right), and every lift (`RestrictionsPause`, `OfflineOverride`,
  design 114's setup hold, which is a pause) clears `VpnInputs.filterOn` before `apply()` writes the shape, so `OFF`
  covers every parent choice on the phone's side. The `BackstopAlarm` pattern (`L/push/BackstopAlarm.kt`, request code 7)
  is the right one to copy.
- **The contract wiring.** `mobile_network.json` is byte-identical in both directories and already in both
  workflows' path filters (`server-ci.yml:76`, `launcher.yml`); no new file, so no CI change.
- **The backup path.** `perform_backup` (`S/handlers/backups.rs:285`) zips a `snapshot` stripped of sessions, push
  subscriptions and the Tailscale key; `apply_restore_request_capped` (`S/startup.rs:127`) has the 1 GiB cap and the
  SQLite header check, and `pre-migrate-*.db` files are not `backup-*.zip`, so "the newest backup" can't pick one.
- **Design 114 collisions.** 114's server branch uses migration 0157 and does not touch `attention.rs` or
  `devices_list.html` yet; its plan adds `Kind::Setup`, `Kind::NotDeviceOwner` and removes `MobileDataOff` and others.
  116's additions sit beside them; building after 114 merges (as the note says) leaves only textual merges in
  `REPORT_KEYS`, `StatusReportRequest`, `intake_limits` and the i18n files.
- The dependency register's spot checks hold (pcap4j 1.8.2, dnsjava 3.6.5, the Spotify SDK unpinned from
  `sdk.scdn.co`, `PushOff`, `SmsHeld`, `MmscHost`, `WifiSigninWide`, `Calls(MessagesHidden)`, `music_sonos_rooms_json`,
  `push::Health`), except as M11 says.

## H

### H1. The backup check's `version` rule raises "Backup can't be read" on the first deploy and after most upgrades

§4: "`MAX(version)` in `_sqlx_migrations` must equal this binary's newest migration", and "once at startup for the
newest backup if it was never checked". The first start of the C2 release itself (which adds 0158) checks a newest
backup made by the old binary: `MAX(version)` is 0156 or 0157, so the row is raised on every server at rollout. The
same happens at any start after an upgrade that adds a migration whenever the newest backup wasn't checked yet, and
for an uploaded backup from an older server. An older backup restores fine (`apply_restore_request`, then the
migrations run), so this is a false alarm, an orange row on the Devices list for a backup that is whole.

**Fix:** the check is "restorable by this binary": every `_sqlx_migrations` row has `success = 1`, its version is one
the binary's `sqlx::migrate!()` knows and its `checksum` equals the binary's; none is newer. Older is fine. `version`
then means "made by a newer or a different build". Add the case to `backups::tests` (an older backup passes, one with a
changed checksum or a newer version fails).

### H2. The kid's-calendar write stops are classed `parent` but are never tried again, so they never raise

§2.1 classes `write_refused` and `full` as **parent** ("something to fix that is still retried"), and §2.2 rule 3 needs
`failures >= 2`. But a 507 (`full`), a write's 403 (`write_refused`) and `no_etag` are `Step::StopDevice`
(`S/calendar_kid_events.rs:1751`, `:2134`, `:2293-2294`), and the next pass skips the device while its set holds one of
them (`:2671-2676`: `continue`). One failure is recorded, never a second: the row the design exists for never shows.
(The "calendar not writable" path at `:2650-2660` does repeat each pass, so it would raise - by luck.)

**Fix:** class every `StopDevice` code (`write_refused`, `full`, `no_etag`, `not_listed`, and the 413's `too_big` at
`:1148`) as **stop**, recorded when the stop is set; the parent's "retry" (`RETRIED_STOPS`, `:2885`) clears it with the
next good write. Name the full code list in §2.1 (see M7).

### H3. `since_ms` survives a shutdown and a process death, and mixes the phone's clock with the server's

§3.1 keeps `since_ms` (wall clock) and the state in `LauncherPreferences.mdm()` "so a process restart doesn't reset
it", written "by `KidVpnService` on every change of state"; §3.3 raises when `reported_at - since_ms >= 600 000`.
- A phone switched off at night (or rebooted for an update, or the launcher killed by its own self-update) destroys the
  service; under §3.1's own definition "no service running" is `DOWN`, so the stored state becomes `DOWN` since the
  shutdown. After the morning unlock, the first report can go before `establish()` (both start from
  `Application.onCreate`): it says `down` since last night, the server sees 9 hours, raises the red row and the
  **urgent push** - and the next report clears it. A false urgent push per power-off, the opposite of R1's aim.
- `reported_at` is the server's clock and `since_ms` the phone's: a phone ahead by more than 10 minutes never raises
  (§3.3 clamps a future `since_ms` to the report's instant), one behind raises at once.

**Fix:** a down period never spans a boot or a process start: keep `since` as elapsed realtime plus the boot count
(the setup hold's `timedWindowActive` pattern, design 114 r3), and start a new period at boot and at a fresh process
only once the service has tried and failed (not at `onDestroy`/shutdown, not before the first `establish()` of this
process has had its chance - e.g. a 60 s grace from process start). Send `down_ms` (measured on the phone at report
time) beside or instead of `since_ms`; the server raises on `down_ms >= report_after_ms` and keys the event by the
period (boot count + start). The contract's `raise` rows become `{state, down_ms} -> row`, and add rows for "a reboot
starts a new period".

## M

### M1. The reported state must be read live, not only from what was stored at the last change

§3.1 has the state written on changes (by the service and by `apply()`). A service that dies without `onDestroy`
(process killed, an exception in `onCreate`) or never starts leaves the stored value `up`, and `tunnelShape` keeps the
dead tunnel's shape on purpose (`L/server/KidVpnService.kt:654-658`). The report would then say `up` while nothing
filters - the exact S-M2 gap.

**Fix:** `statusReport` computes `vpnFilterState(filterOn, tunnelUp, shape)` at report time from
`KidVpnService.serviceRunning`, the live instance's `state.tunnel != null` (add a companion accessor; not
`tunnelShape`) and `shape`; the stored value is only for the period's start (H3). Name this in §3.1.

### M2. The emulator gate's steps don't produce `down`

- Step 1, "deny, then reshape (toggle Wi-Fi)": with the filter on and no sink reason, Wi-Fi off and on keeps
  `wanted == shape == FILTER`, and `reshape()` then only rewrites always-on and returns (`KidVpnService.kt:298-302`):
  no `establish()`, the old tunnel stays, the report says `up`.
- Any path through `setAlwaysOnVpnPackage` (`writeAlwaysOn` when the package or lockdown changes,
  `MobileNetworkRuntime.kt:158-160`) may re-authorise `ACTIVATE_VPN` for the package, undoing the `appops` deny - check
  it on the image before relying on it.
- Step 3, "a failed switch from `PASS` to `FILTER`: lift a pause": during a pause `filterOn` is false, and `PASS` exists
  only while a sink reason is armed (`vpnShape`, `MobileNetwork.kt:174-182`); without one the shape is `NONE`, the
  service stops and always-on is cleared, and the pause's end writes always-on again (the re-grant above).

**Fix:** step 1: deny, then kill the tunnel without touching always-on (e.g. `am kill` / crash the launcher process so
the system's binding restarts the service and `onCreate`'s `establish()` fails), or switch shapes with Bare Vibb on
cellular. Step 3: set Mobildata "Bare Vibb" first (so the pause's shape is `PASS`), on Wi-Fi, then deny, then end the
pause. Each step reads `dumpsys connectivity` for the VPN network as well as the report.

### M3. `met`'s target page doesn't say what is wrong, and its phone mapping isn't named

§2.3 says "each page already says what the error is", but §1's own table says MET's errors are "shown today: nothing",
and `T/device_lock.html:248-297` (`#weather`) has only the switch, the zone and the needs-position note. "MET sperrer
værvarselet" (a 403: the server's `MET_CONTACT` / User-Agent, the owner's fix) would land on a page with no word about
it. And §2.3's "every key has a phone mapping" is not true of `met`'s `''` scope as written.

**Fix:** map `met` to phones with `device_policy.lock_weather = 1` (so a parent who turns weather off sees no row), and
add one orange line in `#weather` while the row is raised ("Været hentes ikke" / "MET sperrer værvarselet", with the
how - `MET_CONTACT` in the server's settings, DEPLOY.md - behind its (i)). Add it to §5's copy table and step 3's files.

### M4. Blocklist rows for feeds the parent turned off

`compile_blocklist` downloads **every** defined feed, whatever its global switch (`S/dns_engine.rs:20-41`, "regardless
of that feed's global enabled flag"). §1 maps a feed to "phones with the filter on", so a feed the parent switched off
that fails (a moved URL nobody uses) shows "1 blokkeringsliste oppdateres ikke" on every filtered phone - a choice
shown as a problem.

**Fix:** map `feed:<id>` to phones with `vpn_filter_enabled = 1` **and** the feed in force for that phone (the
global `dns_blocklists.enabled` unless the phone's `device_blocklist_overrides` row says otherwise - the same rule
`device_api::dns_blocklist` resolves). Add the case to step 3's `tests::attention`.

### M5. Vibb One's own updates have no mapping, and disabled apps keep theirs

The launcher's catalog row is never in `device_tracked_apps` (migration 0013's note, `is_launcher = 1`); it is "included
unconditionally". Mapping `app:<id>` only through `device_tracked_apps` drops a GitHub failure of Vibb One's own
release - the one app every phone needs (register D11 lists it). Conversely, the hourly sync reads only
`enabled = 1` apps, so a disabled app's last failure would stay raised until retention.

**Fix:** `app:<id>` maps to `device_tracked_apps` rows **plus** every enrolled phone when `tracked_apps.is_launcher = 1`,
and only for `tracked_apps.enabled = 1`. Test both in step 3.

### M6. CalDAV at account scope interleaves its calendars

`caldav::refresh_source` runs per calendar source (`calendar_feeds::refresh` calls it for each `kind = 'caldav'`
source). Recording all of them under `account:<id>` means one calendar removed on the server (404, `parent`) next to
two healthy ones resets the run every cycle: it never raises, and Device › Calendar does show it. It also triples
`failures` per cycle in an outage.

**Fix:** record CalDAV's stop codes (`login_refused`, `host_changed`, `key_lost`) at `account:<id>`, and every other
outcome at `source:<id>` under the key `caldav` (mapped through `device_calendar_feeds` like `calendar_link`). §2.1's
scope list and §1's table change accordingly.

### M7. `class_of` is incomplete, and local errors would read as the service's

§2.1 classes 11 of `SyncProblem`'s 24 variants (`S/handlers/tracked_apps.rs:1159-1210`). Unclassed: `Filter`,
`NoRelease`, `Download`, `TooBig`, `Unreadable`, `OtherApp`, `OtherKey` (a release signed by another key - the one
the parent most needs to see), `CantCheck`, `Taken`, `Server`, `Removed`, `FdroidUnknown`, `RepoError`, `IndexSigner`,
`OlderVersion`. `Server` is this server's own disk or database and `Removed` is a race; recorded under `github` they
would say "GitHub svarer ikke". The same gaps elsewhere: MET's `unreadable`, `cut`, `status_other`; CalDAV writes'
`not_listed`, `no_etag`, `too_big`; music's `http_401`/`403`.

**Fix:** put the full table in §2.1 as an exhaustive `match` (no `_` arm, so a new variant fails to compile): e.g.
`Download` transient; `TooBig`, `OtherApp`, `OtherKey`, `CantCheck`, `Taken`, `NoRelease`, `Filter`, `FdroidUnknown`,
`RepoError`, `IndexSigner` parent; `OlderVersion` not a failure (the sync keeps the cached release on purpose);
`Server` and `Removed` not recorded. Step 2's tests run every row.

### M8. The backup check: blocking, scratch files, disk space and the "which file" state

- §4 runs the check after `perform_backup` "both scheduled and Back up now". `PRAGMA integrity_check` on the whole
  database inside the "Back up now" request delays its answer; run it in a spawned task after the zip is written (the
  page shows "kontrollert" on the next load), never holding the backup's result.
- Two checks can run at once (two clicks, a scheduled run): the scratch file needs a unique name
  (`backup-check-<timestamp>-<rand>.db`), and a crash mid-check leaves a full copy of the family's database in `data/`:
  sweep `backup-check-*.db` at startup, as `remove_partial_downloads` does.
- A full disk makes the scratch write fail; that must not read as "Sikkerhetskopien kan ikke leses" (the zip may be
  whole). Record it as `failed` with its own log line, or not at all.
- "If it was never checked" and the failure line need the file's name: store it (scope `file:<name>`, or a column), so a
  restart doesn't re-check, and deleting the failing file - or a newer good backup - clears the row. Otherwise a parent
  who turns the schedule off keeps a stale failure until retention.

### M9. Step 4 doesn't stand alone, and steps 3 and 4 collide

§6 says "Step 4 and step 5 stand alone (step 4 can run in the second worktree)". Step 4 needs `vpn_filter_json` and
`push_filter_down`, which step 1's migration 0158 adds, and steps 3 and 4 both edit `S/attention.rs`
(`Kind`, `level`, `title`, `target`, `Facts`, `facts_of`) and both i18n files. Design 110 (map links) is also waiting
for a migration number (114 QA L2: "110 renumbers at its build").

**Fix:** order 1 -> (3 and 5 in one worktree) and 4 in the other, with 4's migration its own (0159, renumbered at merge),
or run 4 after 3. Say that the migration number is taken at build time, after 114 (0157) and whatever else merged.

### M10. Clear the server side of "a parent's choice is never down"

The phone's `OFF` covers the switch, the pause, the offline override and 114's hold. The server should hold the same
line for the minutes before the phone has the new policy: a parent who just switched the filter off, or started a
pause from the PWA, must not get "Nettfilteret er nede" and an urgent push from a report made under the old policy.

**Fix:** raise `FilterDown` (row and event) only while the server's `device_policy.vpn_filter_enabled = 1`, the last
report isn't `paused_at` and no PWA pause or lift is in force; add both cases to `tests::attention` and
`tests::notifications`.

### M11. The register misses two runtime dependencies and misstates two rows

- Missing: **OpenStreetMap's tiles** for the PWA's maps (`tile.openstreetmap.org` in the CSP, `S/admin_auth.rs:858`;
  kind S, T - the tile usage policy; fails soft; detected by nothing). And **the filter's public resolvers** on the
  phone (Cloudflare and Quad9 DoT and DoH, `L/server/PublicResolver.kt`, design 36/67; fails closed for names; detected
  by the `/dns` page's "provider down" line from `dns_upstream`).
- D16 "Detected by: Page (the filter page's DNS line, while `dns_upstream` is missing)": while `dns_upstream` is
  missing the `/dns` phone line shows nothing (`S/handlers/dns_filter.rs:389-420` only words an outage it is told
  of). Today it is "nothing". (S-M2's server half - "check whether anything reads a missing `dns_upstream` as down" -
  is answered by this: nothing does; say so in §3.3.)
- D11's "Used by" misses the server's own update check and the setup QR's launcher check (both GitHub); keep them out
  of the feed as the note's "Out of scope" says, but list them.

**Fix:** add the two rows (D25, D26) and correct D11 and D16.

## L

- **L1. Two retry chains.** With `retriesFailedEstablish` widened, a lost tunnel's `retryTunnel()` fails in `switchTo`,
  which posts `reshapeRetry`, while `retryTunnel` also schedules `retry` (`KidVpnService.kt:479-494`): two doubling
  chains both calling `startVpn`. Fix: one chain - `retryTunnel` leaves the reschedule to `switchTo` when it ran, or
  the reshape chain is skipped while `state.tunnel == null`.
- **L2. The Android table misses the existing Doze path.** `BackstopAlarm` already syncs every 30 min (15 with SSE
  down) in Doze, and each `apply()` calls `KidVpnService.start`; the new alarm shortens 30 to 5 minutes. "About once
  per 9 minutes" is the pre-S compat quota; at targetSdk 36 the while-idle quota is per hour. Say both; nothing changes
  in the plan.
- **L3. Duplicate rows.** Grouping by (key, problem) with titles that are the same "(both)" for `caldav_write`,
  `calendar_link`, `music_feed`, `blocklist` gives two identical rows when one scope is `Down` and another `Fix`, and
  `github` and `fdroid` `Fix` share `apps_fix`, so two "N apper får ikke oppdateringer" rows. Group by the title key
  and sum the counts; `key()` must stay unique per row.
- **L4. "Two in a row".** `failures` counts every failure since the last success, so transient, transient, parent is
  already `Fix`. Accept it and say so, or count parent-class failures in a run.
- **L5. On-demand keys.** `met`, `storytel` and `spotify` record only when called; a `Down` row stays until the next
  call. Fine while phones ask; note it.
- **L6. `met` at `''` with several cells.** One cell's success resets the run, so a failure of one cell only
  (`status_other`) never raises. Accepted by the note's "an outage of MET is MET's"; say so.
- **L7. Event keys.** Existing events split `events.<kind>.inbox.title` and `.push.title` (`events.foreign_access.*`,
  push "{phone}: ..."). Use `events.filter_down.inbox.title` "Nettfilteret er nede på {phone}" and `.push.title`
  "{phone}: nettfilteret er nede".
- **L8. The filter page's line when stale.** Add " · ved siste rapport" like `/dns`'s lines when the phone is Offline;
  consider the `/dns` phone line saying "Nede" too, so the two pages agree.
- **L9. Attention plumbing.** `read_from_report` is a negative list (`S/attention.rs:91-101`): `Integration` must be
  added to it. Place `FilterDown` among the alarms (after `ForeignAccess`; after 114 also mind `NotDeviceOwner`).
- **L10. Writes per call.** `ok()` on every `spotify::api_get` / Storytel call is a write per call; write when the row
  changes or at most once per few minutes.
- **L11. Launcher tests.** Make the period, the alarm's arm/cancel and the once-per-period sync request pure functions
  with rows (in `mobile_network.json`'s `vpn_filter`, or launcher-only), not only `states`.
- **L12. Size.** With H2, M5-M8 step 2 is the biggest step (ten sites, an exhaustive class table each, ten fake-driven
  tests); "about two nights" is optimistic - say three.

Copy (§5) against "Words in the UI": the titles are facts of six words or fewer, nothing shows while things work, the
quiet "kontrollert" is a quiet value, how-tos sit behind an (i), and a parent's choice raises nothing once M3, M4, M5
and M10 hold. No other change.

## Resolved in revision 2 (2026-11-18)

- H1: resolved. The check is "restorable by this binary": success, a known version, an equal checksum, none newer. An older backup passes (§4).
- H2: resolved. Every `StopDevice` code is class `stop`, recorded when the stop is set; the parent's retry clears it (§2.1).
- H3: resolved. The down period is measured on the phone (elapsed realtime + boot count). It never spans a boot and never starts in a process's first 60 s. The report sends `down_ms` and `period`; the server raises on `down_ms` (§3.1, §3.4, §3.5 `periods`).
- M1: resolved. The state is computed at report time from the live instance's `state.tunnel` and `shape`, never `tunnelShape` (§3.1).
- M2: resolved. The gate is rewritten: a check that the deny holds, a crash with the deny set, recovery in force-idle, Bare Vibb for PASS, a reboot row; each step reads `dumpsys connectivity` (§3.3).
- M3: resolved. `met` maps to phones with `lock_weather = 1`, and `#weather` gains an orange line with its (i) (§2.3, §6, step 3).
- M4: resolved. A feed maps to phones with the filter on and the feed in force (global switch or the phone's override), as the user also asked (§2.3).
- M5: resolved. Vibb One's catalog row maps to every enrolled phone; only `enabled = 1` apps map (§2.3).
- M6: resolved. CalDAV stops are recorded at `account:`, everything else at `source:` (§1, §2.1, §2.3).
- M7: resolved. There is an exhaustive class table per call site with no `_` arm; `Server`/`Removed` are not recorded, and `OlderVersion` records ok (§2.1).
- M8: resolved. The check runs in a spawned task under a unique scratch name, with a startup sweep; a full disk records nothing; rows are kept per file (`file:<name>`) (§4).
- M9: resolved. Worktree A runs 1→2→3→4 and worktree B runs 5b then 5; the migration number is taken at build time (§7).
- M10: resolved. `FilterDown` and its event are raised only while the server's `vpn_filter_enabled = 1`, the report isn't paused, and no known hold is in force (§3.4).
- M11: resolved. D25 (OSM tiles) and D26 (public resolvers) are added, and D11 and D16 are corrected in the register.
- L1: resolved. One retry chain (§3.1).
- L2: resolved. The backstop path and the while-idle quota are named (§3.1, §3.2).
- L3: resolved. Rows are grouped by title with counts summed; `key()` is unique per title (§2.3).
- L4: resolved. Accepted and said (§2.2).
- L5: resolved. On-demand keys are noted (§2.1).
- L6: resolved. The `met` cell interleave is accepted and said (§2.1).
- L7: resolved. The event uses the `inbox.title` and `push.title` keys (§6).
- L8: resolved. " · ved siste rapport" on the filter page; `/dns` says "nede" too (§3.4, §6).
- L9: resolved. `Integration` joins `read_from_report`'s negative list; `FilterDown` is placed among the alarms (§2.3, §3.4).
- L10: resolved. `ok()` writes only when failing or after 10 min (§2.1).
- L11: resolved. The period, alarm and sync decisions are pure, with contract rows (§3.1, §3.5).
- L12: resolved. The size is about three nights (§7).

Also changed in revision 2, beyond the findings: the user's part E (the backups folder as a host bind mount). It
needs `snapshot` to copy across filesystems (`EXDEV`), or the next pre-migration backup stops the start. It also needs
`check_data_writable` to cover `data/backups` (§5).
