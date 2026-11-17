# Emulator check: the small fixes of 2026-11-17 (branch `small-fixes-1117`)

The branch's five queued items (PLAN.md): Home's built-in tiles in the one Material Symbols set (item 1), the
notification small icon's two-ring variant (item 2), the false "Boot cover wasn't shown" (item 3, built), the empty
shade after a swipe from the glance to the PIN pad (item 4, investigation) and the glance after an Element X-style VoIP
call ends (item 5, measurement). No product code was changed for items 4 and 5.

**Build.** `small-fixes-1117` at 4f31816c1: the debug launcher (`assembleDebug -PwarningsAsErrors=true`) and the debug
server (`cargo build -j 1`).

**Rig.** One emulator at a time under `flock -o ~/build.lock` and `flock -o ~/emulator.lock`. The server with
`BIND_ADDR=0.0.0.0:3108`, `DEVICE_BIND_ADDR=0.0.0.0:3109`, on a copy of the 2026-11-17 gate's data (device 1, migration
0154; Mamma, Pappa and Lily as contacts). The debug launcher as Device Owner (§4), enrolled with a typed code, kiosk on,
**Android's lock** with the Child PIN 4826 (the PWA's `kid-lock` and `kid-lock/keyguard keyguard=android`, a new parent
PIN), the boot cover on (Advanced › Updates & notifications). `compat34` (Android 14, `-wipe-data -dns-server 8.8.8.8`)
and `jelly36` (Android 16, equal to `~/kid-look-1-avd-pre-start` at the start, 22 of 22 sums, started without
`-wipe-data`; restored from that copy afterwards: 22 of 22 sums, the same file list). Battery on `jelly36` kept at 80 %
on AC. Android 17: not run (no API 37 image boots on this VM, §6as).

