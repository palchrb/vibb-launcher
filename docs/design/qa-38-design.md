# QA 38 (design) - the phone turned off and on

Reviewer: design QA, 2026-10-19. Branch `design-38` at `76d9372a`. I read it against:
- `docs/design/38-phone-off-on.md`; the root `CLAUDE.md` (contract files, CI paths); `server/CLAUDE.md` (design 35
  steps 1-2, the device API, the device edge, "Data model"); `launcher/CLAUDE.md` (direct boot, boot witness, tsnet,
  the boot cover); `docs/design/35-parent-notifications.md`.
- server: `parent_events.rs` (`Kind`, `NewEvent`, `Phone::event`, `raise`, `unseen_boot`), `push.rs` (`queue`, the
  fold, TTL/urgency), `handlers/device_api.rs` (`status`: the `device_status` insert and the one read of the previous
  report), `handlers/notifications.rs` (`delivery_line`), the migrations (0079 is the highest).
- launcher: `BootWitness.kt`, `Application.kt` (`onCreate`, `initRest`), `BatteryStatus.kt`, `ParentPinEvents.kt`,
  `MdmSyncWorker.kt` (the upload order), `StatusCapabilities.kt`, `MdmApi.kt`/`TsnetClient.kt` (the SOCKS5 proxy),
  `KidVpnService.kt` (routes), `lock/BootCover.kt` (the existing `ACTION_SHUTDOWN` receiver), the manifest.
- `.github/workflows/launcher.yml`, `server-ci.yml` (the `dns_upstream_status.json` filters).

No code was built or changed.

**Verdict: approve with the listed changes.** Two kinds, the phone's own signals, a small endpoint of its own, dedup
by instant, one push per boot - right, and the status-report reasoning holds (below). What must change first: the
ordinary off-then-on loses its pairing (H1), the restart flag will usually be unknown and then a restart pushes
twice (M1), the emulator commands never send `ACTION_SHUTDOWN` (M2), and the shutdown receiver must not hold the
ordered broadcast ahead of the boot cover's synchronous writes (M3).

Counts: 1 High, 6 Medium, 8 Low.

## What holds (checked)

- **No stripped status report** (decision 2): `device_api::status` inserts every column of `device_status`, and
  every page reads the newest row. A cut-down report would blank the Devices page and About, and it would also make
  the *next* full report compare against an empty row. That one read of the previous report feeds
  `app_installed` (every app "new"), the foreign-access set ("changed" -> a `foreign_access` push), the emergency-call
  rule and the unseen-boot log line. The separate endpoint is right, and the reasons are stronger than the note says.
- **`unseen_boot` vs `phone_on`** (decision 3): `witnessBoot` adds `jump - 1`, so the boot in which the process runs is
  never counted as unseen. A process that starts late in a boot still runs `BootWitness.record` first thing in
  `onCreate`, and a crash or update restart in the same boot has `newBoot = false`. So one boot never gets both.
- **`NewEvent.push`** is the right seam. The fold, health and `delivery_line` all work from `push_deliveries`, so an
  event with no deliveries isn't counted in a fold, doesn't touch health, and shows no delivery line (`delivery_line`
  returns `None` for no deliveries, not "Not sent"). Dedup and the 90-day retention depend on the row, not on
  deliveries. Default `true` in `Phone::event`; `NewEvent` derives `PartialEq`, so the tests' comparisons pick it up.
- **Migration 0080**: two `NOT NULL DEFAULT 1` columns on the `notification_settings` singleton. `strip_secrets_in`
  only deletes the push tables, and backups are unaffected. Rename the order test (`migrations_73_to_79_in_order` ->
  `_to_80_`).
- **The VPN doesn't touch the POST**: `KidVpnService` routes only `FAKE_DNS_SERVER/32`. Over tsnet, OkHttp hands the
  unresolved host to tsnet's SOCKS5 proxy, so no system DNS is involved either (see L4 for the note's wording).

## High

### H1. A shutdown that got through is forgotten, so the start that follows can't pair with it

**What.** §4.4 clears the `off` on its 2xx. §3.2 pairs only inside one body ("on, its off in the body";
`power_events(&PowerReport, ..)` is pure over the body). In the normal case, where the shutdown POST works, the next
boot's body has only `on`. The server then says "Anna was turned on · no shutdown seen · last report 21:10" after
every ordinary off and on. Decision 4 breaks entirely: a restart's `off` (`reboot = true`) is stored unpushed and
cleared, and the start pushes "turned on · no shutdown seen" instead of "Anna restarted". The 3-minute gap rule can
never apply either, because it needs the `off` instant. Only the failure case (decision 5) gets the intended words.

