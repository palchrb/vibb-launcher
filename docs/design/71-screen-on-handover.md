# 71 - The screen-on hand-over in android mode (design 32b)

The user, 2026-10-26, a `jellystar2` screen recording of three power-button cycles: "Er en slags resizing tidvis av
bakgrunnen ved power button cycles her; er dette noe vi kan addressere?" PLAN "Design 32b, now the screen-on hand-over
in android mode". Launcher only, one step (S). Paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`.
Builds on design 32 (the ground rule: the first frame after the display lights is the lock, or black), design 25
(the glance's opaque ground) and design 59d step K (as Android's face, Android's lock wallpaper *is* the glance's
ground with its scrim baked in). **Revised 2026-10-27 after its design QA** (`docs/review/qa-71-design.md`, 1/3/4/2).

## 0. In short

- **The step is a fixed 10 %.** The keyguard's frames before our first frame show the lock wallpaper (our own
  ground, baked) at Android's resting `config_wallpaperMaxScale`, 1.10 about the centre; the glance then covers
  them with the same ground as a plain centre crop at 1.0.
- **Recommended fix (D1-b): bake the face's lock wallpaper pre-shrunk by 1/maxScale**, so Android's 1.10 shows exactly
  our 1.0 framing (§3.2). Only the bitmap we hand Android changes; every screen of ours keeps today's framing. The
  alternative (D1-a, §3.3) draws every copy of ours at 1.10 instead.
- **The step is blocked on a pre-check by the user on the physical Jelly Star** (§3.1). The recording came from the
  user's desktop AVD (`jellystar2`, Pixel SystemUI); Unihertz's keyguard may not zoom the lock wallpaper at all.
- **The 12:04 frame in cycle 3 is most likely the emulator's host composer**, not our lock (§1). This design doesn't
  fix it. The phone's clip shows whether it exists there. `setRecentsScreenshotEnabled(false)` comes along as parity
  with Home, nothing more (§3.4).
- **Our window can't be the first frame** without `turnScreenOn` on the lock, which design 32 refused (§3.5, D2).

## 1. What the recording shows (measured 2026-10-27; reproduced by the QA)

