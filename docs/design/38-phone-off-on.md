# 38 - The phone turned off and on: notifications to the parent (proposal)

Asked 2026-10-19. Builds on design 35 (`parent_events`, Web Push, Notify Me About) and replaces the "offline" alert
dropped there on 2026-10-18 (qa-35-design L3, design 35 §2.3). One step, both directories.

## 0. Goal and non-goals

**Goal.** The parent hears when the kid's phone goes off and when it starts again, and why, as far as the phone can
tell: "Anna was turned off · 21:14 · battery 3 %, probably flat" / "Anna was turned on · 07:02 · off since 21:14".
From the phone's own signals (Android's shutdown broadcast and the boot), never from a server-side silence timer.

**Non-goals.** A "lost contact" alarm (the phone in a tunnel, the server down); quiet hours for these pushes (a
possible follow-up for every normal kind at once); guessing a reason beyond the battery reading; telling the parent
before the first unlock after a boot (the anchor and tsnet aren't direct-boot-aware - §4.4).

## 1. Decisions

The user's words (2026-10-19): "Kunne tenke meg at vi la til at man får notification når tlf slås av/på? ... om det er
notification når den mister kontakt med server (og ideelt sett grunnen til dette da, om det er fordi den er slått av
eller hva)", then, choosing: "Av og på varsel basert på oppstart/omstart og action_shutdown høres bra ut!"

1. **Two kinds, `phone_off` and `phone_on`** (given). `phone_off` from `ACTION_SHUTDOWN`, `phone_on` from the first
   process start of a boot. Both stored on the phone first (DE prefs, write-ahead like the emergency-call record),
   cleared only after the server answered 2xx.
2. **A small endpoint of its own, `POST /api/devices/power`, not a field in the status report** (architect's call; the
   brief preferred the status report). The status report is the whole sync's body (installed apps, location, policy
   state...) and inserts the `device_status` row the Devices page reads as "the newest": it can't be built inside a
   shutdown's few seconds, and a stripped one would blank the page. One small body serves both moments - alone at
   shutdown, and in every sync after the status report while a record waits (as `ParentPinEvents.upload` and
   `CrashReports.upload` do). The status body and its two pinned files don't change at all; the new body gets its
   own pinned file (§2).
3. **One push per boot.** `unseen_boot` (design 24b/35) keeps its meaning: boots in which Vibb One **never ran** (safe
   mode, a start cut short - `BOOT_COUNT` jumped by more than one). `phone_on` is the boot in which it runs. They are
   different boots by definition, so never two pushes for one boot. A report after a safe-mode boot gives both: the
   warning for the unseen boot, `phone_on` for this one.
4. **A restart is one push** (default; open question 1): a shutdown with `reboot = true` stores `phone_off` without a
   push, and the boot's `phone_on` says "Anna restarted · 07:02". A power-off pushes at once ("was turned off"), and
   the start pushes "was turned on".
5. **A late shutdown is folded into the start.** When the shutdown POST didn't get through, the record arrives with
   the next boot's: `phone_off` is stored (inbox, marked "told at the next start") but not pushed, and the `phone_on`
   push carries it ("off since 21:14, battery was 3 %"). Still one push per boot.
