# 116 - Failures made visible (consolidation C2, audit R1)

Status: design. Revision 2, 2026-11-18.
- **Design QA:** `docs/review/116-design-qa.md` (3 H, 11 M, 12 L). Every finding is applied; §9 says where.
- **The user's answers** to revision 1's four ASKs (2026-11-18), recorded in §8: all four yes, as recommended. Two
  additions from the user:
  - with ASK 3, QA M4: a blocklist row only for feeds in force on that phone;
  - with ASK 2, the backups folder mounted outside the container, so the host's own backup takes it (new part E).
- **Build order:** built after design 114 merges. Both touch `S/attention.rs`, `T/devices_list.html` and the Devices
  list's handler. 114 owns `setup_steps`, the setup pages and Attention `Setup`; this design adds beside them and
  changes none of them.

**Inputs:**
- the audit `docs/review/2026-11-17-maintenance-audit.md` (§1, §2 #7, §4 I1, §5 R1 and the register's format);
- PLAN.md "Consolidation before new features", C2;
- the apps security review `docs/review/security-review-2026-11-17-apps.md`, S-M2 (folded in as part C);
- CLAUDE.md "Words in the UI".

Read against `main` at 226468b0c. Paths: `S/` = `server/src/`, `T/` = `server/templates/`,
`L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.

The parts:
- **A.** The dependency register: `docs/plan/dependency-register.md` (26 rows, each checked against the code; what
  wasn't checked is marked).
- **B.** One Attention kind, `Integration`, fed by each outside call's last success and last error.
- **C.** "Filter on, tunnel down" from the phone (S-M2): a retry that survives deep sleep, a `vpn_filter` report
  field, an Attention row and a pushed event.
- **D.** A check of every backup.
- **E.** The backups folder on the host in Docker.

**Out of scope:**
- Moving the existing music kinds (`MusicStorytelRefused`, `MusicSpotify*`, `MusicFailing`) into the new kind. That is
  the integration seam's work (audit I5).
- The server's update check and the setup QR's launcher check. Both call GitHub, and both are listed in the register
  (D11), but they already show on the page where they matter (Software Updates, Phone Setup).
- Copying backups off the machine. The server never acts on its host; part E makes them easy for the owner's own
  backup.

## 1. What the code has today

Every server integration records something, but in a different place each, and four of them only in memory. Nothing
reaches Attention except Spotify's account rows.

| Key | Call site, cadence | Recorded today | Scope (phones: §2.3) | Shown today |
|---|---|---|---|---|
| `github` | `tracked_apps::fetch_releases`, hourly (`run_scheduled_tracked_app_sync`, `enabled = 1` apps only), no backoff | in memory, `AppSyncs` → `SyncStatus.last_error` (`SyncProblem`), lost at a restart; refusals remembered in `SyncStatus.refused` and skipped hourly | catalog app | the Apps list's badge, the app's page |
| `fdroid` | `tracked_apps::fdroid_releases` → `fdroid::*`, hourly | the same in-memory `SyncFailure` | catalog app | the same |
| `met` | `weather_cache::fill_forecast`, when a phone asks and `next_fetch_at` has passed; quick retries and a backoff | `weather_cells.last_error`, `failures` | family-wide | nothing |
| `caldav` | `caldav::refresh_source` per CalDAV calendar source, every 30 min with backoff; a refused password stops the account | `caldav_accounts.state`, `calendar_sources.error` | account (stops), calendar source (the rest) | Device › Calendar |
| `caldav_write` | `calendar_kid_events::work`, on a phone's post or every 60 s; a `StopDevice` skips the phone until the parent's retry | `device_kid_events.error` | phone | Device › Calendar |
| `calendar_link` | `calendar_feeds::refresh`, every 30 min with backoff | `calendar_sources.error`, `failures`, `ok_at` | calendar source | Device › Calendar |
| `music_feed` | `music_sweep::check_entry` (NRK, RSS), every 1-24 h with backoff | `music_listings.error`, `failing_since`, `failures` | entry | the Music tab's entry lines |
| `storytel` | `storytel::login`, `shelf`, `phone_jwt`; cooldown and an in-memory backoff | `music_storytel.login_error` | family-wide | Music › Settings; Att `MusicStorytelRefused` from the phone's report |
| `spotify` | `spotify::access_token`, `api_get`; a quota pause in memory | `music_spotify_accounts.refresh_error`, `needs_login` | account | the Spotify page; Att `MusicSpotify*` |
| `blocklist` | `dns_engine::compile_blocklist`, hourly, every 5 min while one fails; downloads **every** feed, on or off | in memory, `CompiledList.last_error` | feed | Settings › Web filter |

UP's gateway is not on the list. Its outside party is inbound (homeservers post to us), and design 108c's `AppPush*`
rows are already raised from the server's own data. Web Push to parents has `push::Health` and the Devices list's push
row. Nothing on the server reads a missing `dns_upstream` as "down" today (S-M2's server question): the `/dns` page only
words an outage the phone reports.

## 2. Part B: the `Integration` row

### 2.1 One store, fed at each call site

The C2 migration (its number is taken at build time: the next free after 114's 0157 and whatever else has merged,
e.g. design 110's) adds:

```sql
CREATE TABLE integration_health (
    key TEXT NOT NULL,       -- github, fdroid, met, caldav, caldav_write, calendar_link, music_feed, storytel, spotify,
                             -- blocklist; backup (part D)
    scope TEXT NOT NULL,     -- '' (family-wide), 'app:<id>', 'account:<id>', 'source:<id>', 'device:<id>',
                             -- 'entry:<id>', 'feed:<id>', 'file:<name>' (part D)
    ok_at TEXT,              -- the last success
    error_at TEXT,           -- the last failure
    error TEXT,              -- its code, the call site's own (logged, never shown)
    class TEXT CHECK (class IN ('transient', 'parent', 'stop')),
    failing_since TEXT,      -- the first failure after the last success; NULL while it works
    failures INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (key, scope)
);
```

A new module `S/integration_health.rs` (the name avoids `unified_push::health` and `push::Health`):
- `ok(db, key, scope)` sets `ok_at`, clears `failing_since` and sets `failures` to 0. To keep it cheap (QA L10), it
  writes only when the row is failing or its `ok_at` is over 10 minutes old. Spotify and Storytel call it per request.
- `failed(db, key, scope, code, class)` sets `error`, `error_at` and `class`, sets `failing_since` if it is empty, and
  adds 1 to `failures`.
- Both are one upsert. A write error is logged and never fails the call it records.

**The classes.** Each call site maps its codes with one `fn class_of(..) -> Option<Class>`, an exhaustive `match`
with no `_` arm, so a new variant doesn't compile until it is classed (QA M7). `None` means "not recorded". These
are the call's success, or our own state: the server's disk, a race, our own waits. Step 2's tests run every row.

| Key | transient | parent | stop | not recorded |
|---|---|---|---|---|
| `github`, `fdroid` (`SyncProblem`) | `GithubUnreachable`, `GithubError` (403/429/5xx), `Download`, `RepoUnreachable` | `RepoNotFound`, `NoRelease`, `Filter`, `FdroidUnknown`, `NotInRepo`, `RepoError` | every refusal `SyncStatus.refused` remembers: `OtherApp`, `OtherKey`, `Taken`, `TooBig`, `Unreadable`, `CantCheck`, `IndexSigner`, `RepoKey`, `RepoSignature`, `RepoRollback`, `ChecksumMismatch` (at the refusal, after its 3 tries) | `Server`, `Removed`; the hourly skip of a remembered refusal; `OlderVersion` records `ok` (the source answered; the cached release is kept on purpose) |
| `met` | `network`, `timeout`, `status_429`, `status_5xx`, `status_other`, `unreadable`, `cut` | `status_403` (the owner's `MET_CONTACT`) | - | - |
| `caldav` | network, timeout, 5xx, 408, 429 | `not_found`, `not_listed`, `not_caldav`, `unreadable` | `login_refused`, `host_changed`, `key_lost` (at `account:<id>`) | - |
| `caldav_write` | 429, 503, network, timeout | - | every `StopDevice` code: `write_refused`, `full`, `no_etag`, `not_listed`, `too_big` (QA H2; recorded when the stop is set; the parent's retry, `RETRIED_STOPS`, clears it at the next good write) | `bad_set`, `tag_pinned:*` (the phone's set refused, not CalDAV) |
| `calendar_link` (`FeedError`) | `network`, `timeout`, `status` 5xx/408/429, `empty` (held) | `not_found`, `status` (other 4xx), `not_calendar`, `unreadable`, `insecure`, `private_address`, `too_big` | `key_lost` | - |
| `music_feed` (`CheckError`) | `network`, `timeout`, `http_5xx`, `http_408`, `http_429` | `not_found`, `not_feed`, `no_items`, `too_big`, every other `http_4xx` (401/403: the feed went private) | - | - |
| `storytel` (`StorytelError`) | `Fetch`, `Status` (5xx/408/429), `LoginStatus` (5xx/408/429), `NoJwt`, `ShortLife`, `TooBig`, `BadShelf` | - (its parent-side problems are `MusicStorytelRefused`'s) | - | `NotConfigured`, `KeyUnusable`, `Refused`, `Cooldown`, `Backoff`, `Busy`, `Database` |
| `spotify` (`SpotifyError`) | `Fetch`, `Status` (5xx), `RateLimited`, `Quota`, `TooBig`, `BadAnswer` | - (its parent-side problems are `MusicSpotify*`'s) | - | `Off`, `NoAccount`, `Reauthorize`, `AppRefused`, `NotFound`, `Key`, `Database` |
| `blocklist` | network, 5xx, 408, 429 | 404/410 and other 4xx, too big, not a list (a typed `FeedFailure` replaces `fetch_feed`'s `String`) | - | - |

A few rules sit beside the table:
- `music_feed` covers NRK and RSS entries only. Storytel's and Spotify's listings record under their own keys.
- `met` records family-wide (`''`), so one cell's success ends another cell's run (QA L6). That is accepted: an outage
  of MET is MET's, and a single cell failing alone is rare.
- `met`, `storytel` and `spotify` record only when called (QA L5). A `Down` row stays until the next call. That is
  fine while phones ask, which is when the row matters.
- **Retention**: a row with neither `ok_at` nor `error_at` in 30 days is deleted (`retention.rs`). A scope that is gone
  (an app removed) is never read: the facts join only through live mapping rows.

### 2.2 When a failure becomes a row

A pure function, `raised(row, grace, now) -> Option<Problem>`, with `Problem::{Down, Fix}`:
1. The newest outcome is a success (`failing_since` NULL): no row. **The next success clears the row at once.**
2. The newest failure is `stop`: `Fix` at once. The integration has stopped itself, and no retry will mend it.
3. The newest failure is `parent` and `failures >= 2`: `Fix`. `failures` counts every failure since the last
   success, so "transient, transient, parent" is already `Fix` (QA L4). That is accepted: three failures in a row
   ending in the parent's kind is worth the parent's look.
4. Otherwise (transient, or a single parent-class failure): `Down` once `failures >= 3` **and** `now - failing_since`
   is at least the key's grace.

| Key | Grace | Why |
|---|---|---|
| `github`, `fdroid`, `calendar_link`, `caldav`, `storytel`, `spotify`, `blocklist` | 12 h | hourly or half-hourly calls: a night's outage stays quiet; a dead day doesn't |
| `met`, `caldav_write` | 6 h | the kid sees a missing weather line or a missing event the same day |
| `music_feed` | 24 h | the sweep runs every 6 h by default; three failures take a day anyway |

There is no flapping: a row needs a sustained run, and any success ends the run. The cost is that an integration
that fails every other call never raises. That is accepted: its feature still works half the time, and its page
keeps showing the last error.

### 2.3 Where it shows

On the phone's Attention list (the summary, and the Devices list's count), for the phones each scope maps to. A
choice the parent made maps to no phone, so it raises nothing:

| Scope | Phones |
|---|---|
| `app:<id>` | `device_tracked_apps` rows, plus **every enrolled phone** when `tracked_apps.is_launcher = 1` (Vibb One's own updates, never in `device_tracked_apps`; QA M5); only while `tracked_apps.enabled = 1` |
| `met` `''` | phones with `device_policy.lock_weather = 1` (QA M3) |
| `account:<id>` (CalDAV stops) | phones with one of the account's calendars (`calendar_sources.account_id` → `device_calendar_feeds`) |
| `source:<id>` (`caldav`, `calendar_link`) | `device_calendar_feeds` (QA M6) |
| `device:<id>` | that phone |
| `entry:<id>` | `device_music_entries` |
| `storytel` `''` | `device_policy.music_storytel = 1` |
| `account:<id>` (Spotify) | `device_policy.music_spotify_account` |
| `feed:<id>` | phones with `vpn_filter_enabled = 1` **and** the feed in force for them: `dns_blocklists.enabled` unless the phone's `device_blocklist_overrides` row says otherwise, the rule `device_api::dns_blocklist` resolves (QA M4, the user's answer to ASK 3) |

The Attention plumbing:
- `attention::Kind::Integration(Title, usize)`. `Title` is a `Copy` enum, one variant per title key in §6. The
  `usize` is the number of failing scopes behind the row.
- `integration_health::for_device(db, device_id)` runs one query joining `integration_health` to the mapping tables,
  then `raised` per row. It groups by **title**, summing the counts (QA L3). So github's and fdroid's `Fix` make one
  "3 apper får ikke oppdateringer" row, and a `Down` scope beside a `Fix` scope of the same key make one row, not two
  identical ones. `key()` is `integration-<title>`, unique per row.
- The row is orange (`Level::Action`) and comes after the music rows. `Integration` joins `read_from_report()`'s
  negative list (QA L9): it is the server's own data, never "at last report".
- `Facts.integrations: Vec<(Title, usize)>`. The Devices list renders `facts` per phone already; this adds one read
  per phone.

| Title | Target |
|---|---|
| github and fdroid's | `/apps` (the list's `update_failed` badge says which) |
| `met_*` | `/devices/{id}/lock#weather`, which gains one orange line while the row is raised (QA M3, §6) |
| `caldav_*`, `caldav_write`, `calendar_link` | `/devices/{id}/calendar` |
| `music_feed` | `/music` |
| `storytel_down` | `/music/settings#storytel` |
| `spotify_down` | `/devices/{id}/music#music-spotify` (the existing Spotify rows' target) |
| `blocklist` | `/dns` |

Every target page already says what the error is, except Weather, which this design gives a line.

### 2.4 No push

These rows are Attention only: no parent event, no inbox entry, no push.
- Every integration fails soft. The core (policy, locks, calls, the catalog's signature checks) works without them.
- A row is a state, not something that happened. An event would need a second "it works again" event to stay true;
  the row clears itself.
- An outage the parent can't fix (GitHub, MET) would push once per phone and day for nothing to act on.

The Devices list's count is where the parent looks. The user approved showing those outages after the graces (ASK 4).

## 3. Part C: "filter on, tunnel down" (S-M2)

### 3.1 The phone

**The state, read live.** A pure function in `L/server/MobileNetwork.kt`, `vpnFilterState(filterOn, tunnelUp,
shape): VpnFilterState`:
- `OFF`: `!filterOn`. That covers the parent's switch, a lift, a pause, the offline override, and 114's setup hold;
  every one clears `VpnInputs.filterOn` before `apply()` writes the shape.
- `UP`: the tunnel is established in `FILTER` or `SINK`. The sink answers every DNS query `REFUSED`, so it filters.
- `DOWN`: everything else:
  - `establish()` returned null or threw;
  - the read loop is dead;
  - `onRevoke` stopped the service;
  - no service is running;
  - a tunnel is left in `PASS` after a failed switch to `FILTER` (a tunnel, but no filter).

The status report computes it **at report time** (QA M1):
- `filterOn` comes from the stored inputs.
- `tunnelUp` comes from a new companion accessor that reads the live instance's `state.tunnel != null` (false with no
  instance).
- `shape` comes from the live instance's `shape`, never `tunnelShape`, which keeps a dead tunnel's shape on purpose.

A stale stored value can't say `up` over a dead service.

**The down period** (QA H3). It is measured on the phone, never across a boot, and never between two clocks:
- Its start is stored as elapsed realtime plus `Settings.Global.BOOT_COUNT` (the pattern of 114's setup hold), so a
  reboot ends any period.
- A period starts only once this process's service has tried and failed: after the first failed `establish()`, or
  60 s after the process start with no tunnel up. A shutdown, an `onDestroy`, a self-update's restart and the first
  minute of a fresh process never start one.
- The report sends `vpn_filter = {state, down_ms}`. `down_ms` is the period's length at report time, present only
  with `down`.
- The event's key is the period's id: the boot count and the period's start (elapsed), as `"<boot>:<elapsed_ms>"`.
  The report carries it as `period`.
- `vpn_filter` is not a heartbeat field, so a change alters the report's hash and a full report goes.

**The retry** (S-M2):
- `retriesFailedEstablish(wanted, established)` becomes `!established && wanted != NONE`.
- One retry chain, not two (QA L1): `retryTunnel` (a lost tunnel) calls `startVpn`, whose `switchTo` arms
  `reshapeRetry` on a failure, and `retryTunnel` no longer reschedules `retry` when `switchTo` ran. The main
  `Handler` chain (5 s doubling to 5 min) is the fast path while the phone is awake.
- Each failure, and `onRevoke` while `filterOn`, also arms one `VpnRetryAlarm`:
  - `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, now + 5 min)`, the `BackstopAlarm` pattern with its own request code
    (not 7).
  - Its receiver writes always-on for the stored shape (`MobileNetworkRuntime.writeAlwaysOn`) and calls
    `KidVpnService.start`. That is `applyVpnRestrictions`' path from the stored inputs, without a sync.
  - It re-arms while the state is `DOWN` and is cancelled at `UP` or `OFF`.
  - Today's path stays beside it (QA L2): the backstop sync every 30 min (15 with SSE down), each running `apply()`
    and so `KidVpnService.start`. The alarm shortens 30 minutes to 5.

**Telling the server**:
- When `down_ms` reaches `report_after_ms` (600 000, from the contract), the retry that sees it calls
  `SyncRunner.request(context, "vpn_filter")`, once per period.
- The first `UP` after a reported `DOWN` requests a sync too.
- Both work during the outage: the launcher is outside its own VPN in every shape.

**Pure and tested** (QA L11): the period's start and end (`vpnDownPeriod`), the alarm's arm/cancel
(`vpnRetryAlarmAction`) and the once-per-period sync request (`vpnFilterSyncDue`) are pure functions. Their rows are in
the contract (§3.5).

### 3.2 Android 14-17 (checked 2026-11-18)

The launcher targets API 36 (`app/build.gradle.kts`). Android 17's "targeting 17" changes don't apply; its all-apps
changes do.

| Version | What matters here | Verdict |
|---|---|---|
| 14 (34) | A foreground service type is required: `specialUse` is declared. Exact alarms are denied by default to new installs, so the inexact `setAndAllowWhileIdle` needs no permission. A background FGS start is allowed for a Device Owner (the FGS docs' exemption list). Always-on via `setAlwaysOnVpnPackage` is a DO call since API 24, and the system keeps an always-on VPN bound, so a killed process comes back. | works as designed |
| 15 (35) | The `BOOT_COMPLETED` FGS ban covers dataSync, camera, mediaPlayback, phoneCall, mediaProjection and microphone, not `specialUse`. Nothing for VpnService or while-idle alarms. | no change |
| 16 (36) | The behaviour-change pages list JobScheduler quotas (not used here), ordered-broadcast priority (not used) and intent redirection hardening (an explicit `PendingIntent` to our own receiver isn't affected). Nothing for VpnService or AlarmManager. | no change |
| 17 (37) | Among the all-apps changes is the app memory limiter, which can kill a process. The system's binding restarts the VPN service, and the alarm covers the gap. Nothing for VpnService, AlarmManager or FGS. | no change; one more way to reach `DOWN`, covered |

While-idle alarms fire in Doze within Android's while-idle quota (per hour at targetSdk 36; QA L2). One alarm every 5
minutes is well inside it.

### 3.3 The emulator gate (QA M2)

It runs on compat34 (the lowest) and the API 37 image (the highest), if that runs on the VM. Otherwise it runs on
handy36, with 37 named as owed. It needs the user's OK first. Each step reads the report **and**
`adb shell dumpsys connectivity` (the VPN network present or not).

0. **Check that the deny holds.** Run `adb shell appops set me.vibb.launcher.debug ACTIVATE_VPN deny`. Then confirm
   that a `setAlwaysOnVpnPackage` write (a shape change) doesn't re-grant it on that image. If it does, the gate uses
   the crash path in step 1 only, with the deny set just before it.
1. **A dead tunnel that can't come back.**
   - Set the deny, then kill the launcher process (`am crash` or `kill`). The system's binding restarts the service,
     and `onCreate`'s `establish()` fails.
   - The report says `down`. After 10 minutes the server shows the row, and the push arrives.
2. **Recovery in Doze.**
   - Set `adb shell dumpsys deviceidle force-idle`, then `appops ... allow`.
   - The alarm brings the filter up within its 5-minute window.
   - The next report says `up`, and the row clears.
3. **`PASS` that fails to become `FILTER`.**
   - Set Mobildata "Bare Vibb" first, so a pause's shape is `PASS`, and stay on Wi-Fi.
   - Start a pause, set the deny, then end the pause.
   - The report says `down`, not `up`.
4. **A reboot doesn't start a period.** Reboot while the filter is on. The first report after the unlock says `up` or
   no `down_ms` over 60 s, and no event.

### 3.4 The server

**Intake.** `device_status.vpn_filter_json` (the C2 migration), kept through `mobile_network::clean_vpn_filter`:
- `state` is one of the three words; any other value drops the field.
- `down_ms` is a non-negative integer, kept only with `down`.
- `period` is at most 32 characters of `[0-9:]`.
- `REPORT_KEYS` gains `vpn_filter`, and `intake_limits` keeps its keys.

**Attention `FilterDown`** (red, `Level::Alarm`: filtering is open). It is placed among the alarms after
`ForeignAccess`, and after 114's `NotDeviceOwner` once that has merged (QA L9). It is raised when all of these hold
(QA M10, a parent's choice is never down):
- the last report says `down` with `down_ms >= report_after_ms`;
- the server's `device_policy.vpn_filter_enabled = 1` now;
- the report isn't paused (`DeviceStatus::paused_at`);
- no hold the server knows of is in force (114's setup hold, once merged).

It is read from the report, so it is marked "at last report" when the phone is offline. Target:
`/devices/{id}/filter`.

**The phone's filter page** shows the same fact as one orange line at the top: "Nede i {duration}" (the report's
`down_ms`), plus " · ved siste rapport" when stale (QA L8). The how-to is behind its (i). The `/dns` page's line for
that phone says "Nede" too, so the two pages agree.

**The event `FilterDown`** (the user's answer to ASK 1):
- It goes to the inbox and is pushed. It is urgent, like `ForeignAccess`, under its own switch `push_filter_down` (on
  by default; the C2 migration).
- It is raised once per down period, keyed by `period`, at the first report that raises the row, under the same
  conditions.
- No event when the filter comes back; the row going is enough.

### 3.5 The contract

The new rows join `server/testdata/mobile_network.json`. It is the VPN shapes' contract already, and its CI path
filters already exist, so there is no new file. The new key is `vpn_filter`:

| Field | What it holds |
|---|---|
| `report_after_ms` | 600000 |
| `reports` | the exact `vpn_filter` of a report: `up`, `down` (with `down_ms`, `period`), `off` |
| `states` | `{filter_on, tunnel_up, shape} -> state`, with the PASS-after-a-failed-switch row |
| `periods` | `{events: [process_start, establish_failed, tunnel_up, boot, ...], at_ms} -> {down_ms, period}`, with the rows "a reboot starts a new period" and "the first 60 s of a process start none" |
| `alarm` | `{state, armed} -> arm \| cancel \| keep` |
| `sync` | `{down_ms, reported_this_period, state, last_reported} -> request \| none` |
| `raise` | `{state, down_ms, filter_enabled, paused} -> row` |
| `clean` | the server's cleaning |

The tests on each side:
- The launcher's `MobileNetworkTest` runs `states`, `periods`, `alarm` and `sync`, encodes `reports` exactly, and checks
  its constant against `report_after_ms`.
- The server's `tests::mobile_network::the_contract` posts `reports`, runs `raise` and `clean`, and checks what is
  stored.
- Both compare the copies.

The root CLAUDE.md's "Mobilnett" paragraph gains one clause naming `vpn_filter`.

## 4. Part D: the backup check

The smallest proof that a backup restores is to open, after every backup, exactly what a restore would open, and
check that this binary can restore it.

**When it runs** (QA M8):
- In a spawned task after `perform_backup` has written the zip, scheduled or "Back up now". It never delays the
  backup's answer; the page shows "kontrollert" on its next load.
- At startup, for the newest backup if it has no row.

**What it does:**
- It reads the zip's `kidphone.db` under the restore's own 1 GiB cap.
- It writes it to a unique scratch file in `data/` (`backup-check-<timestamp>-<rand>.db`; never `data/backups/`, the
  `snapshot` rule) and opens it read-only and immutable.
- The scratch file is deleted after the check, also on a failure. At startup, `backup-check-*.db` files are swept, as
  `remove_partial_downloads` does.
- If the scratch write fails (a full disk), nothing about the backup is recorded. It is logged on its own line: the
  zip may be whole.

**The checks:**
- `PRAGMA integrity_check` must answer `ok`.
- **Restorable by this binary** (QA H1): every `_sqlx_migrations` row has `success = 1`, a version that
  `sqlx::migrate!()` knows and that version's checksum, and none is newer than the binary's newest. An older backup
  passes: the restore migrates it forward. This holds at the C2 release's own first start and after any upgrade.
- `SELECT COUNT(*) FROM devices` must run.

**The codes:**
- `no_db`, `unreadable`, `integrity`: the file is broken.
- `version`: a newer or a different build made it.
- `failed`: the scheduled or manual backup itself failed. Today that is only logged.

**The record** (QA M8):
- `integration_health('backup', 'file:<name>')` for each checked zip, class `stop`. A row means "checked".
- `('backup', '')` holds `failed`.
- The **failure shows** when the newest `backup-*.zip` in the folder has a failing row, or `('backup', '')` is
  failing and newer than the newest zip.
- Deleting the bad file, or a newer good backup, clears it.
- `file:` rows of files that are gone go at the prune.

**The restore path is proven with the same file.** `tests::backup_secrets` gains `a_backup_restores`: seed a family,
`perform_backup`, `check` it, put it through `startup::apply_restore_request` into a fresh data directory, migrate,
and read the phone back. `backups::tests` gains one case per code. It also checks that an older backup passes, and
that a changed checksum or a newer version fails.

**Shown** (the user's answer to ASK 2):
- The Backups page's newest row carries a quiet "kontrollert" when it passed.
- A failure is one orange line at the top of that page, with the how-to behind its (i).
- A failure is also a family row on the Devices list, beside the push row: `attention attention-action`, linking to
  `/backups`. It is the one family-level row, because a backup belongs to no phone.

## 5. Part E: the backups folder on the host (the user, 2026-11-18)

The user: "vi må kanskje også sikre at backups monteres utenfor docker container så vi får tatt backup av backups?"
In Docker the backups live in the `handy-data` named volume, which a host's file-level backup rarely takes. They
should sit in a host folder beside `compose.yaml`.

**`server/deploy/compose.yaml`:**

```yaml
    volumes:
      - handy-data:/app/data
      # The backups (zips, pre-migration and pre-restore copies) in a folder on the host, so the host's own backup
      # takes them. It must belong to uid 65532: mkdir -p backups && sudo chown 65532:65532 backups && sudo chmod 700 backups
      - ./backups:/app/data/backups
      # The Storytel, Web Push and Child PIN keys: their own volume, never in the data volume or the backups folder.
      - handy-keys:/app/data/keys
```

**What was checked:**
- The image runs as uid 65532 (distroless nonroot, `USER 65532`).
- With `cap_drop: [ALL]` there is no `CAP_DAC_OVERRIDE`, so the folder must belong to 65532 exactly.
- `read_only: true` covers the image's root filesystem, not mounts, so the bind mount stays writable.
- A bind mount isn't seeded from the image, hence the `chown`.
- The nested mount under the named volume works as `/app/data/keys` already does.
- The zip holds the database and the picture folders only (`build_backup_zip`), never a key file, and the keys stay in
  their own volume.
- On an SELinux host the mount needs `:Z`. **Unverified**: the user's Proxmox VM runs Debian.

**Two code changes the move needs** (found in this revision, not by QA):
- **`backups::snapshot` renames across filesystems.** It makes its scratch file in `data/` and renames it to its
  target. For `pre-migrate-*.db` that target is `data/backups/`, so with the bind mount the rename fails with `EXDEV`.
  `pre_migration_backup` treats that as fatal: the server would refuse to start at the next upgrade with a migration.
  The fix: on `EXDEV`, `snapshot` copies to `<target>.partial` in the target's folder, fsyncs, renames there, and
  deletes the scratch file. The startup sweep removes stale `*.partial` files. The zip is already written straight
  into the folder, and the restore's prerestore copy already copies. A test forces the copy path (a `rename` that
  fails, injected).
- **The startup check covers the folder.** `startup::check_data_writable` runs for `data/backups` too, with the same
  message (`chown 65532:65532`). A folder left root-owned then stops the start with a clear line. Without the check,
  every backup and the next upgrade would fail later.

**`server/DEPLOY.md` "Docker":**
- The yaml block and the Volumes bullet gain the folder and the `chown` line.
- "Rolling back" takes the pre-migration copy from `./backups/` (`-v "$PWD/backups:/backups"`).
- "Copy `data/backups` off the host yourself" becomes "the host's backup takes `./backups`". Keep it as safe as the
  admin password: it holds full copies of the family's database.
- A short note for an existing install moving its backups out of the named volume:

  ```
  docker compose stop handy
  mkdir -p backups
  docker run --rm -v <project>_handy-data:/from -v "$PWD/backups:/to" busybox \
    sh -c 'cp -a /from/backups/. /to/ && chown -R 65532:65532 /to && chmod 700 /to'
  # take the new compose.yaml (or add the ./backups line), then
  docker compose up -d
  # once Settings › Backups lists them, free the volume's old copies:
  docker run --rm -v <project>_handy-data:/data busybox sh -c 'rm -rf /data/backups/*'
  ```

  The volume's name carries the compose project's prefix (`docker volume ls`). Watchtower doesn't change
  `compose.yaml`: this is the owner's one-time step.

`tests::deploy_scripts` gains a check that DEPLOY.md's yaml block and `compose.yaml` mount the same three paths. The
systemd install needs nothing: its `data/backups/` is already on the host.

## 6. Copy (nb / en)

Checked against "Words in the UI":
- Each title is the fact, six words at most, with no sub-line.
- The why and the fix are on the target page, which already words each error; Weather gets its line here.
- A working integration and a good backup show nothing, except the quiet "kontrollert".
- A parent's choice raises nothing (§2.3, §3.4).
- A transient "svarer ikke" is a wrong state, not a choice, so it may be orange (ASK 4, approved).

| Key | nb | en |
|---|---|---|
| `device.attention.integration.github_down` | GitHub svarer ikke | GitHub isn't answering |
| `device.attention.integration.fdroid_down` | F-Droid svarer ikke | F-Droid isn't answering |
| `device.attention.integration.apps_fix` (`.one`/`.other`; github and fdroid's `Fix`, summed) | {count} app får ikke oppdateringer / {count} apper får ikke oppdateringer | {count} app can't get updates / {count} apps can't get updates |
| `device.attention.integration.met_down` | Været hentes ikke | Weather isn't loading |
| `device.attention.integration.met_fix` | MET sperrer værvarselet | MET is blocking the weather |
| `device.lock.weather.down` / `.blocked` (the `#weather` orange line, the same words as the row) | Været hentes ikke / MET sperrer værvarselet | Weather isn't loading / MET is blocking the weather |
| `device.lock.weather.blocked_info` (its (i)) | Serveren må sende en kontaktadresse til MET: MET_CONTACT i serverens innstillinger. | The server must send MET a contact address: MET_CONTACT in the server's settings. |
| `device.attention.integration.caldav_down` | Kalenderserveren svarer ikke | Calendar server isn't answering |
| `device.attention.integration.caldav_fix` | Kalenderkontoen virker ikke | Calendar account isn't working |
| `device.attention.integration.caldav_write` (both problems) | Avtaler fra telefonen lagres ikke | Events from the phone aren't saved |
| `device.attention.integration.calendar_link` (`.one`/`.other`, both) | {count} kalenderlenke oppdateres ikke / {count} kalenderlenker oppdateres ikke | {count} calendar link isn't updating / {count} calendar links aren't updating |
| `device.attention.integration.music_feed` (`.one`/`.other`, both) | {count} musikkinnslag oppdateres ikke / {count} musikkinnslag oppdateres ikke | {count} music entry isn't updating / {count} music entries aren't updating |
| `device.attention.integration.storytel_down` | Storytel svarer ikke | Storytel isn't answering |
| `device.attention.integration.spotify_down` | Spotify svarer ikke | Spotify isn't answering |
| `device.attention.integration.blocklist` (`.one`/`.other`, both) | {count} blokkeringsliste oppdateres ikke / {count} blokkeringslister oppdateres ikke | {count} blocklist isn't updating / {count} blocklists aren't updating |
| `device.attention.filter_down` | Nettfilteret er nede | Web filter is down |
| `device.filter.down` (the filter page's orange line) | Nede i {duration} | Down for {duration} |
| `device.filter.down_info` (its (i)) | Telefonen prøver igjen selv. Hjelper det ikke, start telefonen på nytt. | The phone keeps trying. If it doesn't help, restart the phone. |
| `settings.web_filter.line.filter_down` (`/dns`'s phone line) | {phone}: nede | {phone}: down |
| `events.filter_down.inbox.title` (QA L7) | Nettfilteret er nede på {phone} | Web filter down on {phone} |
| `events.filter_down.push.title` | {phone}: nettfilteret er nede | {phone}: web filter is down |
| `notifications.switch.filter_down` (Notify Me About) | Nettfilteret nede | Web filter down |
| `settings.backups.checked` (quiet, the newest row) | kontrollert | checked |
| `settings.backups.check_failed` (the page's orange line) | Sikkerhetskopien kan ikke leses | Backup can't be read |
| `settings.backups.run_failed` (the page's orange line) | Sikkerhetskopieringen feilet | Backup failed |
| `settings.backups.check_info` (its (i)) | Vibb åpner hver nye kopi og sjekker databasen i den. Ta en ny kopi nå; feiler den også, se serverloggen. | Vibb opens each new copy and checks the database in it. Make a new copy now; if it fails too, see the server log. |
| `devices.warning.backup_check` / `devices.warning.backup_run` (the Devices list's row) | Sikkerhetskopien kan ikke leses / Sikkerhetskopieringen feilet | Backup can't be read / Backup failed |

Nothing is new on the kid's phone. The "connecting" notification while the filter is down is today's behaviour.

## 7. Steps

| # | Step | Files | Tests | Size |
|---|---|---|---|---|
| 0 | The register | `docs/plan/dependency-register.md` | - | done |
| 1 | The store and the rule | the C2 migration (the table; `device_status.vpn_filter_json`; `notification_prefs.push_filter_down`), `S/integration_health.rs`, `S/retention.rs` | `integration_health::tests`: `raised` per §2.2 row (stop at once, parent twice, transient after grace and three, a success clears, alternating never raises, transient-transient-parent is `Fix`); the upsert and its 10-minute `ok` throttle | S |
| 2 | Feed it at each call site, each with its exhaustive `class_of` | `S/handlers/tracked_apps.rs` (with `fdroid`), `S/weather_cache.rs`, `S/caldav.rs`, `S/calendar_kid_events.rs`, `S/calendar_feeds.rs`, `S/music_sweep.rs`, `S/storytel.rs`, `S/spotify.rs`, `S/dns_engine.rs` (a typed `FeedFailure`) | per key, one test that drives an existing fake or fixture to a failure and a success and reads the row (`tests::tracked_apps`, `tests::fdroid`, `weather_cache::tests`, `tests::calendar`, `tests::calendar_feeds`, `calendar_kid_events::tests` with `fake_dav` - a `StopDevice` raises at once and its retry clears -, `tests::music_sweep`, `storytel::tests`, `spotify::tests`, `tests::dns_blocklist`); every `class_of` row; a remembered refusal's skip records nothing | M-L |
| 3 | The Attention kind | `S/attention.rs` (`Kind::Integration`, `Facts.integrations`, `facts`/`facts_of`, `read_from_report`), `S/integration_health.rs` (`for_device`), `T/device_lock.html` (`#weather`'s line), `S/i18n/{nb,en}.json` | `tests::attention`: each §2.3 mapping (an app on one phone only; Vibb One's row on every phone; a disabled app none; a calendar source on two phones; weather off none; a feed off globally or by the phone's override none), the summed counts and one row per title, the targets, clears on success; `tests::i18n_pages::nb_pages_have_no_english` | S-M |
| 4 | Filter down, both directories in one commit | `L/server/KidVpnService.kt`, `L/server/MobileNetwork.kt`, `L/server/MobileNetworkRuntime.kt`, new `L/server/VpnRetryAlarm.kt` (+ manifest receiver), `L/server/dto/StatusReportRequest.kt`, `L/server/MdmSyncWorker.kt`; `S/handlers/device_api.rs`, `S/mobile_network.rs`, `S/status_heartbeat.rs` (`REPORT_KEYS`), `S/attention.rs` (`FilterDown`), `S/parent_events.rs`, `S/handlers/dns_filter.rs`, `T/device_filter.html`, `T/notifications_settings.html`; both copies of `mobile_network.json`; the root CLAUDE.md | `MobileNetworkTest` (the §3.5 rows, the widened `retriesFailedEstablish`, one retry chain), `tests::mobile_network::the_contract` (`raise`, `clean`), `tests::attention` (`FilterDown`, at last report, none with the filter off or paused), `tests::notifications` (once per period, the switch, none for a parent's off), `intake_limits` | M (launcher M, server S) |
| 5 | The backup check | `S/handlers/backups.rs`, `S/startup.rs` (the startup check and sweep), `T/backups.html`, `T/devices_list.html`, `S/handlers/devices.rs` | `tests::backup_secrets::a_backup_restores`; `backups::tests` per code (an older backup passes; a changed checksum or a newer version fails); a full disk records nothing; two checks at once; the page's line and the Devices row; deleting the bad file clears | S |
| 5b | The backups folder on the host | `server/deploy/compose.yaml`, `S/handlers/backups.rs` (`snapshot`'s `EXDEV` copy), `S/startup.rs` (`check_data_writable` for `data/backups`, the `*.partial` sweep), `server/DEPLOY.md` | `backups::tests` (the forced copy path), `startup::tests` (a folder that isn't writable stops the start), `tests::deploy_scripts` (DEPLOY.md's block equals `compose.yaml`'s mounts) | S |
| 6 | The emulator gate (§3.3), after the user's OK | `docs/testing/emulator.md` (a §6 entry) | steps 0-4 on compat34 and API 37 (or handy36) | S |
| 7 | Notes | `docs/notes/server/pwa.md` (Attention), `docs/notes/server/dns.md`, the launcher's VPN note, `server/CLAUDE.md` (its backup bullet and the index line) | - | S |

**Order (QA M9).** Steps 3 and 4 both edit `S/attention.rs` and the i18n files, and 4 needs step 1's migration.
- Worktree A: 1 → 2 → 3 → 4.
- Worktree B: 5b, then 5 once step 1 is merged. 5b needs nothing from 1.
- Step 7 rides with each step's commit.

There is one migration for C2. Its number is taken at build time.

**Size: about three nights** (QA L12; step 2 is the largest).

**Release:** the server first, since it ignores a missing `vpn_filter`. Then the launcher RC. The owner moves the
backups folder (§5) when the new `compose.yaml` arrives; it isn't needed before.

## 8. The user's answers (2026-11-18)

1. **"Web filter down" pushes**, with an inbox entry, once per down period, under its own switch, on by default:
   **yes** (§3.4).
2. **The backup check shows** a quiet "kontrollert" on the newest backup, an orange line on Backups and a family row
   on the Devices list on a failure: **yes** (§4). The user added the backups folder on the host (§5).
3. **Blocklist feeds get a row**: **yes**, only for feeds in force on that phone (QA M4; §2.3).
4. **Outages the parent can't fix show** after the graces: **yes** (§2.2).

## 9. Design QA applied (`docs/review/116-design-qa.md`)

| Finding | Where |
|---|---|
| H1 the backup's version rule | §4 "Restorable by this binary" |
| H2 the kid's-calendar stops never repeat | §2.1 the table (`caldav_write` stop) |
| H3 `since_ms` across a shutdown and two clocks | §3.1 "The down period", §3.4, §3.5 `periods` |
| M1 the state read live | §3.1 "read live" |
| M2 the emulator gate | §3.3 |
| M3 `met`'s mapping and page line | §2.3, §6, step 3 |
| M4 blocklist feeds in force only | §2.3 |
| M5 Vibb One's row, disabled apps | §2.3 |
| M6 CalDAV per source | §1, §2.1, §2.3 |
| M7 the full class tables | §2.1 |
| M8 the backup check's task, scratch, disk, file rows | §4 |
| M9 order and the migration number | §2.1, §7 |
| M10 a parent's choice on the server | §3.4 |
| M11 the register | `docs/plan/dependency-register.md` D11, D16, D25, D26 |
| L1-L12 | §3.1 (L1, L11), §3.2 (L2), §2.3 (L3, L9), §2.2 (L4), §2.1 (L5, L6, L10), §6 (L7), §3.4 (L8, L9), §7 (L12) |