`jellystar2` is the user's desktop AVD: Google Play Android 16, Pixel SystemUI, 480x854; `jelly36` is its twin. `in.webm` is the emulator console's host recording, which keeps
`FLAG_SECURE` windows (design 59b's `adb emu screenrecord`). The rate is variable, about 24 fps. Each lit frame was
compared with the settled glance frame by the best centre zoom and the mean absolute luma difference (MAD) on the
photo band y 300-740, x 60-420.

| Cycle | Lit frames before the glance | What they are | Zoom vs the glance |
| --- | --- | --- | --- |
| 1 (#32-34) | 3 frames, 185 ms | the bare keyguard (lock wallpaper, live 12:06 bar) | 1.100 (MAD 1.0) |
| 2 (#88) | 1 frame, 41 ms | the keyguard with SystemUI's clock | 1.100 (MAD 1.0) |
| 3 (#158-161) | 4 frames, 265 ms | #158-159 (176 ms): our ground under a **12:04** bar and SystemUI's gesture pill; #160 the keyguard with its clock; #161 bare | 1.000 (MAD 0.5); then 1.100 |

- **The keyguard's frames are our baked ground at 1.100**: no shift, and band means 44.2 vs 43.7, as the baked scrim
  predicts. No zoom animation: #32-34 are identical. So the "resizing" is the scale alone.
- **The direction is the reverse of PLAN's reading.** The keyguard is at the resting 1.10. Our copies are a plain
  centre crop at 1.0 (`ui/wallpaper/WallpaperRender.kt:59`, `centreCrop` `:99`), and nothing reads the scale.
- **The 12:04 image is most likely not ours.**
  - It comes *before* the keyguard's frames. Our task is made visible only after the occlude, so a snapshot of ours
    can't come first.
  - It carries SystemUI's status bar and gesture pill, which no task snapshot holds; and the lock is `FLAG_SECURE`
    (`lock/PinLockActivity.kt:513`), so WM's snapshot of it is app-theme already (design 32 §7.4).
  - Seen before: "one stale full-display frame at power-on, with an old status-bar time, was the emulator's host
    composer and not WM" (`docs/testing/emulator.md` §6k). It shows our ground at 1.0 because the host's old frame
    was the glance mid-leave (content hidden, 59d step B).

## 2. The sequence today (code)

**Screen-off.** `PinLockRuntime.screenReceiver` (`lock/PinLockRuntime.kt:922-951`) counts the screen-off and
dispatches `ScreenOff`. `lockUpAtScreenOff` (`:767-785`) then asks `lockUpStarts` (`lock/LockUpPlan.kt:40-56`). As the
face, that is `NONE` when the lock is marked on top (`:54`), else `LOCK` (`:55`): a start while dark (`show`,
`:1610-1636`). Nothing finishes or blanks the page. The log line is `Lock-up at screen-off: <start>` (`:783`).

**What Android does.** The lock has `showWhenLocked` (manifest `:174`) and no `turnScreenOn`. Asleep, it is paused,
stopped and made invisible (design 32 §3.2), and `GlancePanel.stop` (`:514`) ticks nothing. Android's keyguard is
locked as the face (59 §3.3). Its host window stays drawn across the sleep and is what the screen-on waits for
(design 32 §3.4). The lock wallpaper under it is at rest, 1.10.

**Screen-on.** The display lights on the keyguard (its clock, the lock wallpaper at 1.10). WM drops the sleep token
and resumes the lock, which occludes the keyguard; SystemUI's occlude hides its views (the bare frames, still 1.10).
`onResume` (`lock/PinLockActivity.kt:729`) runs `glance.resume` -> `renderNow` (`lock/GlancePanel.kt:430`, `:1366`)
before the first traversal, and our first frame lands at 1.0: **the step**. The `SCREEN_ON` backstop
(`PinLockRuntime.kt:981`) starts nothing as the face with the lock on top (`PinLockState.kt:69`, `:191`).

**Our live first frame is never stale.** The clock and date are `TextClock`s (`view_lock_glance.xml:53`) and read the
time when they become visible. Rows, alarm, "Neste" and the rule card are bound in `renderNow` before the first draw.
No pre-draw hold and no blanking at the lock-up are needed.

## 3. The fix

### 3.1 Pre-check on the physical Jelly Star (the user; the build waits for it)

- In android mode, with a photo as Home's and the lock's picture, film three power cycles with another phone's
  slow-motion camera (120-240 fps), held still on a stand.
- From the clip: one frame of the keyguard (SystemUI's clock, or the bare photo before ours) and one of the settled
  glance. `scripts/zoom-match.py` (§4) gives their zoom ratio. A ratio of 1.10 means the phone steps as the AVD does,
  and §3.2 is built. The camera's perspective is the same in both frames.
- **1.00: the phone has no step.** Neither D1 variant is built, and the item closes as an emulator-only effect (the
  parity line of §3.4 may still go in).
- **Any other value:** report it. The bake uses the value `config_wallpaperMaxScale` reports, which may not be what
  the keyguard applies.
- The same clip shows whether an old frame (a stale status-bar time) appears on the phone at all (§1).

### 3.2 D1-b (recommended): the face's lock wallpaper baked pre-shrunk

**`ui/wallpaper/WallpaperZoom.kt`** (new, small):
- `restingZoom(raw: Float?): Float` - pure: a finite `raw` within `1.0..1.25`, else 1.0 (today's bitmap).
- `val rest` - read once, lazily: `Resources.getSystem().getIdentifier("config_wallpaperMaxScale", "dimen",
  "android")` and `getFloat(id)`. `getFloat` exists since API 29 (minSdk 34) and throws for a non-float dimen, which
  is caught -> `null`; `@SuppressLint("DiscouragedApi")`.
- Logged once (tag `Wallpaper`): `Wallpaper zoom: config_wallpaperMaxScale 1.10`, or `... unreadable - 1.0`.

**`WallpaperApplier.setBoth`'s separate lock bitmap** (`ui/wallpaper/WallpaperApplier.kt:186-199`, the face's bake):
- Render it at 1.0 as now (photo, night mark or gradient, then the scrim). When `faceOn` and `rest > 1`, draw that
  bitmap once more onto a copy, with `canvas.scale(1/z, 1/z, w/2f, h/2f)` and a filtering paint.
- The centre then holds our whole 1.0 frame, which Android's 1.10 fills the display with. The outer ~4.5 % per side
  keeps the 1.0 render, seen only when SystemUI zooms out.
- One extra display-sized bitmap, briefly, once per bake, off the main thread (as today's bake).
- The shared branch (`lock == home.fill`, no scrim: a solid or a gradient) stays: a colour has no step, and a
  gradient's 10 % is not visible.
- **The key:** `wallpaperKey` gains `|lockzoom=1.10` while the face's bake is pre-shrunk
  (`WallpaperApplyPlan.kt:41-45`, beside `lockscrim=`). One re-bake per phone at the update; a changed value re-bakes.

**Untouched:** Home, the phone book, kid Settings, the glance, the picker cell and the home wallpaper
(`FLAG_SYSTEM`) keep today's framing and code; the face's unlock still fades our 1.0 glance onto Home's 1.0 copy (no
cross-fade); design 08's owed checks stay closed; own mode (its lock wallpaper isn't our ground, no keyguard frames).
**Costs:** a slight softening (Android upscales 1.1x, under the scrim); the margin frame shows when SystemUI zooms its
own face out (its shade before the first unlock); on a ROM whose keyguard doesn't zoom, the lock wallpaper sits 9 %
small inside its margin - hence §3.1.

### 3.3 D1-a (the alternative): every copy of ours at 1.10

- `WallpaperRender.GroundDrawable` (`:112`) gains `zoom: Float = 1f`. `draw` clips to `bounds` and draws the fill
  into the `RectF` grown about the centre: float edges, so 480x854 at 1.10 is 528x939.4, not rounded.
- `lockGround` (`lock/PinLockActivity.kt:1209`) and `WallpaperGround.apply`'s `FULL` (`ui/wallpaper/WallpaperGround.kt:57`)
  pass `WallpaperZoom.rest`. The picker cell (`KidSettingsActivity.kt:459`) and the bitmaps given to Android stay 1.0.
- The parent's photo is then cropped about 10 % tighter on every screen, as Android frames it.
- The lock's ground alone at 1.10 is no option: it cross-fades into Home's 1.0 on every android-mode unlock. Home's
  2026-10-25 "slightly different scale" was *likely* this 1.10 (design 08 left it open vs Q-7's metrics crop).

### 3.4 Parity with Home: no recents screenshot of the lock

`PinLockActivity.onCreate`: `setRecentsScreenshotEnabled(false)` beside the black task description - Home's line
(`ui/HomeActivity.kt:241`). It changes nothing that is known today: `FLAG_SECURE` already makes the snapshot
app-theme, the lock is out of Recents, and the keyguard cover never arms. It states design 32's assumption in code,
so it no longer rests on `FLAG_SECURE` alone. It is not the cycle-3 fix.

### 3.5 Our window first: not possible

- The keyguard is drawn first by construction: the screen-on waits for its host window (design 32 §3.4).
- Design 32 §7.1: "an activity can't be drawn and shown while dark without lighting the screen at every lock-up.
  That is rejected (pocket, battery, design 17 qa-16-17-code #2)." `VoipCallGuardTest.kt:93` pins the lock without
  `turnScreenOn`.
- `setShowWhenLocked` timing is not the cause: the flag is in the manifest.
- What remains after §3.2: 1-4 keyguard frames, the bare ones identical to our ground, and at most a frame or two
  of SystemUI's clock where ours appears (59d step L's twin).
- Only if §4 shows them, as their own items: a resume-to-first-frame over one frame (cycle 1's 185 ms); a `LOCK`
  start logged before a wake (design 32 §4.4's lost `lockOnTop` mark).

## 4. Measurement (`docs/testing/emulator.md`, new §6x "The screen-on hand-over (design 71)")

**The capture paths.**
- `adb shell screenrecord` records the `FLAG_SECURE` glance black (emulator.md §6c item 10) and can't follow a
  screen-off (INVALID_LAYER_STACK). It is not used.
- `jelly36`: `adb emu screenrecord start --time-limit 60 /tmp/h.webm`, the host recording (59b), with design 59b's
  logcat line beside it (`AndroidLock PinLock PinLockActivity KeyguardViewMediator` and the `wm_*` events).
- The phone: the slow-motion camera clip of §3.1.

**`scripts/zoom-match.py`** (new, tracked, about 40 lines, PIL + numpy):
- `zoom-match.py REF.png FRAME.png [--band y0 y1 x0 x1] [--range lo hi step]` prints the best centre zoom of REF
  that matches FRAME, the correlation and the MAD. This is §1's method: affine bicubic about the centre, 0.95-1.20
  step 0.0025.
- `--selftest` zooms a synthetic texture by 1.1 and asserts that 1.100 ± 0.003 comes back.
- Listed in the root `CLAUDE.md`'s `scripts/` line. Run by hand: no CI path reads it.

**The run on `jelly36`**, before (main) and after. Android mode with the face (Child PIN from the PWA), a photo as
Home's and the lock's picture, gesture navigation; one run with kiosk on and one with it off.
1. Seed the old-frame case: unlock through Android's pad to Home and wait over a minute.
2. Six power cycles: `adb shell input keyevent KEYCODE_POWER`, 3 s dark, power, 3 s lit.
3. Frames with `ffmpeg -vsync 0`. For every lit frame from power-on to the settled glance: its kind (black /
   keyguard with clock / bare keyguard / our ground / other), `zoom-match.py` against the settled glance, its
   status-bar time against the live one; and each cycle's `Lock-up at screen-off:` line.
4. Any frame with an old status-bar time: repeat once with the other `-gpu` mode (host vs `swiftshader_indirect`). A
   frame that goes away there is the emulator's (emulator.md §6k).
5. One run with 3-button navigation (the bake is `displayMetrics`-sized; design 08's Q-7 is still owed).
6. After the change, frame-step the face's unlock to Home, and an own-mode cycle with its unlock. Both must be
   unchanged.

**Done.**
- `jelly36`: every bare keyguard frame matches the settled glance at zoom 1.000 ± 0.005 with MAD ≤ 3 on the band.
- No frame of ours or of the keyguard with an old status-bar time in both `-gpu` modes. Black frames pass
  (design 32); clock frames are counted, not gated.
- The phone: the user's slow-motion clip after the update shows no jump; `zoom-match.py` on its keyguard and glance
  frames gives 1.00 ± 0.01.

## 5. What is not touched

- The keyguard stays: no `requestDismissKeyguard`, no `setKeyguardDisabled` change, no `turnScreenOn`, no wake lock.
  Nothing runs while dark that doesn't today.
- Gestures, lock task and window flags stay (the user's rule: ask before removing any gesture). The only flag-like
  change is §3.4's line.
- With D1-b: every ground of ours, `HomeGround`, the home wallpaper, own mode, design 32's lock-up plan, the glance's
  layout and its clock twin (with D1-a, own mode shares the new framing).

## 6. Step and size

One step, branch `screen-on-handover`, S, **after §3.1's pre-check and the owner's D1 pick**:
- D1-b: `WallpaperZoom.kt`, the pre-shrink in `setBoth`'s lock branch and the `lockzoom=` key part.
- D1-a: `WallpaperZoom.kt`, `GroundDrawable`'s `zoom` and its two callers.
- Either way: the §3.4 line, `scripts/zoom-match.py`, emulator.md §6x, and `launcher/CLAUDE.md`'s "System
  wallpaper"/"Drawing" paragraphs.

**Tests.**
- `WallpaperZoomTest`:
  - `restingZoom`: `null`, NaN, 0.9 and 2.0 give 1.0; 1.10 gives 1.10.
  - D1-b: the key gains `|lockzoom=1.10` only as the face with z > 1, and `1.0` adds nothing (in the existing
    `wallpaperKey` tests).
  - D1-a: the grown `RectF` of 480x854 at 1.10 is (-24, -42.7, 504, 896.7).
- **Source guards that pin today's lines.**
  - D1-a must update `HomeGroundTest.kt:55` (`GroundKind.FULL -> WallpaperRender.GroundDrawable(...)`) and
    `LockUpTest.kt:192` (`GroundDrawable(state.lock.fill, ...)`); don't drop them.
  - Both variants add `setRecentsScreenshotEnabled(false)` to the lock's guard in `LockUpPlanTest.kt:333-337`, as
    Home's.
- No test for the platform calls: §6x's run is the proof. Then a code QA as usual.

## 7. Decisions for the owner

- **D1 - how the step goes** (after §3.1 reads 1.10):
  - **D1-b, recommended**: bake the face's lock wallpaper pre-shrunk. Every screen keeps today's framing; android
    mode only; one code path; no cross-fade. Costs: a one-time re-bake, slight softening under the scrim, and a
    margin frame visible only when SystemUI zooms its own face out.
  - **D1-a**: every copy of ours at 1.10. Android's framing everywhere and no margin frame, but the photo is about
    10 % tighter on Home, the phone book and kid Settings.
- **D2 - accept the remaining keyguard frames** (recommended): after the fix they show at most SystemUI's clock for
  a frame or two where ours appears. The only cure, `turnScreenOn` on the lock, lights the screen at every lock-up
  (design 32 §7.1, pinned by `VoipCallGuardTest.kt:93`).