**The VoIP test app** (item 5) is a throwaway probe written for this check (`me.vibb.probe.voip`, Java, built like
§6ay's probe, debug-signed, let out with Allow these apps and ticked under Apps): a ring is a `CATEGORY_CALL`
notification with a full-screen intent and `CallStyle.forIncomingCall` (Decline a broadcast, Answer the call activity);
the answered call is a `phoneCall` foreground service on Element's channel `call_foreground_service_channel` with
`CallStyle.forOngoingCall`, `MODE_IN_COMMUNICATION` and silence played on a `USAGE_VOICE_COMMUNICATION` track (without
it AudioService resets the mode after a few seconds - first 30 s run below); ring and call screens are `showWhenLocked`.
Two variants: the screens in a task of their own, or (`ring_t`) in the app's own task with its non-`showWhenLocked`
main screen opened earlier behind them - as an app the kid has used before. Commands `am broadcast -n
me.vibb.probe.voip/.Cmd --es cmd ring|ring_t|caller_hangup|msg`; Answer and Hang up tapped on its screens. The focused
window and `mKeyguardOccluded` were sampled from `dumpsys window` every ~0.2 s.

## Items 1 and 2: the tiles and the small icon (`jelly36`, for the user's eye)

Screenshots (scratchpad, not in the repo): `small-fixes-shots/36-home-tiles.png` (Home: Telefonbok the handset `call`,
Meldinger the bubble with dots `sms`, Innstillinger the gear `settings`, all on their coloured discs), `36-shade.png`
(the shade: the silent "Vibb One" notification 1002 with the two-ring icon in its circle),
`36-shade-vibb-icon-zoom4x.png`, `36-statusbar.png` and `36-statusbar-zoom4x.png` (the status bar: missed call, the
SystemUI tutorial's gear, the two-ring ◎, the keyboard's A). `dumpsys notification`: 1002's icon is
`0x7f0701f5` = `drawable/ic_stat_vibb` (aapt2 dump). The missed-call notice 1006 uses the platform's `sym_call_missed`
(`0x0108007f`), as before - not this item. **For the user's eye; no verdict here.**

## Item 3: no false "Boot cover wasn't shown"

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) | Result |
|---|---|---|---|
| A report sent after the shutdown's arm | Low battery (`adb emu power capacity 15`): `Sync for [battery]` 22:20:15.458, then the power menu's Power off: `Sending shutdown broadcast` 22:20:20.033, `phone_off` at 22:20:20.094 (the receiver that arms), a `Sync for [sse]` at 22:20:20.173 and 22:20:20.900 (nudged from the host at the broadcast), the power-off at 22:20:21.132. The server stored a report at 22:20:21 with `last_armed_at_ms` 1794953851110 (22:17:31, **the arm before**), not the new 22:20:20 arm | Power menu's Power off: `Sending shutdown broadcast` 22:33:59.396, `phone_off` 22:33:59.438, `Sync for [sse]` 22:33:59.478, the power-off 22:34:00.473. Its arrival can't be shown: the arm before was none, so the report equals the stored one and adds no row (`device_status` and `last_seen_at` stayed 22:25:48) | **Pass** (14); 16: the sync seen, its report not provable |
| While off: Advanced and Attention | Advanced: "Cover at the last start · Shown 02:19 · It handed over when Vibb One started" (no warning); the summary's Attention rows: `calls` only | Advanced: "Cover at the next start · Ready"; Attention: `calls` only | **Pass** |
| After the boot | The cover up at the locked boot (it asks for Android's pad over itself), then Home after the PIN; the report `last_armed_at_ms` 22:20:20.066, `last_shown_at_ms` 22:22:05; Advanced "Shown 02:22 · handed over when Vibb One started"; Attention `calls` only | `BootCoverActivity` focused at the boot, Home after the PIN; `last_armed_at_ms` 22:33:59.406, `last_shown_at_ms` 22:35:58; Advanced "Shown 02:35"; Attention `calls` only | **Pass** |
| `boot_cover` prefs | Three shutdowns: `armed_boot` 1 / `previous_armed_at_ms` 0, then 2 / 22:12:28.604, then 3 / 22:17:31.110 - each arm with its boot and the arm before | `armed_boot` 2, `previous_armed_at_ms` 0 (the first arm) | **Pass** |
| The true case (a start since the arm, the cover not shown) | Not provoked: in Android's lock mode the cover stays enabled at every start (design 59d step M) and making one start skip it means a boot with no direct-boot HOME. The server's rule is `kiosk_escapes::boot_cover_notes`' test rows (`Some(3_001)` -> `BootCoverNotShown`) | - | **Not run** |

PWA times are the rig's display zone (UTC + 4 h on this data). Two rig notes: the first Android 14 shutdown went through
`svc power shutdown` (the same `ACTION_SHUTDOWN`) because the emulator's long press on Power is the Assistant
(`settings put global power_button_long_press 1` gives the power menu, used for every later shutdown); a sync nudged at
"Shutting down activity manager" (22:17:31.771) did not reach the server before the power-off 0.7 s later.

## Item 4: the empty shade after a swipe from the glance to the PIN pad

| Step | Android 14 | Android 16 |
|---|---|---|
| The glance, LOCKED | `mDisabled1=0x7260000` (`DISABLE_EXPAND` among them), `mDisabled2=0x15`, keyguard occluded, focus `PinLockActivity` | the same |
| Swipe down from the status bar (`input swipe 240 5 240 600`, 300 ms; also a 60 ms flick) | Nothing opens; SystemUI: `mInstantExpanding=false`, `lastExpansionEvent(fraction=0.0, expanded=false)` | Nothing opens, **but** SystemUI logs `NotificationPanelView: Status Bar was long pressed.` / `systemui.shade: Status Bar was long pressed. Expanding to Notifications.` and is left with `mInstantExpanding=true`, `mAnimateAfterExpanding=true`, `lastExpansionEvent(fraction=0.0, expanded=true)` (the 60 ms flick the same) |
| Then a swipe up (the glance asks for the pad) | The pad (`isBouncerShowing(): true`, focus `NotificationShade`) | `PrimaryBouncerInteractor: Show primary bouncer requested`, then within ~0.4 s `PRIMARY_BOUNCER -> OCCLUDED`: **the empty shade "No notifications"** (`mExpandedHeight=192.0`, `mExpandedFraction=1.0`, `isPanelExpanded()=true`, `isBouncerShowing(): false`, focus back on `PinLockActivity`); AndroidLock 2 s later: "Lås opp: no answer from Android's PIN pad - reading the state again" |
| A swipe up alone (no swipe down before) | The pad | The pad (`OCCLUDED -> PRIMARY_BOUNCER`, `isBouncerShowing(): true`) |

So on Android 16 the swipe down is the cause: SystemUI's status-bar long press ("Expanding to Notifications", new in
16's shade) doesn't honour `DISABLE_EXPAND`; the expansion can't run while the shade is disabled and the keyguard
occluded, so it waits (`mInstantExpanding`) and runs at the next layout of the `NotificationShade` window - the bouncer
our ask brings up. The shade then expands over the bouncer, which SystemUI drops back to OCCLUDED; the shade is empty
because the lock hides the notifications. Android 14 has no such long press and never showed it.

What gets out of it (Android 16): Back does nothing (still `mExpandedHeight=192`); a screen-off does; the existing
status-bar heal's SYSTEM_INFO flip does **not** (`LockTaskOverrideReceiver --ei clear_features 1`, then reset: still 192);
a flip that sets `DISABLE_EXPAND` anew does (NOTIFICATIONS + HOME added, then reset: 192 -> 0, the glance again - but
the shade is expandable for the flip's length); **a second dismiss ask does** (`wm dismiss-keyguard` in the stuck
state: `OCCLUDED -> PRIMARY_BOUNCER`, the pad over the shade at once), and the PIN typed there unlocks to Home with the
shade collapsed (`mExpandedHeight=0.0`).

**Fix proposal.** In AndroidLock's glance ask (`Lås opp: requestDismissKeyguard`), ask once more when the pad was
taken away again: the glance's window gets focus back (`PinLockActivity.onWindowFocusChanged(true)`) within ~1 s of the
ask while the keyguard is still locked and the screen on - in the stuck case SystemUI set the shade window's back
callback at the bouncer's show (29.070) and dropped it at 29.430, and the focus read afterwards was `PinLockActivity`,
while a normal pad keeps the focus on `NotificationShade` (the focus wasn't sampled in between) - then a second
`requestDismissKeyguard` after ~0.5 s (the gesture ask's retry pattern, never twice). Gate it to `SDK_INT >= 36`; on 14
the case doesn't occur. The kid sees the empty shade for well under a second, then the pad. Not checked: a second ask
while a normal pad is already up (the focus signal should keep that from happening). Rejected: the NOTIFICATIONS flip
(opens the shade for the flip), hiding the status bar on the glance (a visible change to the glance).

## Item 5: the glance after a VoIP call ends

The lock stays IN_CALL for `VOIP_GRACE_MS` (15 s) from the ring's end whatever happens after. Each row: the ring end
as the launcher saw it (`VoIP RINGING -> IN_CALL`), the app's screen leaving, what then shows, `VoIP IN_CALL -> NONE`,
the glance back.

| Run | Android 14 | Android 16 |
|---|---|---|
| A. Own task, caller hangs up while ringing | ring end 22:03:40.882 (the ring screen paused 40.380); **the glance resumed at once** (40.522: it is next in the stack); NONE 22:03:56.899 (+16.0 s) | - |
| C. Own task, answer, 10 s call, hang up | ring end 22:04:39.724; hang-up 22:04:50.071; **the glance at once** (50.128); NONE 22:04:56.098 (+16.4 s from the ring end, +6 s after the hang-up) | - |
| T1. App's own task, caller hangs up while ringing | ring end 22:06:34.609; the ring screen gone 34.607; the app's main screen resumes under it, **Android's lock screen** from 22:06:34.911 (`mKeyguardOccluded=false`, focus `NotificationShade`; `item5-34-android-lock-after-ring-end.png`); the screen times out at 22:06:40.309; NONE 22:06:50.621 (+16.0 s), the glance resumes (screen off) | ring end 22:31:08.291; **Android's lock screen** 22:31:08.606 (`item5-36-android-lock-after-ring-end.png`); screen off 22:31:14.029; NONE 22:31:24.308 (+16.0 s), the glance resumes |
| T2. App's own task, answer, 10 s call (< 15 s), hang up | ring end 22:07:28.365 (ring cancelled 28.228, call service 28.261); hang-up 22:07:38.369; **Android's lock screen** 22:07:38.682-22:07:44.940 (6.3 s); NONE 22:07:44.387 (+16.0 s from the ring end), the glance 22:07:44.940 | ring end 22:31:53.308 (cancelled 53.168, call service 53.218); hang-up 22:32:03.084; **Android's lock screen** 22:32:03.393-22:32:09.835 (6.4 s); NONE 22:32:09.188 (+15.9 s), the glance 22:32:09.835 |
| T3. App's own task, answer, 30 s call, hang up | ring end 22:10:07.911; hang-up 22:10:39.637; NONE 22:10:39.760 (+0.12 s); **the glance** 22:10:39.877; no Android lock screen | ring end 22:32:46.445; hang-up 22:33:18.165; NONE 22:33:18.274 (+0.11 s); **the glance** 22:33:18.895 (`NotificationShade` focused 0.6 s, keyguard still occluded); no Android lock screen |

So the user's observation holds on both versions: Android's lock screen shows from the app's screen leaving until
`IN_CALL -> NONE`, which comes 15-16 s after the ring's end - a ring-only end or a call shorter than the grace waits it
out (6.3 s seen after a 10 s call; up to 15 s after a quick hang-up), a call longer than the grace ends at once (the
call's notice gone and the audio out of IN_COMMUNICATION). It shows only when the app's own task has a screen that
isn't `showWhenLocked` under its call screen (Element X's main screen after use); with the call screens in a task of
their own the glance is next in the stack and comes back at once (runs A and C). The glance comes back by itself at
NONE (`LockEvent.VoipEnded` -> `showLock`), also when the screen went off in between.

Ring to call notice: the probe's call service came 33-244 ms after its ring was cancelled (14: 33 ms, 244 ms; 16:
50 ms, 163 ms) - the probe answers in-process; Element X's real gap (its call joins first) is owed on the Jelly Star
(`VoIP RINGING -> IN_CALL` against Element's `CallForegroundService` notification in `VoipCalls`' log).

Side finding: in the first 30 s run (14, before the probe played audio) AudioService reset `MODE_IN_COMMUNICATION`
after a few seconds, so at the grace's end (22:08:39.991, +16.2 s) the launcher went `IN_CALL -> NONE` while the call's
notice still lived and the glance came over the live call screen. An app whose call doesn't hold the mode (or holds it
late) gets the glance over its call at 15 s; Element X plays WebRTC audio, so it should hold it - worth a look in the
Jelly Star check.

**Fix proposal** (pure, in `VoipCallPlan.continueCall`, `VoipCallPlanTest`): remember on the record that the call's
notice was seen (`callSeen`, set when the package is in `inCall`). Then:
1. **The call's notice seen and gone, and the audio out of IN_COMMUNICATION** -> the call is over: NONE at once, no
   grace (after a short settle, e.g. 1 s, for a notice re-posted with another id). This covers every answered call,
   short or long, and never touches design 57's ring-to-call-service gap, which lies before the first call notice.
2. **The ring ended with no call notice yet** (a ring-only end, or the gap of an answer): keep a grace, but only as
   long as an answer needs - end it early once neither a call notice nor IN_COMMUNICATION came within an answer gap
   measured from Element X on the Jelly Star (the gap above; for instance 5 s if Element's is ~1-2 s), instead of the
   fixed 15 s. Until that measurement, 1. alone fixes the answered case the user hit; the ring-only case keeps up to
   15 s of Android's lock screen.
`IN_CALL -> NONE` already brings the glance back by itself (`VoipEnded`), so no new re-front is needed.

## Not run

Android 17; item 3's true case; item 4's second ask while a normal pad is up; item 5 with Element X itself (owed on the
Jelly Star, with its real ring-to-call gap). The crash buffer was not read.

**Emulator time:** `compat34` 21:54-22:24 (29 min, three shutdowns and cold boots in it), `jelly36` 22:24-22:37
(13 min, one shutdown): about **42 min**, one emulator at a time.