**Fix.** Keep the sent `off` on the phone (`off_sent = true`) until the `on` of a later boot is answered 2xx, and send
it with every `on` (its dedup key makes the repeat free; a new `off` replaces it; `stale` still drops it after 7
days). The server stays pure. "Late" (decision 5) then means "the `off` row was new in a body that also has a later
`on`", which is the same rule. Add the sequence to `src/tests/power.rs`: `off` alone -> pushed; then `off` + `on`
-> one new row, the `on` pushed "off since …"; and the same with `reboot: true` -> nothing at the shutdown, then
"restarted". (Doing it on the server instead, by looking up the newest `phone_off`, would need `boot_count`, `reboot`
and the battery stored in `parent_events`, which the table doesn't have.)

## Medium

### M1. The restart flag will usually be unknown, and then every restart pushes twice

`sys.shutdown.requested` is set by ShutdownThread before the broadcast ("1<reason>" / "0<reason>"), but reading it
needs `android.os.SystemProperties` (a hidden API) by reflection. I also believe its SELinux label isn't readable by
an untrusted app since the property namespace split in Android 9; I haven't verified that, so it is a device check.
`EXTRA_SHUTDOWN_USERSPACE_ONLY` is true only for a userspace reboot, which is rare. So on the Jelly Star, assume
`reboot` is absent. The table then pushes "Anna was turned off" right away and "Anna restarted" a minute later: the
two pushes open question 1 wants to avoid, on every restart.
**Fix:** when `reboot` isn't `true`, queue the `off` push held back: `push_deliveries.next_try_at` = arrival + 3 min.
The column and `send_due`'s handling already exist; `push::queue` gains a `not_before`. When the `on` of
`boot_count + 1` arrives inside that window, the held delivery is `dropped` (reason `restart`) and the `on` says
"restarted". The cost is a power-off push 3 minutes late, which is acceptable for this kind. Keep the property read as
a bonus: when it reads, `"1"` skips the hold, and the reason after the digit (`userrequested`, `battery`,
`thermal-shutdown`) can sharpen the words (L6). Make `rebootOf(false, null)` return `null`, never `false`.

### M2. `adb shell reboot -p` and `adb reboot` never send `ACTION_SHUTDOWN`

Both go through init's `sys.powerctl`, not ShutdownThread. `lock/BootCover.kt`'s own doc says so ("Neither does `adb
reboot` … test with the power menu or `adb shell svc power reboot`"). As written, §6 checks 1-3 would find no
receiver and no push, and could be read as "the broadcast doesn't reach us". **Fix:** use `adb shell svc power
shutdown` / `svc power reboot` (or the emulator's power menu) for 1-3, and use `adb reboot` as a second "no shutdown
seen" case beside `adb emu kill` in 4. The Jelly Star device checks should use the power menu, a real flat battery,
and "Restart" from the power menu.

### M3. The receiver holds an ordered broadcast that the boot cover's synchronous writes also wait in

`BootCover` already registers an `ACTION_SHUTDOWN` receiver in the same process (from `PinLockRuntime.init`). It runs
`KeyguardCover.onShutdown` and writes the cover's component state *synchronously*, because an OEM shutdown may not
flush it otherwise (qa-16b-code #3). `ACTION_SHUTDOWN` is ordered, so receivers run one after another. A `goAsync()`
of up to 4 s in a receiver delivered first holds every receiver behind it, ours included, inside ShutdownThread's
shared 10 s. Past 10 s, shutdown goes on while the broadcast is still being delivered.
**Fix:** one shutdown receiver for the process. It does `KeyguardCover`/`BootCover`'s synchronous work first, then
commits the `off` record, then `goAsync()` and the POST on a dedicated thread with a 3 s `callTimeout` (not just the
10 s connect/read timeouts `createMdmApi` sets). Build the client ahead of time in `watch()`, so nothing is
constructed inside the window. Say all of this in §4.2 (which thread, `commit()` before the call, `finish()` in a
`finally`). The process is kept alive by the anchor's foreground service, so it isn't cached; Android 14's queueing
of broadcasts for cached apps doesn't apply. That is the boot cover's assumption too.

### M4. `boot:<boot_count>` repeats after a factory reset

After a factory reset, BOOT_COUNT starts again from 1. A phone re-enrolled into the same row (`enroll` reuses it)
then gets `ON CONFLICT DO NOTHING` for every `boot:<n>` it already had in the last 90 days. That means no "turned on"
for weeks. **Fix:** key `boot:<devices.enrolled_at>:<n>` (the server knows the enrollment; re-enrolling changes it).
On the phone, never write a boot record with `bootCount < 0`. `isNewBoot` treats -1 as a new boot at every process
start, which would otherwise send `boot:-1` over and over.

### M5. A flat battery on the Jelly Star may never reach ShutdownThread

Android's own path (`BatteryService.shutdownIfNoPowerLocked` at level 0 -> `ACTION_REQUEST_SHUTDOWN` -> ShutdownThread)
does send the broadcast, and `<= 3 %` covers the gauges that map a few percent to 0. But MediaTek phones also cut
power in the kernel or charger driver when the voltage collapses (under load, in the cold), with no broadcast. Then
the parent gets "no shutdown seen", which is exactly the "why" the user asked for, with no reason given.
**Fix:** the no-shutdown words carry the last report's battery (`device_status.battery_level`/`battery_charging` of
the row the "last report" query already reads): "no shutdown seen · last report 21:10, battery 4 %". At `<= 10 %`
and not charging, add "probably flat". One more column in the query you already have. Device check: what level the
Jelly Star reports at its own shutdown, and whether a flat battery broadcasts at all.

### M6. "Before the first unlock" doesn't describe these phones; say what carries a failed start sync

Since step 10, a managed phone has no Android credential, so CE storage unlocks by itself at boot. Vibb's PIN screen
comes after that, and the start's sync runs at once. It does not wait until "the PIN screen is unlocked" (§4.4, §0).
Only a phone that still has an Android credential (`lock_state.inactive = android_credential`) waits for its unlock.
Keep the upload in the unlocked process; sending from DE storage at boot would need tsnet before the unlock, which
the BFU rule forbids (`launcher/CLAUDE.md`, "never start … tsnet before unlock"). Recording in DE at boot, as
planned, is right. The real delay is the network: Wi-Fi joins and tsnet connects some seconds after the boot, and if
the start's sync fails, the note must name the next trigger (the stream's first connect, a network callback, or the
30-minute backstop). Otherwise "turned on" can come 30 minutes late.

## Low

- **L1. An `off` with a skipped boot.** `off` of 41 with `on` of 43 (42 was safe mode): the `on` says "no shutdown
  seen" even though an `off` is in the body. Say "off since 21:14" whenever `off.boot_count < on.boot_count`; the
  boot in between is `unseen_boot`'s push.
- **L2. `hadLastBoot`.** `BootWitness.record` overwrites `last_boot` before `recordBoot` runs, so the witness has to
  expose what it had (`BootWitness.hadLastBoot`, set in `record`). `recordBoot` goes after `onCreate`'s early return
  for `:bootcover`, and only in the main process.
- **L3. `clearSent` races** the shutdown receiver (main thread) against the sync's thread: read-compare-clear under
  one lock, in one `commit()`.
- **L4. Networking facts** for §4.2: the VPN carries only DNS to its fake address. Over tsnet, the host name goes to
  tsnet's SOCKS5 proxy; without tsnet (a public address), the name goes through the VPN's DNS filter, which is still
  up during the broadcast. A WireGuard handshake after an idle path, plus TCP and TLS over the netstack, fits in 3 s
  on a normal link, and the write-ahead record covers the rest.
- **L5. 4xx handling** says it copies `ParentPinEvents` but doesn't: that pattern keeps on 404 and drops on other
  4xx (`pinEventsLeaveQueue`), while §4.4 drops on 404 and keeps 400/413 for 7 days. Reuse `pinEventsLeaveQueue`
  as is (an old server's 404 keeps it until `stale`; a 400 for the body drops it) and say so.
- **L6. "Was turned off" claims a hand did it.** A thermal or OEM shutdown with 64 % reads like the kid. When the
  reason from M1 reads, word `thermal-shutdown` and `battery`. Otherwise "turned off" is fine, but only from
  ShutdownThread; no-broadcast cases use M5's words.
- **L7. Contract wiring.** `server-ci.yml` (launcher copy) and `launcher.yml` (server copy) as planned. `music.yml`
  doesn't need it (the music app doesn't read it), so say that explicitly. The root `CLAUDE.md` gains the contract
  line *and* the CI paragraph's file lists, as for `dns_upstream_status.json`.
- **L8. Tests.** Add H1's sequence, M1's held delivery dropped by a restart and pushed after 3 min (the test calls
  `send_due` with a moved `now`), M4's key after a re-enrollment, and M5's battery clause. Drop "the receiver's
  registration … (source scan)" in favour of one scan that the process has a single `ACTION_SHUTDOWN` receiver
  (M3). The device page's "Off since" compares `happened_at`, not ids.

## Answers to the open questions

1. **A restart: one push.** "Anna restarted" after the start, made possible by M1's 3-minute hold when the flag is
   unknown. The shutdown row stays in the inbox only.
2. **"Off since 21:14 · battery was 3 %" on the device page: in this step.** It is one query on `(device_id, kind)`
   and the stored summary; compare `happened_at`.
