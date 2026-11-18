# 116 - Failures made visible (consolidation C2, audit R1)

Status: design, 2026-11-18. Not QA'd. Built after design 114 merges (both touch `S/attention.rs`,
`T/devices_list.html` and the Devices list's handler; 114 owns `setup_steps`, the setup pages and Attention `Setup` -
this design adds beside them and changes none of them).

Inputs: the audit `docs/review/2026-11-17-maintenance-audit.md` (§1, §2 #7, §4 I1, §5 R1 and the register's format);
PLAN.md "Consolidation before new features", C2; the apps security review `docs/review/security-review-2026-11-17-apps.md`
S-M2 (folded in as part C); CLAUDE.md "Words in the UI". Read against `main` at 226468b0c.
Paths: `S/` = `server/src/`, `T/` = `server/templates/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.

The four parts:
- **A.** The dependency register, committed with this note: `docs/plan/dependency-register.md` (24 rows, each
  checked against the code; what wasn't checked is marked).
- **B.** One Attention kind, `Integration`, fed by each outside call's last success and last error.
- **C.** "Filter on, tunnel down" from the phone (S-M2): a retry that survives deep sleep, a `vpn_filter` report
  field, an Attention row, an event.
- **D.** A backup check after every backup.

Out of scope: moving the existing music kinds (`MusicStorytelRefused`, `MusicSpotify*`, `MusicFailing`) into the new
kind - that is the integration seam's work (audit I5); the server's own update check and the launcher check (they
already show on the page where they matter, Software Updates and Phone Setup); off-machine backup copies (the server
never acts on its host).

## 1. What the code has today (the facts the design rests on)

Every server integration already records something, but in a different place each, and four of them only in memory.
Nothing reaches Attention except Spotify's account rows.

| Key | Call site, cadence | Recorded today | Scope → phones | Shown today |
|---|---|---|---|---|
| `github` | `tracked_apps::fetch_releases`, hourly (`run_scheduled_tracked_app_sync`), no backoff | in memory, `AppSyncs` → `SyncStatus.last_error` (`SyncProblem`); lost at a restart | catalog app → `device_tracked_apps` | the Apps list's badge, the app's page |
| `fdroid` | `tracked_apps::fdroid_releases` → `fdroid::*`, hourly | the same in-memory `SyncFailure` | catalog app → `device_tracked_apps` | the same |
| `met` | `weather_cache::fill_forecast`, when a phone asks and `next_fetch_at` has passed; quick retries and a backoff | `weather_cells.last_error`, `failures` | cell (no stored cell → phone map) | nothing |
| `caldav` | `caldav::refresh_source`, every 30 min with backoff; a refused password stops the account | `caldav_accounts.state`, `calendar_sources.error` | account → phones with one of its calendars (`device_calendar_feeds`) | Device › Calendar |
| `caldav_write` | `calendar_kid_events::work`, on a phone's post or every 60 s | `device_kid_events.error`, the account's `write_backoff_until` | phone | Device › Calendar |
| `calendar_link` | `calendar_feeds::refresh`, every 30 min with backoff | `calendar_sources.error`, `failures`, `ok_at` | source → `device_calendar_feeds` | Device › Calendar |
| `music_feed` | `music_sweep::check_entry` (NRK, RSS), every 1-24 h with backoff | `music_listings.error`, `failing_since`, `failures` | entry → `device_music_entries` | the Music tab's entry lines |
| `storytel` | `storytel::login`, `shelf`, `phone_jwt`; cooldown and an in-memory backoff | `music_storytel.login_error` | family → `device_policy.music_storytel = 1` | Music › Settings; Att `MusicStorytelRefused` from the phone's report |
| `spotify` | `spotify::access_token`, `api_get`; a quota pause in memory | `music_spotify_accounts.refresh_error`, `needs_login` | account → `device_policy.music_spotify_account` | the Spotify page; Att `MusicSpotify*` |
| `blocklist` | `dns_engine::compile_blocklist`, hourly, 5 min while one fails | in memory, `CompiledList.last_error` | feed → phones with the filter on | Settings › Web filter |

UP's gateway is not on the list: its outside party is inbound (homeservers post to us), and design 108c's
`AppPush*` rows are already raised from the server's own data. Web Push to parents has `push::Health` and the
Devices list's push row.

## 2. Part B: the `Integration` row

### 2.1 One store, fed at each call site

Migration **0158** adds:

```sql
CREATE TABLE integration_health (
    key TEXT NOT NULL,            -- github, fdroid, met, caldav, caldav_write, calendar_link, music_feed,
                                  -- storytel, spotify, blocklist, backup (part D)
    scope TEXT NOT NULL,          -- '' (family-wide), 'app:<id>', 'account:<id>', 'device:<id>', 'source:<id>',
                                  -- 'entry:<id>', 'feed:<id>'
    ok_at TEXT,                   -- the last success
    error_at TEXT,                -- the last failure
    error TEXT,                   -- its code, the call site's own (logged, never shown)
    class TEXT CHECK (class IN ('transient', 'parent', 'stop')),
    failing_since TEXT,           -- the first failure after the last success; NULL while it works
    failures INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (key, scope)
);
```

A new module `S/integration_health.rs` (the name avoids `unified_push::health` and `push::Health`):
- `ok(db, key, scope)`: `ok_at` = now, `failing_since` NULL, `failures` 0.
- `failed(db, key, scope, code, class)`: `error`, `error_at`, `class`; `failing_since` kept or set; `failures` + 1.
- One upsert each; a write error is logged and never fails the call it records.
- Each call site maps its own codes to a class, in one small `fn class_of(...)` beside its error type:
  - **transient**: network, timeout, 5xx, 408, 429, quota, `RepoUnreachable`, `GithubUnreachable`, `GithubError`.
  - **parent**: something to fix that is still retried: `RepoNotFound`, `NotInRepo`, `not_found`, `not_feed`,
    `not_calendar`, `http_404`/`410`, `insecure`, `private_address`, `write_refused`, `full`, MET's 403,
    `RepoKey`, `RepoSignature`, `RepoRollback`, `ChecksumMismatch`.
  - **stop**: the integration stopped itself until the parent acts: CalDAV's `login_refused`, `host_changed`,
    `key_lost`.
- Storytel and Spotify feed **transient failures and successes only**. Their parent-side problems already have rows
  (`MusicStorytelRefused`, `MusicSpotify*`), and a second row would say it twice. The seam round moves them.
- `music_feed` is NRK and RSS entries only. Storytel's and Spotify's listings record under their own keys.
- `met` records family-wide (`''`), not per cell: there is no stored cell → phone map, and an outage of MET is MET's.
- Retention: a row with neither `ok_at` nor `error_at` in 30 days is deleted (`retention.rs`). A scope that is gone
  (an app removed) is never read: the facts join only through live mapping rows.

### 2.2 When a failure becomes a row

A pure function, `raised(row, grace, now) -> Option<Problem>`, with `Problem::{Down, Fix}`:

1. The newest outcome is a success (`failing_since` NULL): no row. **The next success clears the row at once.**
2. The newest failure is `stop`: `Fix` at once (the integration has stopped; no retry will mend it).
3. The newest failure is `parent` and `failures >= 2`: `Fix`. Two in a row, so one odd 404 from a CDN says nothing.
4. Otherwise (transient, or a single parent-class failure): `Down` once `failures >= 3` **and** `now - failing_since`
   is at least the key's grace:

   | Key | Grace | Why |
   |---|---|---|
   | `github`, `fdroid`, `calendar_link`, `caldav`, `storytel`, `spotify`, `blocklist` | 12 h | hourly or half-hourly calls: a night's outage stays quiet; a dead day doesn't |
   | `met`, `caldav_write` | 6 h | the kid sees a missing weather line or a missing event the same day |
   | `music_feed` | 24 h | the sweep runs every 6 h by default; three failures take a day anyway |

No flapping: a row needs a sustained run, and any success ends the run. The cost is that an integration that fails
every other call never raises a row. This is accepted: its feature still works half the time, and the page keeps
showing the last error.

### 2.3 Where it shows

On the phone's Attention list (the summary and the Devices list's count), one row per key and problem, for the phones
the scope maps to (§1's table). Every key has a phone mapping, so no family-level row is needed. A GitHub outage
shows on each phone with a GitHub app; at one to five phones that is right.

- `attention::Kind::Integration(Service, Problem, usize)`: `Service` is a `Copy` enum of the ten keys, and the
  `usize` is the number of failing scopes behind the row. `key()` is `integration-<service>`.
- The row is orange (`Level::Action`) and comes after the music rows. `read_from_report()` is false (it is the
  server's own data), so it is never marked "at last report".
- `Facts.integrations: Vec<(Service, Problem, usize)>` is filled by `integration_health::for_device(db, device_id)`:
  one query joining `integration_health` to the mapping tables, then `raised` per row, grouped by (key, problem).
  The Devices list renders `facts` per phone already; this adds one read per phone.
- Targets (each page already says what the error is):

  | Service | Target |
  |---|---|
  | `github`, `fdroid` | `/apps` (the list's `update_failed` badge) |
  | `met` | `/devices/{id}/lock#weather` |
  | `caldav`, `caldav_write`, `calendar_link` | `/devices/{id}/calendar` |
  | `music_feed` | `/music` |
  | `storytel` | `/music/settings#storytel` |
  | `spotify` | `/devices/{id}/music#music-spotify` (the existing Spotify rows' target) |
  | `blocklist` | `/dns` |

### 2.4 No push

These rows are Attention only: no parent event, no inbox entry, no push. The reasons:
- Every integration fails soft. The core (policy, locks, calls, the catalog's signature checks) works without them,
  so nothing here is urgent.
- A row is a state, not something that happened. An event would need a second, "it works again" event to stay true.
  The row clears itself.
- An outage of a service the parent can't fix (GitHub, MET) would push once per phone and per day for nothing they
  can act on.

The Devices list's red count is where the parent already looks.

## 3. Part C: "filter on, tunnel down" (S-M2)

### 3.1 The phone

- **The state, pure** (`L/server/MobileNetwork.kt`): `vpnFilterState(filterOn, tunnelUp, shape): VpnFilterState`:
  - `OFF`: `!filterOn` (the parent's switch, or a lift or pause running; `VpnInputs.filterOn`).
  - `UP`: the tunnel is established in `FILTER` or `SINK` (the sink answers every DNS query `REFUSED`, so it filters).
  - `DOWN`: every other case. That covers `establish()` returning null or throwing, the read loop dead, the service
    stopped by `onRevoke`, no service running, and a tunnel left in `PASS` after a failed switch to `FILTER`. The last
    case has a tunnel but no filter.
- **The report**: `StatusReportRequest.vpnFilter = {state: "up"|"down"|"off", since_ms}`.
  - `since_ms` is the wall-clock instant the state began. It is kept in `LauncherPreferences.mdm()` (one long and the
    state), so a process restart doesn't reset it.
  - It is written by `KidVpnService` on every change of state, and by `apply()` when `filterOn` flips.
  - The field is not a heartbeat field: a change alters the report's hash, so a full report goes.
- **The retry** (S-M2):
  - `retriesFailedEstablish(wanted, established)` becomes `!established && wanted != NONE`, so every failed switch is
    retried, not only the sink's.
  - The main `Handler` chain stays as the fast path (5 s doubling to 5 min) while the phone is awake.
  - Each failure, and `onRevoke` while `filterOn`, also arms one `VpnRetryAlarm`:
    - `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, now + 5 min)`, the `BackstopAlarm` pattern with its own request
      code.
    - It fires in Doze, about once per 9 minutes at most.
    - Its receiver writes always-on for the stored shape (`MobileNetworkRuntime.writeAlwaysOn`) and calls
      `KidVpnService.start`. That is `applyVpnRestrictions`' path from the stored inputs, without a sync.
  - The alarm re-arms itself while the state is `DOWN` and is cancelled at `UP` or `OFF`.
- **Telling the server promptly**:
  - When `DOWN` has lasted `report_after_ms` (600 000, from the contract), the retry that sees it calls
    `SyncRunner.request(context, "vpn_filter")`, once per down period.
  - The first `UP` after a reported `DOWN` requests a sync too.
  - Neither request is affected by the outage itself: the launcher is outside its own VPN in every shape.

### 3.2 Android 14-17 (checked 2026-11-18)

The launcher targets API 36 (`app/build.gradle.kts`). The Android 17 "targeting 17" changes don't apply; its
all-apps changes do.

| Version | What matters here | Verdict |
|---|---|---|
| 14 (34) | A foreground service type is required: `specialUse` is declared. Exact alarms are denied by default to new installs, so the inexact `setAndAllowWhileIdle` needs no permission. A background FGS start is allowed for a Device Owner (the FGS docs' exemption list). Always-on via `setAlwaysOnVpnPackage` is a DO call since API 24, and the system keeps an always-on VPN bound (`BIND_AUTO_CREATE`), so a killed process comes back. | works as designed |
| 15 (35) | The `BOOT_COMPLETED` FGS ban covers dataSync, camera, mediaPlayback, phoneCall, mediaProjection and microphone, not `specialUse`. Nothing for VpnService or while-idle alarms. | no change |
| 16 (36) | The behaviour-change pages list JobScheduler quotas (not used here), ordered-broadcast priority (not used) and intent redirection hardening (an explicit `PendingIntent` to our own receiver isn't affected). Nothing for VpnService or AlarmManager. | no change |
| 17 (37) | All-apps changes: the app memory limiter can kill a process. The system's binding restarts the VPN service, and the alarm covers the gap where no service runs. Nothing for VpnService, AlarmManager or FGS. | no change; the memory limiter is one more way to reach `DOWN`, which this design covers |

**The emulator gate** runs on compat34 (the lowest) and the API 37 image (the highest), if it runs on the VM; otherwise
on handy36, with 37 named as owed. It needs the user's OK first.
1. Deny the VPN: `adb shell appops set me.vibb.launcher.debug ACTIVATE_VPN deny`, then reshape (toggle Wi-Fi).
   - The report says `down`.
   - After 10 minutes the server shows the row (and the event, per ASK 1).
2. Allow it again with `force-idle` on (`adb shell dumpsys deviceidle force-idle`).
   - The alarm brings the filter up within one Doze window.
   - The row clears with the next report.
3. A failed switch from `PASS` to `FILTER`: lift a pause while the VPN is denied. It reports `down`, not `up`.

### 3.3 The server

- **Intake**: `device_status.vpn_filter_json` (migration 0158), kept through `mobile_network::clean_vpn_filter`:
  - `state` must be one of the three words; an unknown one drops the field.
  - `since_ms` must be an integer; one in the future is taken as the report's own instant.
  - `REPORT_KEYS` gains `vpn_filter`, and `intake_limits` keeps its keys.
- **Attention `FilterDown`** (red, `Level::Alarm`: filtering is open):
  - Raised when the last report says `down` and `reported_at - since_ms >= report_after_ms`.
  - Read from the report, so it is marked "at last report" when the phone is offline.
  - Target: `/devices/{id}/filter`.
- **The phone's filter page** (`/devices/{id}/filter`): the same fact as one orange line at the top, with the
  time, and the how-to behind its (i).
- **The event `FilterDown`** (ASK 1):
  - Raised once per down period, keyed by `since_ms`, at the first report that raises the row.
  - In the inbox, urgent like `ForeignAccess`, with its own switch `push_filter_down` (on by default; migration 0158).
  - No event when it comes back up; the row going is enough.

### 3.4 The contract

The new rows join `server/testdata/mobile_network.json` (the VPN shapes' contract already). There is no new file, so
the CI path filters already cover it. New key `vpn_filter`:
- `report_after_ms`: 600000.
- `reports`: the exact `vpn_filter` of a report, for `up`, `down` and `off`.
- `states`: `{filter_on, tunnel_up, shape} -> state`, the PASS-after-a-failed-switch row included.
- `raise`: `{state, since_ms, reported_ms} -> row`.
- `clean`: the server's cleaning.

The tests on each side:
- The launcher's `MobileNetworkTest` runs `states`, encodes `reports` exactly, and checks its constant against
  `report_after_ms`.
- The server's `tests::mobile_network::the_contract` posts `reports`, runs `raise` and `clean`, and checks the stored
  value.
- Both compare the copies.

Root CLAUDE.md's "Mobilnett" paragraph gains one clause naming `vpn_filter`.

## 4. Part D: the backup check

The smallest proof that a backup restores is to open, after every backup, exactly what a restore would open, and
check it:
- `backups::check(data_dir, zip) -> Result<(), BackupProblem>` runs after `perform_backup`, both scheduled and
  "Back up now", and once at startup for the newest backup if it was never checked.
- It reads the zip's `kidphone.db`, under the restore's own 1 GiB cap. It writes it to a scratch file in `data/`
  (never `data/backups/`, the `snapshot` rule) and opens it read-only and immutable.
- Three checks:
  - `PRAGMA integrity_check` must answer `ok`.
  - `MAX(version)` in `_sqlx_migrations` must equal this binary's newest migration.
  - `SELECT COUNT(*) FROM devices` must run.
- The scratch file is deleted after the check, also when it fails.
- Codes: `failed` (the scheduled backup itself failed; today that is only logged), `no_db`, `unreadable`,
  `integrity`, `version`.
- The result is recorded as `integration_health('backup', '')`, class `stop`. One bad backup is never transient: it
  raises at once and clears at the next good one.

A test proves the restore path with the same file. `tests::backup_secrets` gains `a_backup_restores`: seed a family,
`perform_backup`, `check` it, put it through `startup::apply_restore_request` into a fresh data directory, migrate, and
read the phone back.

**Shown** (ASK 2):
- The Backups page's newest row carries a quiet "kontrollert" when it passed.
- A failure is one orange line at the top of that page, with the how-to behind its (i).
- A failure is also a family row on the Devices list, beside the push row: `attention attention-action`, linking to
  `/backups`. This is the one family-level row, because a backup belongs to no phone.

**Off-machine copies** stay the owner's. DEPLOY.md's "Backups" section gains two sentences: the backups sit on the
server's own disk, so copy `data/backups/` elsewhere; the page's "kontrollert" says a copy is whole, not that it is
safe from the disk.

## 5. Copy (nb / en)

Checked against "Words in the UI":
- Each title is the fact, six words at most, with no sub-line.
- The why and the fix are on the target page, which already words each error. The two new (i)s are below.
- A working integration and a good backup show nothing, except the quiet "kontrollert".
- A transient "svarer ikke" is a wrong state, not a choice the parent made, so it may be orange. ASK 4 asks whether
  to show it.

| Key | nb | en |
|---|---|---|
| `device.attention.integration.github_down` | GitHub svarer ikke | GitHub isn't answering |
| `device.attention.integration.fdroid_down` | F-Droid svarer ikke | F-Droid isn't answering |
| `device.attention.integration.apps_fix` (`.one`/`.other`; github and fdroid's `Fix`) | {count} app får ikke oppdateringer / {count} apper får ikke oppdateringer | {count} app can't get updates / {count} apps can't get updates |
| `device.attention.integration.met_down` | Været hentes ikke | Weather isn't loading |
| `device.attention.integration.met_fix` | MET sperrer værvarselet | MET is blocking the weather |
| `device.attention.integration.caldav_down` | Kalenderserveren svarer ikke | Calendar server isn't answering |
| `device.attention.integration.caldav_fix` | Kalenderkontoen virker ikke | Calendar account isn't working |
| `device.attention.integration.caldav_write` (both problems) | Avtaler fra telefonen lagres ikke | Events from the phone aren't saved |
| `device.attention.integration.calendar_link` (`.one`/`.other`, both) | {count} kalenderlenke oppdateres ikke / {count} kalenderlenker oppdateres ikke | {count} calendar link isn't updating / {count} calendar links aren't updating |
| `device.attention.integration.music_feed` (`.one`/`.other`, both) | {count} musikkinnslag oppdateres ikke / {count} musikkinnslag oppdateres ikke | {count} music entry isn't updating / {count} music entries aren't updating |
| `device.attention.integration.storytel_down` | Storytel svarer ikke | Storytel isn't answering |
| `device.attention.integration.spotify_down` | Spotify svarer ikke | Spotify isn't answering |
| `device.attention.integration.blocklist` (`.one`/`.other`, both) | {count} blokkeringsliste oppdateres ikke / {count} blokkeringslister oppdateres ikke | {count} blocklist isn't updating / {count} blocklists aren't updating |
| `device.attention.filter_down` | Nettfilteret er nede | Web filter is down |
| `device.filter.down` (the filter page's orange line) | Nede siden {time} | Down since {time} |
| `device.filter.down_info` (its (i)) | Telefonen prøver igjen selv. Hjelper det ikke, start telefonen på nytt. | The phone keeps trying. If it doesn't help, restart the phone. |
| `events.filter_down.title` (inbox and push) | Nettfilteret er nede på {phone} | Web filter down on {phone} |
| `notifications.switch.filter_down` (Notify Me About) | Nettfilteret nede | Web filter down |
| `settings.backups.checked` (quiet, the newest row) | kontrollert | checked |
| `settings.backups.check_failed` (the page's orange line) | Sikkerhetskopien kan ikke leses | Backup can't be read |
| `settings.backups.run_failed` (the page's orange line) | Sikkerhetskopieringen feilet | Backup failed |
| `settings.backups.check_info` (its (i)) | Vibb åpner hver nye kopi og sjekker databasen i den. Ta neste kopi nå, og se serverloggen hvis den også feiler. | Vibb opens each new copy and checks the database in it. Make a new copy now; if it fails too, see the server log. |
| `devices.warning.backup` (the Devices list's row; it says which of the two from the code) | Sikkerhetskopien kan ikke leses / Sikkerhetskopieringen feilet | Backup can't be read / Backup failed |

Nothing is new on the kid's phone. The "connecting" notification while the filter is down is today's behaviour.

## 6. Steps

| # | Step | Files | Tests | Size |
|---|---|---|---|---|
| 0 | The register (this commit) | `docs/plan/dependency-register.md` | - | done |
| 1 | The store and the rule | migration `0158_failures_visible.sql` (the table; `device_status.vpn_filter_json`; `notification_prefs.push_filter_down` if ASK 1 is yes), `S/integration_health.rs`, `S/retention.rs` | `integration_health::tests`: `raised` per row of §2.2 (stop at once, parent twice, transient after grace and three, a success clears, alternating never raises); the upsert | S |
| 2 | Feed it at each call site | `S/handlers/tracked_apps.rs`, `S/fdroid.rs` (through tracked_apps), `S/weather_cache.rs`, `S/caldav.rs`, `S/calendar_kid_events.rs`, `S/calendar_feeds.rs`, `S/music_sweep.rs`, `S/storytel.rs`, `S/spotify.rs`, `S/dns_engine.rs` | for each key, one test that drives an existing fake or fixture to a failure and a success and reads the row (`tests::tracked_apps`, `tests::fdroid`, `weather_cache::tests`, `tests::calendar`, `tests::calendar_feeds`, `calendar_kid_events::tests::fake_dav`, `tests::music_sweep`, `storytel::tests`, `spotify::tests`, `tests::dns_blocklist`); each `class_of` table | M |
| 3 | The Attention kind | `S/attention.rs` (`Kind::Integration`, `Facts.integrations`, `facts`/`facts_of`), `S/integration_health.rs` (`for_device`), `S/i18n/{nb,en}.json` | `tests::attention`: a row per mapping (an app checked on one phone shows there only; a calendar source on two phones shows on both), the count, the target, clears on success; `tests::i18n_pages::nb_pages_have_no_english` | S-M |
| 4 | Filter down, both directories in one commit | `L/server/KidVpnService.kt`, `L/server/MobileNetwork.kt`, `L/server/MobileNetworkRuntime.kt`, new `L/server/VpnRetryAlarm.kt` (+ manifest receiver), `L/server/dto/StatusReportRequest.kt`, `L/server/MdmSyncWorker.kt`; `S/handlers/device_api.rs`, `S/mobile_network.rs`, `S/status_heartbeat.rs` (`REPORT_KEYS`), `S/attention.rs` (`FilterDown`), `S/parent_events.rs` (ASK 1), `T/device_filter.html`, `T/notifications_settings.html`; both copies of `mobile_network.json`; root CLAUDE.md | `MobileNetworkTest` (`states`, `reports`, the widened `retriesFailedEstablish`), `tests::mobile_network::the_contract` (`raise`, `clean`), `tests::attention` (`FilterDown`, at last report), `tests::notifications` (once per period, the switch), `intake_limits` | M (launcher M, server S) |
| 5 | The backup check | `S/handlers/backups.rs`, `S/startup.rs` (the startup check), `T/backups.html`, `T/devices_list.html`, `S/handlers/devices.rs`, `server/DEPLOY.md` | `tests::backup_secrets::a_backup_restores`; `backups::tests` for each code (a zip without the db, a truncated db, an older migration); the page's line and the Devices row | S |
| 6 | The emulator gate (§3.2), after the user's OK | `docs/testing/emulator.md` (a §6 entry) | the three checks on compat34 and API 37 (or handy36) | S |
| 7 | Notes | `docs/notes/server/pwa.md` (Attention), `docs/notes/server/dns.md`, the launcher's VPN note, `server/CLAUDE.md` index line | - | S |

Order: 1 → 2 → 3. Step 4 and step 5 stand alone (step 4 can run in the second worktree). Step 7 rides with each
step's commit. In all, about two nights. Releases: the server first, since it accepts and ignores a missing
`vpn_filter`. Then the launcher RC.

## 7. ASKs (what the parent would notice beyond R1 as approved)

1. **Should "Web filter down" push?** R1 asked for a row. S-M2 asked for an event too. Proposal: an inbox event that
   is also pushed, urgent like "An app may read the PIN", once per down period, under its own switch "Nettfilteret
   nede" (on by default). **Recommendation: yes.** While the tunnel is down, all of the kid's DNS goes unfiltered, and
   a row the parent only sees when they open the app can wait for hours.
2. **Where should the backup check show?** Proposal:
   - a quiet "kontrollert" on the newest row of the Backups page;
   - on a failure, an orange line there and a row at the top of the Devices list.

   **Recommendation: yes to all three.** A broken backup is found when it is needed, and nobody opens the Backups
   page unless something is already wrong.
3. **The blocklist feeds get a row too** ("2 blokkeringslister oppdateres ikke"). They are not on R1's list. They
   fail closed (the last good domains stay), but a list that never refreshes ages quietly. **Recommendation: yes**
   (one call site, the same rule).
4. **Should outages the parent can't fix show?** These are "GitHub svarer ikke", "Været hentes ikke", "Storytel
   svarer ikke" and the like, after the grace in §2.2 (12 h; 6 h for weather and events; 24 h for music).
   **Recommendation: yes, with those graces.** The state is wrong: updates, weather or new books aren't coming. The
   row says so once and leaves when the service answers. Without these rows, R1 would cover only the parent-fixable
   half.
