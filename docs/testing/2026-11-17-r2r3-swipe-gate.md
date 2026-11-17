# Emulator gate: App push R2/R3 + 108c, swipe down on the kid's sheets, Innstillinger as its own task (2026-11-17)

The gate for three finished rounds, run on the user's OK for emulator runs:
- **App push R2/R3** (design 108b; branches `up-r2r3-server` d66b9f8d6 and `up-r2r3` 401017322) and **108c** (App push
  on Attention and the delivery time, on `up-r2r3-server`): 108b §11's rows 1-9, 108c's three Attention rows and the
  "Usually delivered in …" lines;
- **Swipe down on the kid's sheets** (branch `kid-sheet-swipe` 9427377e6; `calls/KidSheetDrag*.kt`,
  `docs/notes/launcher/ui-polish.md`);
- **Innstillinger as a task of its own** (design 111, branch `kid-settings-task` 6df3375bd, added mid-run on the user's
  OK): `docs/testing/emulator.md` §6az rows 1-7.

**Build.** Branch `gate-1117` (not pushed) = origin/main 44d9fdd7e + `up-r2r3-server`, `up-r2r3`, `kid-sheet-swipe` and
then `kid-settings-task`, merged in that order. The debug launcher (`assembleDebug -PwarningsAsErrors=true`, built
twice: before and after `kid-settings-task`) and the debug server (`cargo build -j 1`). The shared contract files
under `server/testdata/` and `launcher/app/src/test/resources/` are byte-identical after every merge.

