# 38 - The phone turned off and on: notifications to the parent (buildable spec)

Asked 2026-10-19. Builds on design 35 (`parent_events`, Web Push, Notify Me About) and replaces the "offline" alert
dropped there on 2026-10-18 (qa-35-design L3, design 35 §2.3). One step, both directories. Revised after design QA
(`docs/design/qa-38-design.md`, §8) and the coordinator's decisions of 2026-10-19 (§1.4, §1.8).

## 0. Goal and non-goals

**Goal.** The parent hears when the kid's phone goes off and when it starts again, and why, as far as the phone can
tell: "Anna was turned off · 21:14 · battery 2 %, probably flat" / "Anna was turned on · 07:02 · off since 21:14,
battery was 2 %" / "Anna restarted · 07:02". From the phone's own signals (Android's shutdown broadcast and the
boot), never from a server-side silence timer.

**Non-goals.** A "lost contact" alarm; quiet hours (a possible follow-up for every normal kind); any other reason.

## 1. Decisions

The user's words (2026-10-19): "Kunne tenke meg at vi la til at man får notification når tlf slås av/på? ... om det er
notification når den mister kontakt med server (og ideelt sett grunnen til dette da, om det er fordi den er slått av
eller hva)", then, choosing: "Av og på varsel basert på oppstart/omstart og action_shutdown høres bra ut!"

1. **Two kinds, `phone_off` and `phone_on`** (given). `phone_off` from `ACTION_SHUTDOWN`, `phone_on` from the first
   process start of a boot. Both written on the phone first (DE prefs, write-ahead like the emergency-call record).
2. **A small endpoint of its own, `POST /api/devices/power`, not a field in the status report** (architect's call,
   confirmed by the QA): a status report is the whole `device_status` row the pages and the next report compare
   with; a stripped one built inside a shutdown would blank them and raise false events. The status pins stay.
3. **One push per boot.** `unseen_boot` (design 24b/35) keeps its meaning: boots in which Vibb One **never ran**
   (`BOOT_COUNT` jumped by more than one, `witnessBoot` adds `jump - 1`). `phone_on` is the boot in which it runs.
   Different boots by definition.
4. **A restart is one push, "Anna restarted"** (decided 2026-10-19, §7). The restart flag is usually unknown on the
   phone (QA M1), so the server **holds the `phone_off` push for 5 minutes** (`OFF_HOLD`) and drops it when the next boot's `on`
   comes within the hold (§3.3).
5. **The phone keeps a sent `off` until a later boot's `on` is accepted** (QA H1), and sends it with that `on`, so
   the server pairs them in one body; the repeat costs nothing (dedup). The server stays pure over the body.
6. **The reason in the words** (§3.2): the battery at the shutdown ("probably flat" at <= 3 % not charging), the
   shutdown reason when the phone could read it (`battery`, `thermal-shutdown`), and for a start with no shutdown seen
   the last report's battery ("probably flat" at <= 10 % not charging, QA M5). Nothing more is guessed. Titles name
   the phone and what happened (design 35's privacy rule: the phone's name as stored, no phone-supplied text).
7. **Switches `push_phone_off`, `push_phone_on`**, default on; urgency normal (summary-foldable), TTL 43200 like
   `unseen_boot`. Capability `power_v1`; an old launcher never calls the endpoint, so nothing new happens.
8. **The device page** shows "Off since 21:14 · battery was 3 %" between a shutdown and the next start (decided
   2026-10-19, in this step).

## 2. Wire

`POST /api/devices/power` (bearer, as `/status`; body limit 2 KiB) -> 204. Both parts optional, at least one (else
400):

```json
{"off": {"at_ms": 1760900040000, "boot_count": 41, "battery": {"level": 3, "charging": false, "low": true},
         "reboot": false, "reason": "userrequested"},
 "on":  {"at_ms": 1760936520000, "boot_count": 42, "battery": {"level": 11, "charging": true, "low": false}}}
```

