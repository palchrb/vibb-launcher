# 41 - A flashlight on Home and on the lock (proposal)

Asked 2026-10-19 ("vi burde legge til en lommelykt 'app' kanskje? Kanskje tilogmed på låst skjerm, som for iphone"),
placement decided by the user: "Home og lås". Launcher only; no API change. Builds on design 25/25b (the lock's glance
page, `CameraLock`), design 08/34 (Home's tiles and tones).

## 0. Why the launcher owns it

Android has no torch app; the Quick Settings tile is out of reach in the kiosk. `CameraManager.setTorchMode` needs no
permission and no camera session, so the launcher can light the flash itself, from a Home tile and from the lock,
the way an iPhone does.

## 1. Decisions

1. **A `Torch` object** (`launcher/.../torch/Torch.kt`, Android glue over pure `TorchRules`): the back camera with
   `FLASH_INFO_AVAILABLE` (none: the tile and the button don't exist - the emulator), `setTorchMode(id, on)`, and a
   `TorchCallback` so the state follows the truth (another app, the system or a call turning it off). State is
   process memory plus the callback; nothing is stored.
2. **Home**: `GridTile` is sealed (`ui/home/HomeModel.kt`), so the tile is `data class Flashlight(val lit: Boolean)`,
   placed before `GridTile.Settings` (always last two: Flashlight, Settings); `homeGrid(..., torch: TorchState?)`
   carries the state (null: no flash unit, no tile). `HomeGridAdapter`'s two `when`s get the case, and `renderGrid()`
   is the re-render for the callback. Drawn like the Settings tile (the round tile, the Vibb tones - never yellow):
   the Material Symbols torch glyph (`flashlight_on`/`flashlight_off`, `scripts/material-symbols.sh`, Apache-2.0 like
   the others), the label "Lommelykt"/"Flashlight", the lit state a filled peach disc with the `bg` glyph. A tap
   toggles. It is the only tile that toggles instead of opening, so it has a TalkBack `stateDescription` ("på"/"av").
   `ui/home/` is GPL code, outside the MIT directories, so it is fine there.
3. **The lock**: a round 48 dp button in a new bottom-row container in `view_lock_glance.xml` that holds it and
   "Nødsamtale" (two children, the Emergency button centred as today - the torch button sits at its right without
   pushing it off centre; the Emergency button keeps its size and text, today a direct child of the vertical
   root). Same glyph and lit look, usable while LOCKED without the PIN - it protects nothing and reveals nothing
   (design 25's rule: the page's only actions change no state the lock guards). `glanceFit`'s `fixed` is measured
   from the row (`withMargins(row)` in place of `withMargins(lock_glance_emergency)`); a 48 dp button in the row adds
   nothing while the row is at least as tall as the Emergency button, so `fixed` stays 106 dp.
4. **Rules** (`TorchRules`, pure, JVM-tested):
   - Off by itself after 10 minutes: `AlarmManager.setExactAndAllowWhileIdle` to a `TorchOffReceiver` (the launcher
     holds `USE_EXACT_ALARM`, as `TimeRuleAlarm` does; a Handler alone dies with the process, and the inexact variant
     can run minutes late in Doze), re-armed at every on and cancelled on every off path.
   - Off when a call begins, on the false->true edge of `MusicCalls`' three listeners (`VoipCalls` including a ringing
     VoIP call, `OngoingCalls`, the audio mode) - not `KidInCallService`, which sees only our dialer's calls. The torch
     hangs its off rule at the place those three converge (`MusicCalls.evaluate`, via a small shared call-edge helper
     so the music rule and the torch don't drift). Never turned back on by the call's end.
   - **Never off at screen-off** (as iPhone).
   - After a process restart the platform has already turned the torch off (CameraService turns off a torch whose
     client died); we only re-read the state through the callback.
5. **The camera policy while LOCKED** (design 25b: cameras disabled by `setCameraDisabled` while the PIN lock is
   LOCKED, `CameraLock.syncCameraPolicy`). The policy is never lifted for the torch: a lift would reopen design 25b's
   lock bypass (the camera gesture landing on a suspended camera). `Torch.on()` tries `setTorchMode`; if it fails with
   `CameraAccessException.CAMERA_DISABLED` while the PIN lock is LOCKED, the lock's button is hidden (the state is
   remembered for the process and re-tried at the next unlock) and the Home tile keeps working. On AOSP the policy
   check sits in the camera connect path and not in `setTorchMode`, so the torch probably lights under the policy; an
   OEM build may differ. The probe on the Jelly Star (§4 step 0) decides whether the lock's button exists at all, and
   the user is told the result.
6. **Look**: calm, adult (launcher-look rule): the tile and the button in the Vibb tones, the lit state a filled
   disc, no animation.

## 2. Security

Nothing new is reachable: the torch API opens no camera, the lock's button starts no activity, the camera policy is
untouched; the alarm's receiver is not exported. No string from any app is shown. `LockGlanceGuardTest` gets the
button: it must not open anything.

## 3. Tests (required)

`TorchRulesTest` (JVM): the auto-off at 10 minutes from the last on, the alarm cancelled on every off path; off at a
call's begin (each of the three sources), not re-lit at its end; screen-off changes nothing; `CAMERA_DISABLED` while
LOCKED hides the lock's button and a later unlock re-tries; no flash unit = no tile, no button. `HomeModelTest`: the
tile order (..., Flashlight, Settings), its lit state, and its absence without a flash. `LockGlanceGuardTest`: the
button opens nothing. There is no `GlanceFitTest`: the fit and the page's XML model are in `LockGlanceTest.kt`, which
changes with the row: the child-order test (`root.kids().takeLast(2)` becomes the row as the last child, with the
unlock button before it and the row holding Emergency and the button), the `fixed` measurement taken from the row
(still 106 expected; `tileFull` 211 and `tileCompact` 90 are untouched by this design but are re-derived once with the
other round-6 changes), and the 320/360/411 dp width checks with the button in the row.

## 4. Device checks (the Jelly Star - the emulator has no flash)

0. The probe first, in the debug launcher: Home tile on/off unlocked; then LOCKED: the lock's button - lights, or
   fails with CAMERA_DISABLED (the button is hidden) - record which and tell the user.
1. Lit while LOCKED, the camera gesture (double power) must behave as today.
2. 10 minutes: off by itself; an incoming call (cellular and VoIP ringing): off; screen off and on: still lit.
3. Unlock while lit from the lock: still lit; lock again: still lit; the tile and the button agree.
4. A real Jelly Star photo afterwards: the camera app works as before.

## Revision (2026-10-20, after qa-r6-launcher-design.md)

- 41-1: no camera-policy lift, ever; `CAMERA_DISABLED` while LOCKED hides the lock's button, the probe decides (§1.5, §4).
- 41-2: the lift and its ordering are gone with it (§1.5, §3).
- 41-3: the call hook is `MusicCalls`' three listeners and the place they converge (§1.4).
- 41-4: the button goes in a new bottom row with "Nødsamtale"; `fixed` measured from the row (§1.3, §3).
- 41-5: `Flashlight(val lit)`, `homeGrid(..., torch)`, the adapter's `when`s, `renderGrid()`, `stateDescription` (§1.2).
- 41-6: `setExactAndAllowWhileIdle`, cancelled on every off path; the restart is the platform's (§1.4).
- Tests: `LockGlanceTest.kt`, not `GlanceFitTest` (§3).

## Implementation status (2026-10-20)

### The lock's button (implementer B, branch `r6-b`)

- **Layout** (`view_lock_glance.xml`): the bottom row `lock_glance_bottom`, a `FrameLayout` after "Lås opp" with the
  row's 2 dp top margin, holds `lock_glance_emergency` (centred, `minHeight` 48 dp, 52 dp margins on both sides so a
  long label at a large font wraps before it reaches the button and stays centred) and `lock_glance_torch` (48 dp
  round `ImageButton` at the end, `gone` until the panel knows there is a flash unit). `glanceFit`'s `fixed` is
  `withMargins(lock_glance_bottom)` - 106 dp with or without the button.
