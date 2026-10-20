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
2. **Home**: a `GridTile.Flashlight` before `GridTile.Settings` in `HomeModel` (always last two: Flashlight,
   Settings), drawn like the Settings tile (the round tile, the Vibb tones - never yellow): the Material Symbols
   torch glyph (`flashlight_on`/`flashlight_off`, `scripts/material-symbols.sh`, Apache-2.0 like the others), the
   label "Lommelykt"/"Flashlight", the lit state a filled peach disc with the `bg` glyph. A tap toggles; the tile
   re-renders from the callback.
3. **The lock**: a round button beside Emergency call on the glance page (`view_lock_glance.xml`'s bottom row), same
   glyph and lit look, usable while LOCKED without the PIN - it protects nothing and reveals nothing (design 25's
   rule: the page's only actions change no state the lock guards). It is part of `glanceFit`'s `fixed` height like
   the Emergency button.
4. **Rules** (`TorchRules`, pure, JVM-tested): off by itself after 10 minutes (an `AlarmManager`
   `setAndAllowWhileIdle` to a `TorchOffReceiver`, re-armed at every on; a Handler alone dies with the process);
   off when a call begins (the camera is a call's; the hook is where `CALL_HOLD`'s source is - `KidInCallService` /
   the audio-mode call rule) and never turned back on by the call's end; **never off at screen-off** (as iPhone);
   off at a process restart (the state can't be trusted; the callback re-reads it).
5. **The camera policy while LOCKED** (design 25b: cameras disabled by `setCameraDisabled` while the PIN lock is
   LOCKED, `CameraLock.syncCameraPolicy`). `setTorchMode` under that policy most likely fails with
   `CameraAccessException.CAMERA_DISABLED` - the device check decides (§4 step 0; the emulator has no flash). Built
   both ways: `Torch.on()` tries; on `CAMERA_DISABLED` it asks `CameraLock.torchLift(context, lit = true)`, which
   lifts the camera policy for exactly the time the torch is lit (`cameraPolicyDisabled(locked, call, torchLit)`:
   `false` while lit) and puts it back at off (every off path: the tap, the 10 minutes, the call, the process
   restart's re-read). While lifted the camera **apps** stay suspended (CameraLock's suspension list is untouched), so
   nothing but the flash unit is reachable through the lift. The one path the lift reopens is the lock screen's
   camera gesture (double power / the keyguard swipe) landing on a suspended camera app - design 25b's SystemUI crash
   - so §4 checks it explicitly; if it crashes, the lift is dropped and the lock's button says "Lommelykten virker
   ikke mens telefonen er låst" (the Home tile keeps working), decided by the check, not by code.
6. **Look**: calm, adult (launcher-look rule): the tile and the button in the Vibb tones, the lit state a filled
   disc, no animation.

## 2. Security

Nothing new is reachable: the torch API opens no camera, the lock's button starts no activity, the lift (if it is
needed) keeps every camera app suspended and lasts only while lit; the alarm's receiver is not exported. No string
from any app is shown. `LockGlanceGuardTest` gets the button: it must not open anything.

## 3. Tests (required)

`TorchRulesTest` (JVM): the auto-off at 10 minutes from the last on; off at a call's begin, not re-lit at its end;
screen-off changes nothing; the lift is asked only while LOCKED and only while lit, and released on every off path;
no flash unit = no tile, no button. `HomeModelTest`: the tile order (…, Flashlight, Settings) and its absence without a
flash. `LockGlanceGuardTest`: the button opens nothing. `GlanceFitTest`: the fixed height includes the button.

## 4. Device checks (the Jelly Star - the emulator has no flash)

0. The probe first, in the debug launcher: Home tile on/off unlocked; then LOCKED: the lock's button - lights (no
   lift needed) or fails with CAMERA_DISABLED (the lift path runs) - record which.
1. Lit from the lock, the camera gesture (double power): what happens - the lock stays, no SystemUI crash?
2. 10 minutes: off by itself; an incoming call: off; screen off and on: still lit.
3. Unlock while lit from the lock: still lit; lock again: still lit; the tile and the button agree.
4. A real Jelly Star photo afterwards: the camera app works again (the policy restored).