- `battery`: design 35's `BatteryReport` through `lenient_battery` (a bad one drops the reading, not the event).
- `reboot`: `true` restart, `false` power-off, absent = unknown (the usual case, §4.2). `reason`: the text after the
  digit of `sys.shutdown.requested`, absent when unreadable; the server keeps only known words (`battery`,
  `thermal-shutdown`, `thermal`, `userrequested`), anything else is ignored, never shown.
- `at_ms`: the shutdown instant / the boot instant (wall clock minus `elapsedRealtime`, §4.3).
- Pinned: `server/testdata/power_report.json` (exactly the body above), byte-identical in
  `launcher/app/src/test/resources/`. The launcher's `PowerEventsTest` encodes exactly it; the server's
  `power::a_launcher_report_raises_both` posts it; both compare the copies. CI: `launcher.yml`'s build filter gains
  `server/testdata/power_report.json`, `server-ci.yml`'s gains the launcher's copy; `music.yml` doesn't (the music app
  doesn't read it). The root `CLAUDE.md` gains the contract line and both CI file lists, as for
  `dns_upstream_status.json`. `time_status_report.json`, `dns_upstream_status.json` and `policy_json_keys_snapshot`
  are untouched; `power_v1` joins `STATUS_CAPABILITIES` (`SyncScheduleTest`).
- Versions: old launcher + new server -> no calls, no events; new launcher + old server -> 404, kept until stale (§4.4).

## 3. Server

### 3.1 Migration `0080_phone_power.sql`

`ALTER TABLE notification_settings ADD COLUMN push_phone_off INTEGER NOT NULL DEFAULT 1;` and the same for
`push_phone_on`. Nothing else. `migrations_73_to_79_in_order` becomes `migrations_73_to_80_in_order`.

### 3.2 `parent_events`: kinds and words

- `Kind::PhoneOff` (`phone_off`, switch `push_phone_off` "Phone turned off"), `Kind::PhoneOn` (`phone_on`,
  `push_phone_on` "Phone turned on"); not urgent, TTL 43200. Notify Me About lists them by `Kind::switch()` (no
  template change). `Kind::ALL` gains both after `UnseenBoot`.
- `NewEvent.push: Push` (`Now` from `Phone::event`, `Held(until)`, `No`); `raise` passes it to `push::queue`, which
  gains a `not_before: Option<DateTime>` written to `push_deliveries.next_try_at` (`send_due` already skips a
  queued row until then; the TTL counts from `created_at`, so 5 min of 12 h is nothing).
- Words (`Phone::phone_off`, `Phone::phone_on`; pure `battery_words`, `off_reason_words`; `FLAT_LEVEL = 3`,
  `LAST_REPORT_FLAT_LEVEL = 10`; times by `Phone::at`, "yesterday 21:14", "12 Oct 21:14"; a missing battery leaves its
  clause out):

| case | title | summary | push |
|---|---|---|---|
| off, flat | "Anna was turned off" | "21:14 · battery 2 %, probably flat" | held 5 min |
| off, reason `battery` | "Anna shut down: battery flat" | "21:14 · battery 1 %" | held 5 min |
| off, reason `thermal*` | "Anna shut down: too hot" | "21:14 · battery 64 %" | held 5 min |
| off, other | "Anna was turned off" | "21:14 · battery 64 %, not charging" | held 5 min |
| off, `reboot: true` | "Anna is restarting" | "21:14 · battery 64 %, charging" | no |
| off, late (new row in a body with a later `on`) | as above | + " · told at the next start" | no |
| on, restart (§3.3) | "Anna restarted" | "07:02 · battery 64 %" | now |
| on, an off in the body (`off.boot_count < on.boot_count`) | "Anna was turned on" | "07:02 · off since 21:14, battery was 3 %" | now |
| on, no off | "Anna was turned on" | "07:02 · no shutdown seen · last report 21:10, battery 5 %, probably flat" | now |

  "Turned off" is said only for a `phone_off`, which comes only from ShutdownThread's broadcast (QA L6); every
  no-broadcast end (a battery cut by the charger driver, a hard reset, a crash, `adb emu kill`, `adb reboot`) is the
  last row. "Last report" = the newest `device_status` row before the boot instant: `reported_at`,
  `battery_level`, `battery_charging` (one query, only for that row; the clause is left out when there is none).