- **The seam**: `GlancePanel(..., torch: TorchPort? = null, onEmergency)`; `TorchPort` (in `lock/LockGlance.kt`):
  `hasFlash`, `lit`, `refusedWhileLocked` (`CAMERA_DISABLED` while LOCKED, §1.5), `toggle()`, `addListener`/
  `removeListener`. Until `Torch` lands the lock passes nothing and the button stays gone; **at the merge**
  `PinLockActivity` passes an adapter over `Torch` (`toggle()` = `Torch.toggle(this)`, the state and the listener).
  The panel listens between onStart and onStop (posted to the main thread) and `bindTorch` follows the pure
  `glanceTorchButton`: HIDDEN (no flash unit, or refused on the lock), OFF (`bg_music_control`, `kid_core` glyph) or
  LIT (the peach `bg_music_play` disc with the `kid_ground` glyph); no animation. TalkBack: "Lommelykt"/"Flashlight"
  with the state description "på"/"av" (`lock_torch*`, nb + en).
- **Glyphs** (since 2026-10-23 only `torch_glyph_on.xml` for both states - the user: "ta bort den skrå streken", the disc's colours say the state; `torch_glyph_off.xml` and its `flashlight_off` row in the script are gone): `torch_glyph_on`/`torch_glyph_off.xml`, Material Symbols `flashlight_on`/`flashlight_off` (Rounded,
  filled, the script's pinned commit, SHA-256 `6ea0d1a3...`/`a5de8d4a...`), converted exactly as
  `scripts/material-symbols.sh` writes `lock_glyph_*` - added by hand on this branch; the merge keeps one copy and
  the script's entry (implementer A's).