6. **The reason in the words, from the battery only**: level <= 3 % and not charging -> "probably flat" (Android
   shuts down at 0 %, `BatteryService.shutdownIfNoPowerLocked`; the reading at the broadcast is 0-2 %, some OEMs a
   little higher). Otherwise the level and charging state, nothing more. Titles name the phone and what happened
   (design 35's privacy rule: nothing phone-supplied as text).
7. **Switches `push_phone_off`, `push_phone_on`**, default on; urgency normal (summary-foldable), TTL 43200 like
   `unseen_boot`. Capability `power_v1`; an old launcher never calls the endpoint, so nothing new happens.

## 2. Wire

`POST /api/devices/power` (bearer, as `/status`; body limit 2 KiB) -> 204. Both parts optional, at least one:

```json
{"off": {"at_ms": 1760900040000, "boot_count": 41, "battery": {"level": 3, "charging": false, "low": true},
         "reboot": false},
 "on":  {"at_ms": 1760936520000, "boot_count": 42, "battery": {"level": 11, "charging": true, "low": false}}}
```

- `battery` is design 35's `BatteryReport`, read by `lenient_battery` (a bad one drops the reading, never the event).
- `reboot`: `true` restart, `false` power-off, absent = unknown (§4.2).
- `at_ms`: the shutdown instant / the boot instant (wall clock minus `elapsedRealtime`, §4.3).
- Pinned: `server/testdata/power_report.json` (exactly the body above, `on` and `off`), byte-identical in
  `launcher/app/src/test/resources/`; the launcher's `PowerEventsTest` encodes exactly it, the server's
  `power::a_launcher_report_raises_both` posts it, both compare the copies (root CLAUDE.md gains the line; `launcher.yml`
  and `server-ci.yml` gain the paths). `time_status_report.json` and `dns_upstream_status.json` are untouched.
  `power_v1` in `STATUS_CAPABILITIES` (`SyncScheduleTest`); `policy_json_keys_snapshot` is untouched.
- Versions: new launcher + old server -> 404, the launcher drops the record (nothing would ever take it); old
  launcher + new server -> no calls, no events.

## 3. Server

### 3.1 Migration `0080_phone_power.sql`

`ALTER TABLE notification_settings ADD COLUMN push_phone_off INTEGER NOT NULL DEFAULT 1;` and the same for
`push_phone_on`. Nothing else: the events are `parent_events` rows. The migrations-in-order test gains 0080.

### 3.2 `parent_events`

- `Kind::PhoneOff` (`phone_off`, switch `push_phone_off` "Phone turned off"), `Kind::PhoneOn` (`phone_on`,
  `push_phone_on` "Phone turned on"); not urgent, TTL 43200. Notify Me About lists them by `Kind::switch()` (no
  template change). `Kind::ALL` gains both, after `UnseenBoot`.
- `NewEvent.push: bool` (true from `Phone::event`): `raise` calls `push::queue` only when set. Decisions 4 and 5 are
  the only `false`s.
- Words (`Phone::phone_off`, `Phone::phone_on`; `battery_words(&BatteryReport) -> String` pure; `FLAT_LEVEL = 3`):

| case | title | summary | push |
|---|---|---|---|
| off, power-off, flat | "Anna was turned off" | "21:14 · battery 2 %, probably flat" | yes |
| off, power-off | "Anna was turned off" | "21:14 · battery 64 %, not charging" | yes |
| off, reboot | "Anna is restarting" | "21:14 · battery 64 %, charging" | no (decision 4) |
| off, unknown | "Anna was turned off" | as power-off | yes |
| off, late (an `on` of a later boot in the same body) | as above | + " · told at the next start" | no (decision 5) |
| on, after a reboot (`off.reboot`, or unknown and the gap < 3 min) | "Anna restarted" | "07:02 · battery 64 %" | yes |
| on, its off in the body (`boot_count` + 1) | "Anna was turned on" | "07:02 · off since 21:14, battery was 3 %" | yes |
| on, no off known | "Anna was turned on" | "07:02 · no shutdown seen · last report 21:10" | yes |

  Times by `Phone::at` ("yesterday 21:14", "12 Oct 21:14"). No battery -> the clause is left out. "Last report" is
  the newest `device_status.reported_at` before the boot instant (one query; left out when none) - it covers a battery
  pulled, a hard reset, a crash, a shutdown before the first unlock (no receiver then), `adb emu kill`.
- Pairing (pure `power_events(&PowerReport, last_report_before, &Phone) -> Vec<NewEvent>`): an `off` pairs with the
  `on` only when `on.boot_count == off.boot_count + 1` and `off.at_ms <= on.at_ms`; otherwise both are told alone
  (boots in between are `unseen_boot`'s). An instant more than 5 min in the server's future is dropped (that part
  only, logged); an `off` older than 30 days too.
- Dedup keys: `phone_off` = the instant (RFC 3339 with ms, UTC, as `emergency_call`); `phone_on` =
  `boot:<boot_count>` (the boot instant is recomputed at each upload and may move by milliseconds or with NTP, the
  count doesn't). A repeated body gives no new rows; the `on`'s words still pair with a known `off`.
- `happened_at` = the instant; target `/devices/{id}`.

### 3.3 The handler

`device_api::power` (`src/handlers/device_api.rs`, beside `status`; `models::PowerReport` with `Option<PowerOff>`,
`Option<PowerOn>`): `Phone::load`, the "last report before" query only when an `on` has no paired `off`, then the
events raised in one transaction (`raise` for each, the `off` first), commit, `state.push.wake()` if any row was new.
A body with neither part -> 400. No `device_status` row, no security log line.

### 3.4 The device page (optional, cheap)

The summary's header, under the battery line: "Off since 21:14 · battery was 3 %" while the phone's newest
`phone_off` is newer than its newest `phone_on` (one query on `parent_events`, `(device_id, kind)`; the words are the
stored summary's). Gone with the next start. Not on the Devices list.

## 4. Launcher

### 4.1 Storage: DE prefs `power_events` (`commit()`)

Keys: `off_at_ms`, `off_boot_count`, `off_level`, `off_charging`, `off_low`, `off_reboot` (absent = unknown);
`on_boot_count`, `on_at_ms`, `on_level`, `on_charging`, `on_low`. Device-protected so the boot record can be written
in direct boot; nothing about the user or the PIN in them. At most one `off` and one `on`: a newer one overwrites an
unsent older one (the inbox then misses it; said in the class's doc).

### 4.2 The shutdown (`server/PowerEvents.kt`)

- `PowerEvents.watch(app)` from `Application.initRest`, beside `BatteryStatus.watch`: one runtime receiver
  (`RECEIVER_NOT_EXPORTED`) for `ACTION_SHUTDOWN`. ShutdownThread sends it `FLAG_RECEIVER_REGISTERED_ONLY`, so a
  manifest receiver never gets it; `ACTION_REBOOT` is a request to the system, not a notice of one - not registered.
- `onReceive`, main thread: read the battery (`BatteryStatus.report`), `reboot` (below), `BootClock.bootCount()`,
  commit the `off` record (write-ahead), then `goAsync()` and on a thread: `api.sendPower(off + any unsent on)` with a
  **4 s call timeout**, clear on 2xx, `finish()` in a `finally`. ShutdownThread waits at most 10 s
  (`MAX_BROADCAST_TIME`) for the whole ordered broadcast, shared by every receiver; the radios go off only after it,
  so Wi-Fi/mobile and our tsnet node are still up. 4 s leaves room for others and never delays a shutdown past 10 s.
- `reboot`: `true` with `EXTRA_SHUTDOWN_USERSPACE_ONLY`; else the first character of `sys.shutdown.requested`
  (ShutdownThread sets "1<reason>" for a reboot, "0<reason>" for a power-off before it broadcasts), read through
  `SystemProperties` by reflection, caught; unreadable (SELinux, hidden API) -> absent. Pure
  `rebootOf(userspaceOnly: Boolean, requested: String?) -> Boolean?`. Device check: is it readable on the Jelly Star?
  If not, the server's 3-minute gap rule decides the restart wording.

### 4.3 The boot

- In `Application.onCreate` right after `BootWitness.record` (direct boot too): `PowerEvents.recordBoot(context)` -
  when `BootWitness.newBoot` and the witness had a `lastBoot` before (not the build's first start: an install or the
  first `power_v1` start announces nothing), commit the `on` record: `boot_count`, `at_ms` = now - `elapsedRealtime`,
  the battery. Pure `bootRecord(newBoot, hadLastBoot, bootCount, nowMs, elapsedMs, battery) -> PowerOn?`.
- The instant sent is recomputed at upload while `BootClock.bootCount() == on_boot_count` (NTP may have moved the
  clock since the record), else the stored one. Never throws (the call path runs in this process).

### 4.4 The upload

`PowerEvents.upload(context, api)` in `performMdmSync` after `ParentPinEvents.upload`: when a record waits, one POST;
2xx -> clear (`clearSent`: only the keys whose `at_ms`/`boot_count` still equal what was sent - a shutdown racing a
sync keeps its newer record); 404 -> clear (an old server); else keep for the next sync. Records older than 7 days are
dropped unsent (pure `stale`). The process start's sync (the anchor syncs at start) carries the boot - after the first
unlock only, so a phone left at the PIN screen before its first unlock tells the parent when it is unlocked.

## 5. Tests (lean)

- Server: `parent_events::tests`' words table gains §3.2's rows (flat at 3, not at 4; charging; reboot; late; pairing
  by `boot_count`, a gap of 2 min vs 4 min with `reboot` unknown; no battery; no last report); `src/tests/power.rs`:
  the contract body (both copies equal) -> two rows, one queued delivery (the late off unpushed), the same body again ->
  nothing new; an `off` alone -> pushed; a reboot `off` -> stored, no delivery; the switch off -> stored, no delivery;
  a bad battery -> event without the clause; neither part -> 400; another phone's token can't post for this one; the
  device page's "Off since" line and its absence after an `on`; `migrations_*_in_order` gains 0080.
- Launcher: `PowerEventsTest` - `rebootOf`, `bootRecord` (not new / no last boot -> null), `clearSent`, `stale`, the
  encoding equals `power_report.json`, the receiver's registration and action and `finish()` in a `finally` (source
  scan, as `BatteryReportTest`), `recordBoot` called after `BootWitness.record`; `power_v1` in `SyncScheduleTest`.

## 6. Emulator checks (`docs/testing/emulator.md` §6p)

With the PWA subscribed (§6n's setup) and the debug launcher provisioned:
1. `adb emu power ac off`, `adb emu power capacity 2`, then `adb shell reboot -p`: logcat shows the receiver and the
   POST within 4 s; the push "Anna was turned off · <time> · battery 2 %, probably flat". Start the AVD again: after the
   unlock "Anna was turned on · <time> · off since <time>, battery was 2 %".
2. `adb emu power capacity 60`, `adb reboot`: no push at the shutdown (inbox row "Anna is restarting"), after the
   unlock "Anna restarted · <time> · battery 60 %". If `sys.shutdown.requested` reads as unknown, the same by the gap.
3. The server stopped, `adb shell reboot -p`, the server started, the AVD started: one push "Anna was turned on · <time> ·
   off since …"; the inbox has the off row "… · told at the next start", unpushed.
4. `adb emu kill` (no shutdown broadcast): the parent sees nothing until the next start, then "Anna was turned on ·
   <time> · no shutdown seen · last report <time>".
5. The device page shows "Off since …" between 1's shutdown and start, not after.

## 7. Open questions for the user

1. **A restart: one push or two?** Proposed: one ("Anna restarted", after the start), the shutdown's row only in the
   inbox. The other way: "Anna is restarting" at once, then "Anna restarted" a minute later.
2. **The device page's "Off since 21:14 · battery was 3 %" line (§3.4)**: in this step (proposed, small), or later?

## 8. Steps

One step, both directories in one commit (root CLAUDE.md: the API and its contract file together): migration 0080,
`Kind`s, words, `device_api::power`, the route, `power_report.json` in both places, `PowerEvents.kt` (receiver, boot
record, upload), `power_v1`, the tests, §6p, both CLAUDE.md files, the root CLAUDE.md's contract line and the CI paths.
Device checks owed after: the Jelly Star - `ACTION_SHUTDOWN` reaches us on a power-off, a restart and a flat battery;
whether `sys.shutdown.requested` is readable; the level the phone reports at its own shutdown.
