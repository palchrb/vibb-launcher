# Design QA 71 - the screen-on hand-over in android mode (`docs/design/71-screen-on-handover.md`, e62854b9)

QA 2026-10-27, read-only, no emulator. Paths under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless noted.
## Verdict: revise first - 1 High, 3 Medium, 4 Low, 2 Nit

The zoom reading is right (reproduced: the keyguard's frames are our baked lock wallpaper at 1.100) and §3.1 is sound
code. Before the build: §4 can't produce its gate's numbers (H1); cycle 3's "stale page" is most likely the emulator's
host composer, so §3.2 fixes nothing (M1); the case rests on the user's emulator, not the Jelly Star (M2); D1 has a
cheaper option that keeps every screen's framing (M3).

## Verified (file:line)

- **Copies at 1.0, nothing reads the scale.** `grep -rn MaxScale launcher/app/src`: nothing. `WallpaperRender.draw`
  centre-crops into `bounds` (`ui/wallpaper/WallpaperRender.kt:59`, `centreCrop` `:99`); `GroundDrawable` (`:112-126`)
  draws into `bounds`. Callers: `lockGround` (`lock/PinLockActivity.kt:1208-1210`: keypad and glance),
  `WallpaperGround.apply` FULL (`ui/wallpaper/WallpaperGround.kt:57`: Home, phone book, kid Settings, all `ownGround`,
  `:28-39`), the picker cell (`ui/kidsettings/KidSettingsActivity.kt:459`).
- **Home's 2026-10-25 fix** (design 08 "The copy stays", `launcher/CLAUDE.md:176`, PLAN:1470): Home and its siblings
  keep a permanent copy and never hand over; the note keeps that and only changes the scale, so the two agree. That
  the "hair" shift in that video *was* this 1.10 is not shown - design 08 says "its wallpaper zoom, or Q-7's
  display-metrics crop" (L3).
- **The baked lock wallpaper** (59d step K): `WallpaperApplier` renders `lockScreenFill(.., glance)` plus the scrim
  into a `displayMetrics`-sized bitmap at 1.0 (`ui/wallpaper/WallpaperApplier.kt:65-66`, `:176-200`, `:246-247`);
  Android adds 1.10. **Known**: the `jellystar2` video (Google Play Android 16, Pixel SystemUI; `jelly36` is its twin)
  shows the lock wallpaper at rest at 1.100. **Assumed**: the physical Jelly Star does the same - Unihertz's own
  Android 16 ROM (design 56:36-38), never recorded. Older AOSP drew a separate lock wallpaper in SystemUI's backdrop,
  unzoomed. Reading `config_wallpaperMaxScale` follows a ROM's *value*, not whether its keyguard applies it.
- **Snapshot state**: the lock has `FLAG_SECURE` before content (`lock/PinLockActivity.kt:513`, `LockUpPlanTest.kt:334`)
  and the black task description (`:516-522`), no `setRecentsScreenshotEnabled`; Home has it (`ui/HomeActivity.kt:241`),
  as does `ui/settings/SettingsActivity.kt:57`. Design 32 itself: FLAG_SECURE means the snapshot is "never a
  screenshot - `wm/ActivityRecord.java:5615` `shouldUseAppThemeSnapshot`" (§1 table, §7.4).
- **Design 32 §7.1** (`32-lock-flash.md:299-300`): "an activity can't be drawn and shown while dark without lighting
  the screen at every lock-up. That is rejected (pocket, battery, design 17 qa-16-17-code #2)." Still holds (AOSP
  unchanged; `VoipCallGuardTest.kt:93` pins the lock without it).
- **Android-mode unlock to Home**: Android's pad over our opaque glance, then `leave()` hides the content
  (`lock/PinLockActivity.kt:1131-1150`, `GlancePanel.leaving` `lock/GlancePanel.kt:1107-1111`, handle gone `:1131`)
  and the ground fades onto Home's copy. A glance-only 1.10 would cross-fade two scales there: the note is right.

## Frames, re-checked (`pb71/f`; my script `scratchpad/qa71/zoomcheck.py`)

Glance frame scaled about the centre (affine, bicubic), zoom 0.95-1.20 step 0.0025, correlation + luma MAD on band
y 300-740, x 60-420. Keyguard #33 vs glance #40: **best 1.1000**, corr 0.9975, MAD 1.04 (at 1.000: corr 0.830, MAD
7.81); #32 and #34 identical (no animation); #88 vs #92 1.1000 / MAD 1.03; #160, #161 vs #165 1.1000 / MAD 1.04. Band
means 44.2 vs 43.7 (consistent with the baked scrim). Stale #158/#159 vs #165: 1.000, MAD 0.51; first glance #35 vs
#40: 1.000, MAD 0.69. Cycle 3 order (pts): #158 6.753 s, #159 6.887 (12:04 bar, SystemUI's gesture pill) -> #160
6.929 keyguard + clock -> #161 6.976 bare -> #162 7.018 glance.

## Findings

**H1 - §4's method can't measure its gate.** `adb shell screenrecord` records the FLAG_SECURE glance black
(emulator.md:442-443), so "zoom against the settled glance" has no reference; PLAN:1494 also records that it "can't
follow a screen-off (INVALID_LAYER_STACK)". The user's `in.webm` is the emulator console's host recording, which keeps
secure windows (design 59b:206); on the Jelly Star the built-in recorder (MediaProjection) blacks the glance too.
*Fix*: zoom on `jelly36` via `adb emu screenrecord start --time-limit 60 /tmp/h.webm`; stale frames judged on a second
`-gpu` mode too; on the Jelly Star, bare keyguard frames from the built-in recorder vs a screenshot of Home (not
secure; same photo when the lock's picture is Home's), plus a slow-motion camera clip. Put the matcher (≈20 lines) or
`vidsum.py`'s path in §6x - neither is tracked.