- **Guards**: `LockGlanceGuardTest` - the button's click is exactly `torch?.toggle()`, the panel has no other toggle,
  no `CameraManager`/`setTorchMode`/`setCameraDisabled`/`CameraLock`/`DevicePolicyManager`, and `TorchPort` has only
  the state, the toggle and the listener; `LockMediaGuardTest` - the page's touchables include `lock_glance_torch`, wired.
  `LockGlanceLayoutTest` - the row's children and gravities, the button no taller than "Nødsamtale", the label's room
  at 320/360/411 dp, the button a fixed round size, the child order ending in the row.
- **Device checks owed**: §4 on the Jelly Star once `Torch` is wired (the emulator has no flash unit, so the button
  never shows there).

## Implementation status (2026-10-20, branch r6-a; the lock's button is implementer B's)

Built: `torch/TorchRules.kt` (pure: `torchCamera`, `torchStep`, `torchRefusedOnLock`, `torchLockButton`,
`TORCH_AUTO_OFF_MS`) and `torch/Torch.kt` (glue: the camera, `setTorchMode`, the `TorchCallback`, the auto-off alarm
to the non-exported `TorchOffReceiver` with `setExactAndAllowWhileIdle`, the call-edge rule, the unlock clearing a
refusal), started from `Application.initRest` after `MusicMedia.init`. The call hook is a pure
`callEdge(wasInCall, inCall)` in `MusicMediaPlan.kt`, which `callHoldStep` now uses too, and
`MusicCalls.addCallEdgeListener`, called from `MusicCalls.evaluate` on every edge before the music's own part. Home:
`GridTile.Flashlight(lit)`, `homeGrid(..., torch: TorchState?)` (default `null`), the adapter's two `when`s,
`HomeActivity`'s listener -> `renderGrid()`, `stateDescription` "på"/"av". The glyphs `torch_glyph_on`/`_off` come
from `material-symbols.sh` (`TORCH_GLYPHS`, hashes added). The API for B's lock button: `Torch.state`,
`Torch.toggle(context)`, `Torch.lockButtonShown` (a flash unit and no `CAMERA_DISABLED` refusal under the lock in
this process), `Torch.addListener`/`removeListener` (main thread).

Tests: `TorchRulesTest` (6: the alarm armed at every on and cancelled on every off path, exact and while idle, the
receiver not exported; a call's beginning from each of the three sources, and its end; no screen event in the torch;
the refusal and the unlock; no flash unit; nothing opened, no camera policy, nothing stored), `HomeModelTest` (the
tile before Settings, its state, its absence).

Deviations:
- The dark tile is the indigo tone (`kid_torch_tile` = `TONE_INDIGO`), so it reads apart from the slate Settings
  tile beside it. The note only said "the Vibb tones".
- The glyph hashes in `material-symbols.sh` were computed from the files as fetched once, because the sandbox
  couldn't run the script's own download. The reconstruction method was checked against an existing glyph's pinned
  hash (`bedtime`), and the script's next run verifies them.
- Code QA fix round (qa-r6-a-code M1): only the platform's DARK cancels the auto-off alarm, and a switch-off that
  fails (the alarm's, a call's) arms it again (`torchAlarmAfterOff`). So a torch lit on Home before the lock and
  refused off under the camera policy is tried again every 10 minutes; it goes off as soon as an off is accepted
  (at the latest at the next unlock, from Home), never staying lit for want of an alarm. Only a refused *on*-tap
  hides the lock's button; a refused off keeps it, so a lit torch keeps its off button. This only matters if the
  probe finds that the policy blocks the torch.

Device checks owed (all of §4 - the emulator has no flash unit): step 0's probe of the lock's button under the camera
policy (B and the user); steps 1-4 on the Jelly Star for the tile: on/off, the 10 minutes, an incoming cellular call
and a ringing VoIP call, screen off and on still lit, the camera app afterwards.