**Merge conflicts.** One: `server/src/handlers/app_push.rs`. main's 2789bdfbc ("App push's prefill falls back to
PHONE_SERVER_URL") added `setup_address` and a `#[cfg(test)] mod tests` at the file's end; `up-r2r3-server` added its own
`mod tests` there (108c's `durations_are_floored_words`). Resolved by keeping `setup_address` and one `mod tests` with
both tests (`use super::*;` plus `use crate::config::ForkConfig;`); the page still calls `setup_address` (line 99).
`up-r2r3`, `kid-sheet-swipe` and `kid-settings-task` merged without conflicts. Migrations 0153/0154 untouched.

**Rig.** One emulator at a time under `flock -o ~/emulator.lock` and `flock ~/build.lock`, battery at 80 % on AC (the
Android 16 image's SystemUI crash under 20 %), proximity set far. §6ay's rig: the server with `BIND_ADDR=0.0.0.0:3108`,
`DEVICE_BIND_ADDR=0.0.0.0:3109`, §6ay's data copied (device 1, migrated to 0154 at start), the public origin
`https://up.test`; the debug launcher as Device Owner, enrolled with a typed code; calls and SMS managed, Mamma and Pappa
on Home with SMS allowed, the emergency menu 112, 110, 113 and Mamma, a parent PIN. The probe is §6ay's
`me.vibb.probe.up`, rebuilt with design 108b §11's additions: a `vapid` extra on REGISTER, an "ack off" switch (no
MESSAGE_ACK for anything with an `id`), and the importance it sees in each `NEW_ENDPOINT` (`getMyMemoryState`: 125 =
foreground service, 100 = foreground, 300 = service). `scripts/fake-homeserver.py` has neither `--vapid` nor `--outside`
(108b §11 says it gains them; neither branch built them), so a gate-only helper in the scratchpad did those: the VAPID
forms signed with `scripts/up-vapid-vectors.py`'s test key (its definitions loaded, its `main` not run), a third public
key from another phrase, and the outside gateway's Matrix body posted to `/up/<secret>`. Ageing went through the debug
build's `UpDebugReceiver` (no clock moved). The policy's `unified_push.refresh` is read with the SQL of
`unified_push::policy` against the live DB at the time (the phone's bearer isn't at hand). Attention rows are read from
the summary page's `data-attention` rows; 108c's day-long runs and the waiting pushes are seeded in the DB
(`vapid_refused_since`/`outside_since` moved 25 h back; a queued `up_messages` row 45 minutes old), and every clearing
goes through the real path (a push, a pull, an ack). The window dim is SurfaceFlinger's task dim layer alpha.
Screenshots and recordings: the scratchpad `gate-1117/shots/`.

**AVDs.** `compat34` (Android 14, google_apis, `-wipe-data -dns-server 8.8.8.8`, booted twice: the R2/R3 and swipe rows,
then §6az) and `jelly36` (Android 16, google_apis_playstore, the Jelly Star size; it was equal to
`~/kid-look-1-avd-pre-start` at the start, 22 of 22 sums, and is restored from it afterwards, 22 of 22, the same file
list). Android 17: not run (no API 37 image boots on this VM, §6as) - owed.

## App push R2/R3 (design 108b §11) and 108c

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) | Result |
|---|---|---|---|
| 1 ping | Backdated 24 days, screen off, deep IDLE, a sync: `NEW_ENDPOINT ping`, the probe gets the same endpoint with a new id (47fd189b; the REGISTER's was 1bf8439e), importance 125; its uid `CACHED_EMPTY` -> `FOREGROUND_SERVICE` 5.0 s -> `CACHED_EMPTY`; next sync no ping | The same: id bebaa40d, importance 125, `FOREGROUND_SERVICE` 5.0 s, none at the next sync | **Pass** |
| 9 time rule | `Lekser` on (probe `suspended=true`), backdated 24 days, a sync: the ping raised (`FOREGROUND_SERVICE` 5.1 s), delivered (id 50b70a1d, importance 125) and acked. Proof of the ack: `pingSentMs` moved 8 days back and a sync - nothing (a ping still out would re-send or die) | The same (id 0b398b54; the 8-day move gives nothing) | **Pass** |
| 2 dead | "ack off"; 24 days -> ping be84911c (ack withheld); `pingSentMs` 8 days back -> **a re-send** (same id), not the removal: the hook moves `pingLastSendMs` with `pingSentMs`, and code QA M1's daily re-send runs first and restarts the hour (see Notes). An hour after the last re-send (15:36:56): `me.vibb.probe.up didn't answer - unregistered`, `UNREGISTERED to me.vibb.probe.up`, the probe `UNREGISTERED token=tokA`; a POST to its endpoint **410**; the server row gone; the PIN-gated Settings row: "UP Probe svarte ikke på 30 dager; registreringen ble fjernet" | The ping (65312a9b), the re-send after 8 days and after the reboot (row 8) seen; **the removal an hour later not waited for** (the extra boot for design 111's build restarted the hour; see Notes) | **Pass** (14); 16: the removal not observed |
| 8 boot re-send | `adb reboot` (boot_count 1 -> 2), unlocked: `NEW_ENDPOINT resend`, the same id be84911c, raised (importance 125), no removal; the removal came an hour later (row 2) | `adb reboot` ended the emulator process on this image; a cold boot (boot_count 3): `NEW_ENDPOINT resend`, the same id 65312a9b, raised, no removal | **Pass** |
| 3 outside, idle | An outside Matrix body to `/up/<B>` 201 (delivered, 259 bytes); screen off, deep IDLE, a sync: **no** `NEW_ENDPOINT`; `refresh` = `[(edd1c734, outside)]`; Device › Apps: "UP Probe's notifications go through an outside gateway (usually unifiedpush.org), …" | The same (`refresh` = `[(65c51b55, outside)]`, the line on the page, no `NEW_ENDPOINT` while idle) | **Pass** |
| 3b outside, in use | Unlock (`USER_PRESENT` 14:37:45 and again 14:38:43, deep idle `ACTIVE`, interactive): **no `NEW_ENDPOINT`**. The next sync in use (14:38:24) sent `NEW_ENDPOINT refresh` (importance 100); after it no second one at the next sync or the next unlock | Unlock (`USER_PRESENT` 15:50:26): **no `NEW_ENDPOINT`**; the next sync in use sent it (importance 125) | **FAIL** (both) - Failures, F1 |
| 4 healed | Our gateway's notify for B's pushkey `{"rejected":[]}`: `refresh` = `[]`, the outside line gone from Device › Apps | The same | **Pass** |
| 5 VAPID | REGISTER with the test key; `rfc`, `rfc-spaced`, `draft`: 201 each, three MESSAGEs delivered; Device › Apps: "Only pushes signed by the app's server" | The same | **Pass** |
| 6 refused | `none` 401, `wrong-key` 403, `expired` 503, `wrong-aud` 503; nothing delivered, no pull (the phone not nudged); our gateway's notify for the pushkey `{"rejected":["https://up.test/up/5slV…"]}`; "Pushes not signed by the app's server were refused, last …"; `refresh` = `[(2203eb14, refused)]` until an `rfc` push (201), then `[]` | The same (401/403/503/503, rejected, the line, `refused` then `[]`) | **Pass** |
| 7 key update | Re-REGISTER with the second key: `NEW_ENDPOINT` with the same endpoint at once, the server's key BPMw… -> BOCy…; without a key: `NEW_ENDPOINT` at once, the server keeps BOCy…; a push signed with the second key 201 | The same | **Pass** |
| 7b offline update | `keyTryMs` aged a day; the server stopped (connection refused); REGISTER with another key: `NEW_ENDPOINT` with the same endpoint at once, no `REGISTRATION_FAILED` (`A registration's call failed: ConnectException`); the server back: the policy at +34 s posts nothing, the policy at +6 min 9 s posts the key (the server holds it) | The same: no post at +2 min 48 s, the key at +6 min 29 s | **Pass** |
| 108c refused | `vapid_refused_since` 25 h back: `data-attention="app-push-refused"` "1 app's notifications are refused"; Device › Apps: "… This has gone on for over a day. Opening UP Probe on the phone once usually fixes it; if not, Stop… and Allow Again gives it a new address."; an `rfc` push: the row gone (the 7-day page line stays, as designed) | server-side, run once | **Pass** |
| 108c outside | A fresh outside run: no row; `outside_since` 25 h back: "1 app's notifications go via an outside service"; our gateway's push 4 s later: **the row stays** (the gateway's stamp is throttled to once a minute: Notes, N2); our gateway's push a minute after the last stamp: the row gone | server-side, run once | **Pass** (with N2) |
| 108c waiting (a) | A never-fetched push queued 45 minutes ago: not raised while only two policies were served in its window, raised at the third ("App notifications aren't reaching the phone"; Device › Apps: "Notifications for the phone's apps have waited on the server since …"); a sync: the pull hands it out, MESSAGE, acked 6 s later, the row gone | server-side, run once | **Pass** |
| 108c waiting (b) | Fetched 6 minutes ago and handed out again 3 minutes ago: raised; a sync: delivered and acked, the row gone. (A hand-out 1 minute ago does not raise it: code QA's 2-minute settle) | server-side, run once | **Pass** |
| 108c delivery time | Settings › App push: "Delivery to the phones · Usually under a second · Pushes 10 in the last 24 hours" and the footer; Device › Apps: "Usually delivered in under a second (10 pushes while connected, last 24 hours)"; `up_delivery` 10 samples, 9-196 ms; the seeded rows (no stream) gave none | Device › Apps: "Usually delivered in under a second (12 pushes while connected, last 24 hours)" | **Pass** |

## Swipe down on the kid's sheets

Pappa's sheet is 532 px high (a third = 177 px), Mamma's with the missed-call line 568 px. Distances are the finger's.

| Row | Android 14 | Android 16 | Result |
|---|---|---|---|
| Slow drag follows the finger, the dim fades | From the name: finger +100/+200 -> sheet +78/+178, dim 0.436/0.333; from the photo (a button, the 36 px start): +60/+120/+170 -> +15/+75/+125, dim 0.5/0.444/0.387 | +60/+120/+170 -> +39/+99/+149, dim 0.5/0.438/0.385 | **Pass** |
| Release above a third springs back, below closes | +170 (sheet +125): back to its place, dim 0.5; +250 (sheet +228): closed, no dim | +170: back, dim 0.5; +250: closed | **Pass** |
| A quick flick closes | 140 px in 60 ms: closed; 60 px in 40 ms (under the 48 dp fling travel): springs back | The same | **Pass** |
| After a swipe close Home equals a Lukk close | A missed call from Pappa; the sheet swiped closed: Home "Pappa" (badge gone), call log `new=0`, notification 1006 gone. A second missed call closed with Lukk: the same | The same | **Pass** |
| Ring, Melding, Lukk; a drag from Ring doesn't call | Lukk closes; Ring: Telecom `SET_DIALING` (hung up at once); Melding: `ThreadActivity`; a drag starting on Ring: the sheet moved and sprang back, no call (no `SET_DIALING`, no call in Telecom) | The same | **Pass** |
| A hurried tap on 112 with a slip | 27 px (18 dp) down in 70 ms on the 112 row (non-scrolling sheet): the emergency call placed (`EmergencyCallHelper`, TC@5 `CONNECTING` -> `ACTIVE`), ended from the dialer's End call (it ran 1 min 39 s: the proximity sensor read near and blanked the screen until it was set far) | The same slip: `SET_DIALING` 15:59:20, `SET_ACTIVE`, ended 15:59:29 (9 s) | **Pass** |
| Scrolling emergency list | Font scale 2.0 (the rows scroll): list scrolled 300 px down; a 360 px drag down scrolled it back to its top and the sheet never moved in that touch; the next drag moved the sheet (+38/+98/+158) and it sprang back | The same (the sheet still in the first drag, +39/+99/+159 in the next) | **Pass** (per touch, as designed) |
| The photo's long-press; the same finger can't drag the card | "Bilde av Pappa" opens; the same finger then moves 210 px: the photo sheet's title stays at 420, the card under it at rest (screenshot); after the lift the photo sheet stays | The same (`36-photo-held.png`) | **Pass** |
| Back (gesture, 3-button) and tap outside close all three | Contact, emergency and photo sheets each closed by the edge swipe, by a tap above the sheet and by the 3-button Back; the photo sheet closes alone, the card stays | The same | **Pass** |
| Animations off | Release at +150: dim 0.386 (the held value, the release not yet processed) then 0.5 at +55 ms, no value between; release at +285: no dim from +28 ms. Animations on, for comparison: 0.276, 0.187, 0.124, 0.060 … over 270 ms | Release at +150: 0.384 then 0.5 at +72 ms; +285: 0.291 then none at +52 ms. On: 0.288, 0.203, 0.140, 0.075, 0.001 | **Pass** |
| The dim follows the finger | Sampled every 15 px: 0.500 to +60, 0.443 to +120, 0.386 to +180, 0.330 to +240 - monotonic, in steps of ~0.057 every ~60 px (code QA L6's 0.05 steps, each a window relayout) | 0.5 to +90, 0.438, 0.385, 0.332 | **Pass** (steps as designed; how it reads by eye is the user's call) |

## Innstillinger as a task of its own (design 111, §6az rows 1-7)

| # | Android 14 | Android 16 | Result |
|---|---|---|---|
| 1 Own task | The tile and the swipe left both: `Task{#10 type=standard A=…launcher.debug.kidsettings}`, `KidSettingsActivity` aa4e81f on top; lock-task list Home #9 first, then the kidsettings task | The same: task #23, the same `ActivityRecord` 267155866 from both ways in; Home #18 first | **Pass** |
| 2 Open and close | Shell's default transition both for Kid Settings (`TO_FRONT … MOVE_TO_TOP`, then Home `TO_FRONT SHOW_WALLPAPER`) and Meldinger (`OPEN`); no wallpaper-intra. `screenrecord` on this image captured only Home frames (Kid Settings itself on screen: plain night ground, `34-ks.png`) | Recorded: the default task transition for both; Kid Settings on a plain night ground in every frame, no wallpaper; Meldinger's first frames plain ground before its content (`ks36-tile2.png`, `msg36-tile2.png`) | **Pass** (14: frames not captured) |
| 3 Recents and Back | The card "Innstillinger" with the Vibb icon and the night ground; a tap brings back aa4e81f; the Wi-Fi page, Home, the tile: the Wi-Fi page on top; Back twice ends on Home, the card stays | The same (267155866; `36-recents-card.png`) | **Pass**; the picker and the Wi-Fi sign-in entry on top **not run** |
| 4 Location and the mask | Location off -> the Wi-Fi page 3 -> Home 0 -> back on the page 3 -> Back 0. The PWA's Wi-Fi bit off with the page in the card: Kid Settings with no Wi-Fi row, the page finished (`t-1 f`), Location stayed 0, no `startScan` | The same (0 -> 3 -> 0 -> 3; no row, finished, 0, no scan) | **Pass**; "no 'couldn't connect' after a connect and Home" **not run** (one network on the emulator) |
| 5 A rule, and Flymodus | Wi-Fi page, Home, `Lekser` on, the card: the time-rule screen, the Wi-Fi page finished, Location 0, no scan; the rule ended, the card: Kid Settings | The same; Flymodus **not run**: the row reads "Bestemt av mamma og pappa" on this rig (the assistant role granted; the parent's switch not found in the time) | **Pass** (rule); Flymodus not run |
| 6 Process death | Lys, the phone dark, the Bluetooth page, Home, `kill -9`, the card: the splash a plain dark ground with no icon, then the Bluetooth page restored in Lys (`34-pd-strip.png`); Back twice ends on Home; Home first in the lock-task list | The same (`pd36-tile.png`) | **Pass** |
| 7 Language and look | nb -> en: **the first ask was refused** (`LauncherLocales: Couldn't set the launcher language - IllegalStateException: Can't change activity type once set`), the retry 24 ms later went through (`Launcher language: en`); the card still showed the old Norwegian screen right after, and the kidsettings task was gone by the next look at Recents; the tile then opened Kid Settings in English. Dark with Meldinger, Dagen and the phone book open: their three tasks closed, Kid Settings' stayed | nb -> en: the first ask went through (`Launcher language: en`, no refusal); the card "Innstillinger" stayed in Recents (task #27); the tile then opened Kid Settings in English in task #32. Dark, then Light: Meldinger's, Dagen's and the phone book's tasks closed, Kid Settings' stayed | **Pass** |

## Failures

**F1 (108b row 3b, Android 14 and 16): an unlock never sends the waiting refresh.** The refresh that waited for the
phone to be in use goes out only at the next accepted policy while in use (up to 30 minutes later at the backstop), not
within a second of `USER_PRESENT`.

- Evidence: `dumpsys activity broadcasts history` for each `USER_PRESENT` lists the launcher's receivers it was
  delivered to - only PinLockRuntime's exported one (14: `ReceiverList{11d053c}`; 16: `{bc576c2}`). The launcher holds
  two single-action `USER_PRESENT` receivers (14: `66c0bd3` and `11d053c`; 16: `7ab9203` and `bc576c2`); the UP one is
  never in the delivery list. No `NEW_ENDPOINT` follows the unlock; the next in-use sync sends `NEW_ENDPOINT refresh`.
- Diagnosis: `launcher/app/src/main/java/com/kidslauncher/mdm/push/up/UpRuntime.kt:143` registers it with
  `Context.RECEIVER_NOT_EXPORTED`. SystemUI sends `USER_PRESENT` from its own uid, and a not-exported receiver only hears
  system_server, root and its own app (AOSP `BroadcastSkipPolicy`) - the same trap design 59's run found for the PIN
  lock, fixed at `lock/PinLockRuntime.kt:575-584` with a separate `RECEIVER_EXPORTED` registration for this one action
  (`USER_PRESENT` is a protected broadcast, so no app can send it). The unit tests can't see this; the design note (108b
  §3.3, §9 "behave alike on 14-17") and `docs/notes/launcher/unified-push.md` say `RECEIVER_NOT_EXPORTED`. Not fixed
  here (gate only).

## Notes

- **N1 (108b row 2's procedure).** The gate row's "backdated 24 days (ping), then the ping 8 days; a sync each ->
  `UNREGISTERED`" no longer holds after code QA M1: `UpDebugReceiver` moves `pingLastSendMs` with `pingSentMs`, so the
  8-day move makes the daily re-send due, the re-send stamps `pingLastSendMs` now, and the dead rule's "an hour since the
  last send" then needs a real hour (`UpRules.liveness`, `UpRules.kt:300-311`). The hook takes whole days only, so the
  gate can't reach "dead" without waiting the hour. On 14 the hour was waited (the removal came 60 minutes after the
  reboot's re-send); on 16 it was not (design 111's rebuild needed the emulator down, and the next boot re-sent). Either
  let the hook move `pingLastSendMs` alone, or by hours, or change the row to "the removal an hour after the re-send".
- **N2 (108c outside row, Low).** Our gateway's `gateway_at` is written at most once a minute
  (`server/src/unified_push/matrix.rs:283-284`), so a gateway push within a minute of the previous gateway stamp doesn't
  end an outside mark set in between: the Attention row, the page line and `refresh` stay until a gateway push at least a
  minute after the last stamp. Seen here: a gateway push at 14:40:11, the outside push at 14:41:03, a gateway push at
  14:41:07 left the row; one at 14:41:21 cleared it. Real traffic rarely hits it (the next push after a minute heals
  it). A condition `gateway_at < outside_gateway_at OR gateway_at < now - 1 minute` would end it at once.
- **N3 (rig).** On `jelly36` `adb reboot` ended the emulator process instead of rebooting the guest (no log line); a cold
  boot stood in for row 8's reboot. On a freshly restored `jelly36` the launcher had no location permission
  (`startScan … UID 10218 has no location permission`, the DO's location policy on this rig), so the Wi-Fi page lists no
  scan results; the page's own Location handling (row 4) is unaffected. Once, right after `cmd location
  set-location-enabled false`, a tap into the Wi-Fi page showed the system's `BlockedAppActivity` in a new task; it didn't
  come back in three more tries and its starter wasn't captured (most likely a Google location prompt blocked by lock
  task) - recorded, not judged.
- **N4.** In Android 14's run, `screenrecord` delivered only Home frames for row 2; the transition type is from Shell's
  log and the ground from a screenshot.
- Not run: 108b row 2b/12 (as in R1), FluffyChat/Fedilab/Element X (owed on the Jelly Star), Android 17; §6az row 3's
  picker and Wi-Fi sign-in entry, row 4's connect-then-Home toast, row 5's Flymodus. The crash buffer was not read.

**Emulator time:** `compat34` 14:28-15:38 (69 min) and 16:17-16:31 (14 min), `jelly36` 15:38-15:44, 15:48-16:00 and
16:02-16:16 (about 33 min): about **1 h 56 min** in all, one emulator at a time.

## Re-run 2026-11-17 evening: row 3b

After the F1 fix: `gate-1117` with `up-r2r3-server` c0d05a669 (N2's `gateway_at`) and `up-r2r3` 994784d3f
(`UpRuntime`'s `USER_PRESENT` receiver `RECEIVER_EXPORTED`; N1's `send_hours`) merged in (no conflicts), the debug
launcher and server rebuilt. Only row 3b: a fresh `compat34` (`-wipe-data`) and `jelly36` from its clean copy (restored
again afterwards, 22 of 22 sums), each provisioned and enrolled; the probe registers, an outside Matrix body 201 while
idle (deep IDLE, screen off) and a sync - no `NEW_ENDPOINT`, `refresh` lists the registration; then the unlock.

| Row | Android 14 | Android 16 | Result |
|---|---|---|---|
| 3b outside, in use | `USER_PRESENT` 18:08:24.096, `NEW_ENDPOINT refresh for me.vibb.probe.up` 18:08:24.126 (+30 ms), raised, the probe's `NEW_ENDPOINT` with the same endpoint 18:08:24.132; none at the next sync or the next unlock | `USER_PRESENT` 18:14:17.624, `NEW_ENDPOINT refresh` 18:14:17.641 (+17 ms), raised, the probe's at 18:14:17.645; none at the next sync or the next unlock | **Pass** |

F1 is fixed on both versions. Emulator time: `compat34` 18:05-18:09 (4 min), `jelly36` 18:09-18:15 (6 min), 10 min.