- Dedup keys: `phone_off` = the instant (RFC 3339 with ms, UTC, as `emergency_call`); `phone_on` =
  `boot:<devices.enrolled_at>:<boot_count>` (QA M4: a factory reset restarts `BOOT_COUNT`, a re-enrollment changes
  `enrolled_at`; the boot instant itself moves with NTP, the count doesn't). `happened_at` = the instant; target
  `/devices/{id}`.

### 3.3 The handler and the restart hold

`device_api::power` (beside `status`; `models::PowerReport { off: Option<PowerOff>, on: Option<PowerOn> }`):
`Phone::load`, then pure `power_events(&PowerReport, last_report, &Phone) -> PowerPlan { events, drop_held_off }`,
applied in one transaction, then `state.push.wake()` if a row or a delivery changed. Drops: an instant more than
5 min in the server's future (that part only, logged), an `off` older than 30 days, an `off` with
`off.at_ms > on.at_ms` (the `on` is then told alone).

The state machine of one shutdown and the start after it (gap = `on.at_ms - off.at_ms`, `RESTART_GAP` = 3 min
between the phone's two instants; `OFF_HOLD` = 5 min on the server's clock from the `off`'s arrival - separate, since
the restart's `on` arrives only after boot, unlock, Wi-Fi or mobile data, tsnet's cold connect and a sync, typically
1.5-2.5 min, QA N2; if the Jelly Star check in §6 times it above the hold, raise the hold, not the gap):

1. **`off` alone arrives** (the shutdown POST got through): a new `phone_off` row; `reboot: true` -> `Push::No`;
   else `Push::Held(arrival + OFF_HOLD)`.
2. **No `on` within the hold**: the sender sends the held delivery at `next_try_at`: "Anna was turned off".
3. **`off` + `on` arrive** (the next boot's upload, with the kept or the unsent `off`), paired when
   `off.boot_count < on.boot_count`:
   - `off.boot_count + 1 == on.boot_count` and (`reboot: true`, or `reboot` unknown and gap <= `RESTART_GAP`):
     **restart**. The `on` is "Anna restarted"; the `off`'s still-`queued` deliveries are `dropped` with reason
     `restart` (`UPDATE ... WHERE event_id = <the off's row> AND status = 'queued'`); a new `off` row (it never got
     through before) is `Push::No`. If the held push already went out (the boot's upload came after the hold), the
     parent has "turned off" and then "restarted" - two pushes, only in that edge. The race with a sender pass that
     read the held row as due just before the drop commits (QA N4) is closed by the send: `send_one` claims the row
     with `UPDATE ... SET attempts = attempts + 1 WHERE id = ? AND status = 'queued'` before the request and skips it
     when that changed nothing. In the inbox the off row shows no delivery line: `delivery_line` treats deliveries
     `dropped` with reason `restart` as none (returns `None`, not "Not sent"; QA N3); during the hold it reads
     "Sending".
   - otherwise: **turned on with its off**, "off since 21:14, battery was 3 %"; a new `off` row is late, `Push::No`;
     an already-known one is untouched (dedup). A boot between the two (`boot_count` + 2 or more) is `unseen_boot`'s
     push from the status report (QA L1).
4. **`on` alone**: "no shutdown seen" (§3.2's last row).

A resent body gives no new rows and pairs the same way. No `device_status` row, no security log line.

### 3.4 The device page

The summary's header, under the battery line: "Off since 21:14 · battery was 3 %" while the phone's newest
`phone_off` has a later `happened_at` than its newest `phone_on` (one query on `parent_events` by `(device_id, kind)`;
the time and battery from the off's stored summary words, `Phone::at` for the time). Gone with the next start. Not
on the Devices list.

## 4. Launcher

### 4.1 Storage: DE prefs `power_events`

Keys: `off_at_ms`, `off_boot_count`, `off_level`, `off_charging`, `off_low`, `off_reboot`, `off_reason` (absent =
unknown), `off_sent` (the server took it); `on_boot_count`, `on_at_ms`, `on_level`, `on_charging`, `on_low`.
Device-protected so the boot record is written in direct boot; nothing about the user or the PIN. At most one `off`
and one `on`: a new one replaces the old (an unsent old one is then lost; said in the class's doc). Every
read-compare-write goes under one lock (`PowerEvents.lock`) and one `commit()` (QA L3: the shutdown receiver on the
main thread races the sync's thread).

### 4.2 The shutdown: one receiver for the process

- `BootCover`'s existing `ACTION_SHUTDOWN` receiver (registered from `PinLockRuntime.init`, `RECEIVER_NOT_EXPORTED`;
  ShutdownThread sends it registered-only, so no manifest receiver can get it) becomes the process's one shutdown
  receiver (QA M3). In order, on the main thread: `KeyguardCover.onShutdown` and the boot cover's synchronous
  component writes, exactly as today; then `PowerEvents.onShutdown` - each part in its own `try`/`catch`, so a throw
  in one never skips the other (QA N5): read the battery (`BatteryStatus.report`),
  `rebootOf`, `BootClock.bootCount()`, `commit()` the `off` record (write-ahead, `off_sent = false`). Only then
  `goAsync()` and the POST on a dedicated thread (`PowerEvents`' single-thread executor), `finish()` in a `finally`.
- The POST uses a client built in advance (`PowerEvents.prepare`: `createMdmApi`'s OkHttp with `callTimeout(3 s)`),
  so nothing is built inside the window. `createMdmApi` reads `TsnetClient.proxy()` once, when built, and tsnet
  connects after `initRest` (Home's first `onResume`, a sync's `connectFromPreferences`), so the prepared client is
  keyed by `(baseUrl, token, proxy address)` and rebuilt off the main thread whenever one differs: from `initRest`,
  after each sync's `connectFromPreferences`, and from `TsnetClient` when the proxy comes up (QA N1). The receiver
  takes whatever is current. `goAsync` holds the ordered broadcast at most ~3 s; we
  are its last receiver in our process, so nothing of ours waits behind it, and ShutdownThread's shared 10 s
  (`MAX_BROADCAST_TIME`) keeps room for others. The process is alive (the anchor's foreground service), so Android
  14's queueing for cached apps doesn't apply - the boot cover's assumption too.
- The network (QA L4): the radios go off after the broadcast; tsnet's SOCKS5 proxy (or the VPN's DNS filter without
  tsnet) is still up; a WireGuard handshake plus TLS fits in 3 s on a normal link; the record covers the rest.
- `rebootOf(userspaceOnly, requested) -> Boolean?`: `true` with `EXTRA_SHUTDOWN_USERSPACE_ONLY`; `"1…"` -> true,
  `"0…"` -> false from `sys.shutdown.requested` (read by reflection through `SystemProperties`, caught); anything else,
  unreadable included -> `null`, never `false`. Device check: expected unreadable on the Jelly Star (hidden API, its
  SELinux label); then the server's hold does the work.

### 4.3 The boot

- `BootWitness.record` exposes `hadLastBoot` (what it had before overwriting `last_boot`, QA L2). Right after it in
  `Application.onCreate`, after the `:bootcover` early return (main process only), in direct boot too:
  `PowerEvents.recordBoot(context)` - when `BootWitness.newBoot`, `hadLastBoot` and `bootCount >= 0` (QA M4: -1 is
  "new" at every start), commit the `on` record: `boot_count`, `at_ms` = now - `elapsedRealtime`, the battery. Pure
  `bootRecord(newBoot, hadLastBoot, bootCount, nowMs, elapsedMs, battery) -> PowerOn?`. Never throws.
- The instant sent is recomputed at upload while `BootClock.bootCount() == on_boot_count` (NTP may have moved the
  clock since), else the stored one.

### 4.4 The upload

`PowerEvents.upload(context, api)` in `performMdmSync` after `ParentPinEvents.upload`: when an unsent `off` or an
`on` waits, one POST with the `on` and any kept `off` (sent or not). The answer by `pinEventsLeaveQueue`, reused as is
(QA L5): 2xx -> mark the sent `off` `off_sent = true`; when the body had an `on`, clear the `on` and any `off` of an
earlier boot (the pair is told; `clearSent` compares `at_ms`/`boot_count` so a newer record written meanwhile stays).
401/404/408/429, 5xx, no network -> keep. Another 4xx -> drop what was sent (the body is wrong; retrying won't fix it).
A kept `off` (sent or not) and an `on` older than 7 days are dropped unsent (pure `stale`) - an old server's 404 ends
there too.

**When the start's report lands** (QA M6): a managed phone has no Android credential since step 10, so CE storage
unlocks at boot and `initRest` (anchor, tsnet, the start's sync) runs at once, behind Vibb's own PIN screen. Only a
phone that still has an Android credential (`lock_state.inactive = android_credential`) waits for its unlock; the
upload stays in the unlocked process (tsnet never runs before unlock). If the start's sync finds no network yet
(Wi-Fi joining, tsnet connecting), the stream's first open requests the next sync (`"sse_open"`), so "turned on"
comes within seconds of the phone reaching the server; the 30-min backstop is only the last resort.

## 5. Tests (lean)

- Server: `parent_events::tests`' words table gains §3.2's rows (flat at 3 not at 4; the last report's flat at 10 not
  at 11; the reasons; an unknown reason ignored; a missing battery). `src/tests/power.rs`: the contract body (both
  copies equal) -> two rows, the `on` pushed; QA H1's sequence: `off` alone -> one delivery with `next_try_at` = +5 min,
  `send_due` at +4 min sends nothing; `off` + `on` 90 s later -> the delivery `dropped`/`restart`, the `on` "restarted";
  the same with the `on` 10 min later -> `send_due` at +5 min sends "turned off", then "turned on · off since …";
  `reboot: true` -> no delivery, then "restarted"; a resent body -> nothing new; the switch off -> stored, no
  delivery; `on` alone -> the last report's clause; the `on` key after a re-enrollment (new `enrolled_at`) -> a new
  row; neither part -> 400; the device page's "Off since" by `happened_at`, gone after an `on`; the migrations test;
  `handlers::notifications::tests`: a `restart`-dropped delivery gives no delivery line; a claimed row isn't sent twice.
- Launcher: `PowerEventsTest` - `rebootOf` (null, never false, for unknown), `bootRecord` (not new, no last boot,
  count -1 -> null), `clearSent` (keeps a newer record), `stale`, the prepared client rebuilt when the proxy changes, the answer table through `pinEventsLeaveQueue`, the
  encoding equals `power_report.json`; one source scan: the process registers exactly one `ACTION_SHUTDOWN` receiver,
  `PowerEvents.onShutdown` after the boot cover's writes, each in its own `catch`, `finish()` in a `finally`; `power_v1` in `SyncScheduleTest`.

## 6. Emulator checks (`docs/testing/emulator.md` §6p)

`adb reboot` and `adb shell reboot -p` go through init's `sys.powerctl`, not ShutdownThread: no `ACTION_SHUTDOWN`
(QA M2, as `BootCover`'s doc says). Use `adb shell svc power shutdown` / `svc power reboot`, or the power menu. Setup: §6n.
1. `adb emu power ac off`, `adb emu power capacity 2`, `adb shell svc power shutdown`: logcat shows the receiver and
   the POST's 204 (with tsnet on - the emulator loop's normal setup, QA N1); 5 min later "Anna was turned off · <time> · battery 2 %, probably flat". Start the AVD: "Anna was turned
   on · <time> · off since <time>, battery was 2 %"; the device page's "Off since" line between, gone after.
2. `adb emu power capacity 60`, `adb shell svc power reboot`: no push at the shutdown; after the start one push "Anna
   restarted · <time> · battery 60 %"; the inbox's off row shows no delivery.
3. The server stopped, `svc power shutdown`, the server started, wait > 5 min, the AVD started: one push "Anna was
   turned on · … · off since …"; the inbox's off row "… · told at the next start", unpushed.
4. No shutdown seen: `adb emu kill`, or `adb reboot`. Nothing at once; at the next start "Anna was turned on · <time>
   · no shutdown seen · last report <time>, battery <n> %".
Jelly Star: the power menu's Power off and Restart, a real flat battery (broadcast? level?), the property read, and
Restart timed to the `on`'s arrival on Wi-Fi and on mobile data against `OFF_HOLD` (QA N2).

## 7. Steps

One step, both directories in one commit (root CLAUDE.md: the API and its contract file together): migration 0080,
the `Kind`s, `Push`/`not_before`, the words, `device_api::power` and the route, the device page line,
`power_report.json` in both places, `PowerEvents.kt` (the shutdown part inside `BootCover`'s receiver, the boot
record, the upload), `BootWitness.hadLastBoot`, `power_v1`, the tests, §6p, both CLAUDE.md files, the root CLAUDE.md's
contract line and the CI paths in `launcher.yml` and `server-ci.yml`.

## 8. Revision after design QA (`docs/design/qa-38-design.md`, 2026-10-19)

Every finding taken. H1 the kept `off` (§1.5, §4.4). M1 the hold (5 min since N2), `rebootOf` never `false` for unknown (§3.3,
§4.2). M2 `svc power`, `adb emu kill`/`adb reboot` as "no shutdown seen" (§6). M3 one receiver, writes first, 3 s
`callTimeout` on a prepared client (§4.2). M4 `boot:<enrolled_at>:<count>`, nothing at -1 (§3.2, §4.3). M5 the last
report's battery (§3.2). M6 the unlock text, `sse_open` as the retry (§4.4). L1 any earlier boot's `off` pairs
(§3.3). L2 `hadLastBoot`, main process only (§4.3). L3 one lock, one `commit()` (§4.1). L4 §4.2's network facts. L5
`pinEventsLeaveQueue` as is (§4.4). L6 "turned off" only from the broadcast, reasons worded (§3.2). L7 CI and root
CLAUDE.md (§2). L8 §5. Not taken from the coordinator's brief: titles keep design 35's form, the phone's name as
stored ("Anna restarted", not "Anna's phone restarted"), since the name may already read "Anna's phone".

Revision check (`qa-38-design.md`, "Revision check (15d356bb)"), all taken: N1 the prepared client rebuilt on a
changed base URL, token or tsnet proxy; check 1 with tsnet on (§4.2, §6). N2 `OFF_HOLD` 5 min, apart from the 3-min
`RESTART_GAP`; the Jelly Star timing check (§3.3, §6). N3 no delivery line for a `restart` drop (§3.3, §5). N4 the
send claims the row with a conditional UPDATE (§3.3). N5 the receiver's two parts caught separately (§4.2).

## Implementation status (2026-10-19, branch `phone-power`)

**In, as specified**: migration `0080_phone_power.sql` (the two switches; `migrations_73_to_80_in_order`); the
`Kind`s, `Push`/`not_before`, the words (`battery_words`, `off_reason_words`, `FLAT_LEVEL` 3, `LAST_REPORT_FLAT_LEVEL`
10, `OFF_HOLD` 5 min, `RESTART_GAP` 3 min) and the pure `power_events` in `parent_events.rs`; `device_api::power`
(2 KiB limit, 400/413) and its route; the sender's claim (`push::claim`); `delivery_line` without a `restart` drop;
the device page's "Off since" line; `power_report.json` in both places with both sides' tests and the CI filters; the
launcher's `PowerEvents.kt` (the shutdown inside `BootCover`'s receiver after its writes, each part caught; the
record in DE prefs under one lock; the prepared client keyed by base URL, token and tsnet proxy, rebuilt from
`initRest`, after each sync's `connectFromPreferences` and from `TsnetClient`'s new `proxyListener`; the boot record
after `BootWitness.record` with `hadLastBoot`; the upload with `pinEventsLeaveQueue`), `power_v1`; the tests of §5;
§6p; both CLAUDE.md files and the root one.

**Deviations, and why**:
- **A start after a real shutdown releases the off's held push** (§3.3 said an already-known off is untouched): when a
  power-off and its start both arrive inside the 5-minute hold without being a restart (a phone off for 3-4 minutes,
  or `reboot: false` - the power menu's Power off, then on at once), the untouched hold would push "turned on" first
  and "turned off" a minute later. The handler sets the off's still-queued, never-tried deliveries due now
  (`release_held_off`, `next_try_at = NULL WHERE ... AND attempts = 0`), so the sender's pass sends "turned off" then
  "turned on" (by id). Tested by `power::a_quick_start_after_a_power_off_sends_the_off_first`.
- **The contract test moves the body's instants** (`power::a_launcher_report_raises_both`): the file's `at_ms` are in
  October 2025, so the 30-day rule would drop the `off`; the test sets the `on` to a minute ago and keeps the gap (the
  file itself is exactly §2's body).
- **The late suffix also on a restart's new off row** (" · told at the next start"): that row reaches the server only
  with the next start, as the late row of §3.2's table; it is unpushed either way.
- **An unpaired kept shutdown** (§4.4 left it open): a kept shutdown of the start's own boot or later (a start the
  phone never got through, then a shutdown in the same boot) is not sent with that start - the server would drop it as
  later than the start. The phone then sends only an unsent shutdown, and the start waits for the next boot's to
  replace it (the class doc says an unsent start can be lost). On the server an unpaired pair (off's boot not earlier)
  is told as two alone.
- **The start's record is compared by its boot count** in `clearSent` (§4.4 says `at_ms`/`boot_count`): the instant
  sent is recomputed at upload (§4.3), so it can differ from the stored one; a newer start always has another count.

**Owed to device checks** (§6, `docs/testing/emulator.md` §6p for the emulator part): the Jelly Star's power menu
(Power off, Restart), a real flat battery (is the broadcast sent, at which level), the `sys.shutdown.requested` read
(expected unreadable), and Restart timed to the start's arrival on Wi-Fi and on mobile data against `OFF_HOLD`
(raise the hold, not the gap, if it is slower). Not run on the emulator yet: §6p's four checks.

### Code QA fix round (`docs/review/qa-38-code.md`, 2026-10-19)

Every finding taken, on top of `ad8f4821`:
- **M1** the device page's "Off since" line also needs no `device_status` report newer than the off
  (`datetime(o.happened_at, '+60 seconds') >= MAX(reported_at)` - RFC 3339 against SQLite's `datetime` text, never a
  raw string compare; the minute is slack for the phone's clock and a report racing the shutdown). A re-enrollment, a
  boot count of -1 or a dropped start no longer leaves it up while the phone reports.
  `power::a_report_after_the_shutdown_ends_off_since`.
- **L1** (a) `PowerEvents.update`'s `editor.commit()` pinned, and no `.apply()` in the file; (b) the route test's
  out-of-range level is a whole battery object (`level` 140 with `charging`/`low`), so it reaches the range check;
  (c) the boot cover's synchronous arm and its `KEY_ARMED_AT` commit pinned in `coverAtShutdown` (`PowerEventsTest`).
- **L2** left to the coordinator at the merge, as asked (StatusCapabilities keeps both capabilities, emulator.md keeps
  §6o and §6p, the server section moves to `docs/notes/server/notifications.md`); nothing rebased.
- **Nit 1** no "Off since" for a restart the phone announced (the off's title "… is restarting") nor while the off's
  push is still held (`created_at` within `OFF_HOLD`: a restart's start may still fold it), so the page says no more
  than the pushes (`off_since_line` returns `Option`; `the_off_since_line`, the page test).
- **Nit 2** an `on` older than 30 days is dropped like an `off` (`POWER_MAX_AGE`; the off is then told alone, a body
  with `on.at_ms` 0 raises nothing; `the_power_state_machine`).
- **Nit 3** two shutdowns before a start got through: the newest off is kept and told, and a new `phone_off` row drops
  every older off's still-queued push (`dropped`, reason `superseded`, no delivery line in the inbox) - one "turned off",
  never a stray one for a boot long over (`power::a_newer_shutdown_supersedes_an_older_held_push`, the delivery-line
  table).