**M1 - Cycle 3 is not shown to be a task snapshot; §3.2 changes nothing.** The 12:04 image comes *before* the keyguard
frames (#158-159, then #160-161); the lock's snapshot could only show once our task is made visible, after the
occlude. It carries SystemUI's status bar and gesture pill (ours is gone while leaving, `GlancePanel.kt:1131`), which
no task snapshot holds. The lock is FLAG_SECURE, so its snapshot is already app-theme (design 32's own reading). The
project has seen exactly this: "one stale full-display frame at power-on, with an old status-bar time, was the
emulator's host composer and not WM" (emulator.md:1146-1148; PLAN:1488-1493). *Fix*: rewrite §1's correction (2) and
§3.2 - likeliest source the host composer; reproduce on `jelly36` (swiftshader) before blaming WM; keep the one line
only as parity with Home, not as a fix. Correct PLAN:547 the same way.

**M2 - Measured on an emulator, shipped to a phone.** §1/§4.6 call the source "the Jelly Star", but `jellystar2` is the
user's desktop AVD (PLAN:974-986), and `jelly36` is its twin, so §3.1's "if `jelly36` differs from the Jelly Star,
stop" can't fire before the build. *Fix*: a **pre-check by the user** on the physical phone before any crop ships
(built-in recorder, three power cycles, judged against Home's screenshot as in H1). Keyguard at 1.0 there -> neither
D1 variant is wanted.

**M3 - D1 misses a cheaper option, D1-b**: bake the face's lock wallpaper **pre-zoomed by 1/maxScale** (draw the fill
at 1.0 for the margins, then again scaled 1/z about the centre; `WallpaperApplier`'s lock render only, key gains
`lockzoom=`). At rest Android's 1.10 shows exactly our 1.0 framing. Gains: Home, phone book, kid Settings and glance
stay as they are; android mode only; one code path; no cross-fade; design 08's owed checks not reopened. Costs: a
one-time re-bake; a slight softening (1.1x upscale, under the scrim); the outer ~4.5 %/side shows when SystemUI zooms
the lock wallpaper out (its shade over its own face, e.g. before the first unlock); on a ROM that doesn't zoom, the
lock wallpaper sits 9 % small.

**L1** - Source guards pin the exact constructors: `HomeGroundTest.kt:55` and `LockUpTest.kt:192` break - list them in
§6 to update, not drop; add the lock's `setRecentsScreenshotEnabled(false)` to `LockUpPlanTest.kt:337`'s guard.
**L2** - §4 is gesture navigation only; the bake is `displayMetrics`-sized (`WallpaperApplier.kt:65`) and design 08's
Q-7 (3-5 % with 3-button) is still owed - add one 3-button run.
**L3** - Word "Home's 2026-10-25 step was this 1.10" as likely. **L4** - `WallpaperZoom.rest`: log the value once
(tag `Wallpaper`); `getFloat` is API 29+, throws for a non-float dimen (caught); lint DiscouragedApi on `getIdentifier`.
**Nit 1** - `zoomedEdges` on ints rounds outward (528x940 vs 528x939.4); `drawBitmap(Rect, RectF)` and the gradient
take floats - use a `RectF`. **Nit 2** - §1 cites `WallpaperRender.kt:25` for the crop; it is `:59`/`:99`.
**Side effects of `setRecentsScreenshotEnabled(false)`**: none (already app-theme; out of Recents; `KeyguardCover`
never arms since round 0b, emulator.md ~1132); same in both modes.

## Decisions

- **D1 - recommend D1-b (M3), after M2's pre-check**: fixes the step and leaves the parent's photo framed as today on
  every screen. Take the note's D1 (every copy at 1.10) only if the owner wants Android's framing everywhere or D1-b's
  margins look bad under SystemUI's shade. The note is right that glance-only 1.10 cross-fades two scales on every
  android-mode unlock.
- **D2 - accept** (agreed): what remains is SystemUI's clock in our clock's place (59d step L) for a frame or two; the
  only cure, `turnScreenOn`, lights the screen at every lock-up - design 32 §7.1 still forbids it.

## What the implementer's brief must carry

1. The owner's D1 pick (D1-b or every copy at 1.10), and the user's physical-Jelly-Star pre-check first (M2).
2. D1-b: only the face's lock bake changes (fill at 1.0, then at 1/z about the centre; key `lockzoom=`); pure tests for
   `restingZoom` and the pre-zoom rect; Home's, the glance's and the picker's grounds untouched. Note's D1:
   `GroundDrawable(zoom)` with float edges, both callers, picker at 1.0, `HomeGroundTest:55`/`LockUpTest:192` updated.
3. `setRecentsScreenshotEnabled(false)` on the lock as parity only (guarded), never called the cycle-3 fix.
4. §6x with H1's capture paths, the matcher's code or path, one 3-button run, kiosk on/off, own mode's unlock, before
   (main) and after. No turnScreenOn, wakelock, gesture, keyguard or lock-task change. PLAN:547 corrected (M1, M2).
