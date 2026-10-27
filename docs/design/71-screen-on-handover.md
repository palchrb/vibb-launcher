# 71 - The screen-on hand-over in android mode (design 32b)

The user, 2026-10-26, a `jellystar2` screen recording of three power-button cycles: "Er en slags resizing tidvis av
bakgrunnen ved power button cycles her; er dette noe vi kan addressere?" PLAN "Design 32b, now the screen-on hand-over
in android mode". Launcher only, one step (S). Paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`.
Builds on design 32 (the ground rule: the first frame after the display lights is the lock, or black), design 25
(the glance's opaque ground) and design 59d step K (as Android's face, Android's lock wallpaper *is* the glance's
ground with its scrim baked in).

## 0. In short

- **The step is ours, and it is a fixed 10 %.** Android shows its wallpaper at rest at `config_wallpaperMaxScale`
  (1.10, about the display's centre); our copy (the glance's ground, Home's, the phone book's, kid Settings') is a
  plain centre crop at 1.0. The keyguard's frames before our first frame show the lock wallpaper - our own ground,
  baked - at 1.10; then the glance covers it at 1.0. **Fix: every full-screen copy is drawn at Android's resting
  scale** (§3.1): the keyguard's bare frames become pixel-identical to the glance's ground.
- **The stale frame was a stored image, not our live page**: no clock, no rows, only the ground at our scale under a
  status bar two minutes old. **Fix: no stored picture of the lock** - `setRecentsScreenshotEnabled(false)`, as Home
  has had since design 32 (§3.2); the stand-in is then design 32's black.
- **Our window cannot be the first frame** in android mode without `turnScreenOn` on the lock, which design 32
  refused (it wakes the phone). The keyguard's 1-4 frames stay; after §3.1 only SystemUI's own clock in them is
  visible, in the place of ours (59d step L's twin).

## 1. What the video shows (measured 2026-10-27, `in.webm`, 480x854, variable rate ~24 fps)

Frames were extracted with `ffmpeg -vsync 0`; each lit frame was compared with the settled glance frame by the best
centre zoom (0.86-1.16, step 0.0025, correlation over the photo band y 300-740) and the mean absolute difference.

| Cycle | Lit frames before the glance (ms) | What they are | Zoom vs the glance |
| --- | --- | --- | --- |
| 1 (#32-34) | 3 frames, 185 ms | the bare keyguard (lock wallpaper, live 12:06 bar) | 1.10 |
| 2 (#88) | 1 frame, 41 ms | the keyguard with SystemUI's clock | 1.10 |
| 3 (#158-161) | 4 frames, 265 ms | #158-159: **our ground** under a **12:04** bar, nothing else (176 ms); #160 the keyguard with its clock; #161 bare | 1.00 (MAD 0.3); then 1.10 |

- The keyguard's frames are our baked ground: at zoom 1/1.102, no shift, MAD 1.7 and the same mean colour (59.3 vs
  59.6) - so the "resizing" is the scale alone. No frame shows a zoom in progress: SystemUI does not animate the
  wallpaper at this wake; it sits at rest.
- **Two corrections to PLAN's reading.** (1) The direction is the other way round: the keyguard is at the resting
  1.10, our copy at 1.0 (`ui/wallpaper/WallpaperRender.kt:25` draws a plain centre crop; no code reads the scale).
  (2) The cycle-3 "stale page" holds no page content - no clock, date, alarm or Nødsamtale. The old 12:04 is the
  status bar, baked into the image: cycles 1 and 2 had already drawn 12:06 live, so it can't be a live window that
  wasn't redrawn. It is an image stored at 12:04, when the page last left with its content hidden - 59d step B's
  leave as the face shows only the ground (`lock/GlancePanel.kt:1110`, `lock/PinLockActivity.kt:1143`). The most
  likely store is WM's task snapshot of the lock's task, shown as the wake's stand-in when the wake counts as a task
  switch (design 32 §3.5). The SF layer trace in §4 names the layer.

## 2. The sequence today (code)

**Screen-off.** `PinLockRuntime.screenReceiver` (`lock/PinLockRuntime.kt:922-951`) counts the screen-off and
dispatches `ScreenOff`. `lockUpAtScreenOff` (`:767-785`) then asks `lockUpStarts` (`lock/LockUpPlan.kt:40-56`). As the
face, that is `NONE` when the lock is marked on top (`:54`), else `LOCK` (`:55`): a start of the lock while dark
(`show`, `:1610-1636`, `NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION`). Nothing finishes or blanks the page. The log
line is `Lock-up at screen-off: <start>` (`:783`).

**What Android does.** The lock has `showWhenLocked` (manifest `:174`) and no `turnScreenOn`. Asleep, it is paused,
stopped and made invisible, and its surface goes (design 32 §3.2; `onStop` -> `GlancePanel.stop`, `:514`, ticks
nothing). Android's keyguard is locked as the face (59 §3.3) and is no longer occluded while dark. Its host window
stays drawn across the sleep and is what the screen-on waits for (design 32 §3.4). The wallpaper under it is at
rest, 1.10.

**Screen-on.** The display lights on the keyguard: **stock frame 1**, its clock at the lock wallpaper's 1.10. WM drops
the sleep token, makes the lock visible and resumes it. It occludes the keyguard, and SystemUI's occlude hides the
keyguard's views: the **bare frames**, still 1.10. Our `onResume` (`lock/PinLockActivity.kt:729`) then runs
`glance.resume` -> `renderNow` (`lock/GlancePanel.kt:430`, `:1366`) before the first traversal. When our first frame
lands, the glance is at 1.0: **the step**. The `SCREEN_ON` broadcast's backstop (`PinLockRuntime.kt:981`) starts nothing
as the face with the lock on top (`PinLockState.kt:69`, `:191`).

**Why our live first frame is never stale.** The clock and date are `TextClock`s (`view_lock_glance.xml:53`), which
read the time when they become visible. Rows, alarm, "Neste" and the rule card are bound in `renderNow` before the
first draw. So no pre-draw hold or blanking at the lock-up is needed. A stale frame can only be a *stored* image.

**Why a stored image can show.** A wake that WM treats as a task switch onto the lock (e.g. a `LOCK` start while
dark after the mark was lost, design 32 §4.4) shows the lock task's snapshot until our first frame. Design 32 counted
on that snapshot being black: `FLAG_SECURE` (`PinLockActivity.kt:513`) should make WM use the app-theme snapshot, and
the task description is black (`:516-522`). Cycle 3 says this ROM stored a real picture anyway. The same recording
also captured our `FLAG_SECURE` window live. Home doesn't rely on that: it calls `setRecentsScreenshotEnabled(false)`
(`ui/HomeActivity.kt:241`). The lock doesn't.

## 3. The fix, in order of value

### 3.1 Every full-screen copy at Android's resting scale (the step, every cycle)

- `ui/wallpaper/WallpaperZoom.kt` (new, small):
  - `restingZoom(raw: Float?): Float` - pure: `raw` within `1.0..1.25` and finite, else 1.0 (today's look).
  - `val rest` - read once, lazily: `Resources.getSystem().getIdentifier("config_wallpaperMaxScale", "dimen",
    "android")`, then `getFloat(id)`; any failure -> `null`.
  - `zoomedEdges(left, top, right, bottom, zoom)` - pure, on ints: the rect grown about its centre, rounded outward.
- `WallpaperRender.GroundDrawable` (`WallpaperRender.kt:112`) gains `zoom: Float = 1f`. Its `draw` clips to `bounds`
  and draws the fill into the grown rect. A centre crop into a larger rect is exactly Android's surface scale: a
  photo, a gradient and the night mark are all scaled 1.10 about the centre, as Android shows the same baked bitmap.
  The scrim stays a full rect.
- **Callers.** `lockGround` (`lock/PinLockActivity.kt:1209`: the keypad stage and the glance) and
  `WallpaperGround.apply`'s `FULL` (`ui/wallpaper/WallpaperGround.kt:57`: Home, the phone book, kid Settings) pass
  `WallpaperZoom.rest`.
- **Unchanged.** `SCRIM` has no fill. Kid Settings' picker cell (`KidSettingsActivity.kt:459`) is a thumbnail and
  stays 1.0. The bitmaps we *give* Android (`WallpaperApplier`, the face's baked lock wallpaper) stay 1.0, because
  Android applies its 1.10 itself. The zoom is a per-boot constant, so the ground keys need no change.
- **Home's 2026-10-25 fix, and why the two agree.** Home keeps its own copy permanently (`homeGroundDue`) and never
  hands over to Android's wallpaper. The "slightly different scale" its 24 fps video showed was this same 1.10. Home
  keeps its copy (the 2026-10-24 black frames are still the reason; `HomeGround` is untouched), and the copy is now
  drawn at Android's scale. So the face's unlock agrees end to end: the glance fades out in place over the
  wallpaper (RC3-2) onto Home, all three at 1.10. A glance-only change would have broken that: the glance at 1.10
  cross-fading into Home at 1.0 on every unlock (decision D1).
- **Risk.** Another ROM's keyguard may not scale the lock wallpaper (e.g. a separate lock engine on Android 16
  Pixel). Its frames would then be at 1.0 and ours at 1.10: a new step. Measured on `jelly36` first (§4). If it
  differs from the Jelly Star, stop and report: do not branch per device in this step.

### 3.2 No stored picture of the lock (the stale frame)

`PinLockActivity.onCreate`: `setRecentsScreenshotEnabled(false)` next to the black task description. This is the
same line and the same reason as Home (`HomeActivity.kt:241`). WM then keeps only the app-theme snapshot (black) for
the lock's task, on every ROM and in both modes. Own mode's leave doesn't hide the content, so a real snapshot there
would carry old rows and an old clock. A wake that is a task switch shows black (design 32's rule: allowed) instead
of an older picture. The lock is excluded from Recents and runs in lock task, so nothing else changes. If §4's layer
trace names another source for the 12:04 image (not a snapshot), this line still stands, and that source is reported
as a residual for the owner. No app can remove a system-level capture.

### 3.3 Our window first: not possible, and not needed after 3.1

- The keyguard is drawn first by construction (design 32 §3.4: the screen-on waits for its host window). Our lock
  can't stay visible through the sleep without `turnScreenOn`, which wakes the screen whenever the lock is made
  visible after a start. Design 32 §3.2/§7.1 and qa-16-17-code #2 refused it. Restarting the activity at the lock-up
  wouldn't help either.
- `setShowWhenLocked` timing is not the cause: the flag is in the manifest, and occlusion is decided when WM makes
  us visible after the sleep token.
- What remains after 3.1:
  - 1-4 keyguard frames, the bare ones identical to our ground.
  - At most a frame or two of SystemUI's clock where ours will be (same place, 59d step L), before ours replaces it.
- **Not in this step, only if §4 shows it.**
  - Our resume-to-first-frame taking over one frame (cycle 1's 185 ms): trim what runs in `onResume`.
  - Logs with a `LOCK` start before a wake that showed a stand-in: the lost `lockOnTop` mark (design 32 §4.4) as
    its own small item.

## 4. Measurement (`docs/testing/emulator.md`, new §6x "The screen-on hand-over (design 71)")

Run on `jelly36` (swiftshader draws our views) before the change (on main) and after. Android mode needs the face
(Child PIN set from the PWA), and a photo as Home's and the lock's picture. Use gesture navigation; run kiosk on
and kiosk off once each.

1. `adb shell screenrecord --bit-rate 8000000 /sdcard/h.mp4` (no `--bugreport`: its overlay sits on the frames) with
   `adb logcat -v time` beside it.
2. Seed the stale case: unlock through Android's pad to Home, then wait over a minute (the clock must change). Then
   do six power cycles: `adb shell input keyevent KEYCODE_POWER`, 3 s dark, power, 3 s lit.
3. Frames with `ffmpeg -vsync 0`. List timing and diffs per frame as `vidsum.py` does. For every lit frame from the
   first one after power-on to the glance's settled frame, note:
   - its kind: black / keyguard with clock / bare keyguard / our ground / other;
   - its best centre zoom against the settled glance and its MAD (§1's method: zoom 0.86-1.16 step 0.0025,
     correlation over the photo band below the clock and above Nødsamtale);
   - its status-bar time against the live one.
   Also note the launcher's `Lock-up at screen-off:` line for each cycle.
4. Any frame that is neither black, the keyguard nor the live glance: take design 32 §5's SurfaceFlinger layer trace
   of one such wake. The layer's name (a snapshot or starting window, ColorFade, a Shell screenshot) settles §1's
   store.
5. After the change, also check:
   - the face's unlock to Home (frame-step the fade: no zoom step);
   - Home -> phone book -> kid Settings and back, and Back to Home (no step, no 10 % jump between screens);
   - own mode: power cycles and its unlock to Home.
6. The Jelly Star (the user): the built-in recorder, an unlock first and a minute's wait, then three power cycles.
   Read the frames the same way.

**Done** (both devices):
- Every lit frame's background is at zoom 1.000 ± 0.005 against the glance, and every bare keyguard frame has a MAD
  ≤ 3 on the photo band: no visible step.
- 0 frames with an older status-bar time or with stale content. Black frames pass (design 32).
- The keyguard-with-clock frames are counted and reported, not gated: SystemUI's timing.
- No step on the unlock to Home, between Home and its sibling screens, or in own mode.

## 5. What is not touched

- The keyguard stays: no `requestDismissKeyguard`, no `setKeyguardDisabled` change, no `turnScreenOn`, no wake lock
  - nothing runs while dark that doesn't today.
- Gestures stay (the user's rule: ask before removing any). Nothing here touches a gesture, lock task or a window
  flag besides §3.2.
- The bitmaps given to Android (`WallpaperApplier`, the face's lock wallpaper), `HomeGround`'s logic, design 32's
  lock-up plan, the glance's layout and its clock twin.
- Both modes are covered by one change: own mode has no keyguard frames, but its unlock to Home stays seamless
  because every copy shares the scale.

## 6. Step and size

One step, branch `screen-on-handover`, S:
- `WallpaperZoom.kt`, `GroundDrawable`'s `zoom`, the two callers, and the one line in `PinLockActivity.onCreate`;
- `docs/testing/emulator.md` §6x, and `launcher/CLAUDE.md`'s "Drawing" paragraph (the copy is drawn at Android's
  resting scale; the lock has no recents screenshot).

Tests: `WallpaperZoomTest` -
- `restingZoom`: `null`, NaN, 0.9 and 2.0 give 1.0; 1.10 gives 1.10;
- `zoomedEdges(0, 0, 480, 854, 1.10f)` gives `(-24, -43, 504, 897)`, and zoom 1 is the identity.

No test for the platform call. §6x's run is the proof. Then a code QA as usual.

## 7. Decisions for the owner

- **D1 - Home's photo framed as Android frames it** (recommended): every full-screen copy at 1.10, so the parent's
  photo on Home, the phone book and kid Settings is cropped about 10 % tighter than today - exactly what the keyguard
  and Android's own wallpaper show. The alternative, only the lock's ground at 1.10, keeps Home's framing but brings
  a zoom cross-fade on every android-mode unlock to Home.
- **D2 - the remaining keyguard frames** (recommended: accept): after §3.1 they show at most SystemUI's clock for a
  frame or two where ours appears. The only way to remove them, `turnScreenOn` on the lock, wakes the phone and was
  refused in design 32.
