# Code QA: lock fix round 25b and the lock background 25f

Reviewer: code QA, 2026-10-12, on `main` at 444ca582. Read-only review; nothing in the implementation was changed.

Commits reviewed:
- 25b: 86631d9c (A), ff16078d (C), da297b93 (B), ce235782 (E), dceaedd2 (A/C camera policy), cac3cf8a and
  697fa0ea (H, SOS), 0d74c1b4 (G), 2871b7b0 (B residual), b6bcb3e8 (docs).
- 25f: 38674f61, 160a6f9e, c5c0079b, 96b42e90.

Sources checked:
- AOSP 14/15/16 `DevicePolicyManagerService.setCameraDisabled` and `LockTaskController.updateLockTaskPackages`
  (`android16-release`);
- AOSP 16 SystemUI `EmergencyGestureModule` (fetched from googlesource).
- `[device]` marks what only a phone or emulator can settle.

**Result: 1 High, 3 Medium, 6 Low.**

Suites, run once under the shared build lock:
- server: `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`;
- launcher: `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`.

The results are in "Suites" at the end.

---

## High

### H1. The SOS yield gives about 60 s over the lock to whatever stops it within 3 s of a screen-on

**Where:**
- `launcher/.../lock/PinLockRuntime.kt:1049-1059` (`noteProbableSos`);
- `lock/RefrontPolicy.kt:67` (`inputs.sosFlow -> Yield("sos")`), `:178` (`SOS_FLOW_MS = 60_000`), `:194` (`sosGestureLikely`).

**What happens.** Any stop of the lock while LOCKED marks "probably SOS" when all of these hold:
- the screen is on;
- it comes within 3 s of the last `ACTION_SCREEN_ON`;
- the phone has *any* system handler of `LAUNCH_EMERGENCY` (AOSP's `com.android.emergency` is on almost every image);
- none of our own activities is resumed.

After that the re-front yields as "sos" for up to 60 s, whatever is actually in front. Nothing checks that the SOS
activity is the one in front.

The design's argument that nothing else can be there doesn't hold with the kiosk on. While LOCKED,
`lockTaskWhileLocked` keeps `kioskPackages - recentsPin`, which is every allowed app (`LockTaskHelpers.kt:182`). Any
allowed app may come to the front within lock task, and Recents is still reachable through the 25b-A desync. These
kid-reachable ways stop the lock inside the window:
1. **Recents to a thumbnail.**
   - Power on, tap Overview (the lock pauses; inside the window the check waits `SOS_PAUSE_GRACE_MS` = 700 ms), then
     tap an allowed app's thumbnail within that 700 ms.
   - The task switch stops the lock and marks SOS. The app (Element X, a game) stays up for about 60 s.
   - The trick repeats: screen off and on, then do it again.
   - The user's own report shows that opening an app from that Recents works ("opening an app came back to the lock").
2. **A phone call through the system dialer (calls unmanaged) that wakes the screen.**
   - The incoming-call screen covers the lock at once, inside the window, so the call marks SOS.
   - VoIP calls and our own in-call screen are *not* affected, because `VoipEnded` and `CallsEnded` show the lock
     directly.
   - The system dialer's call has no end event. Only the re-front loop brings the lock back, and after the call
     `sosFlow` keeps whatever the kid opened during it (the dialer, contacts, an app they lead to) up until 60 s
     after the ring.
3. **An allowed app's full-screen intent or alarm-clock activity that turns the screen on.** Examples are a timer or
   alarm app from Play, or a messenger's call screen. It covers the lock within the window, and nothing ends the yield
   early.
4. **The system clock's alarm** (see M2). It matters when dismissing it leaves the clock app's own screen in front.
5. **The camera double-press while `setCameraDisabled` isn't granted yet.**
   - This is the restart-pending state of an updated Android 14 phone, where DPMS checks `USES_POLICY_DISABLE_CAMERA`
     without the policy-engine flag.
   - The double-press itself turns the screen on. SystemUI crashes as in bug A/C, and whatever comes up (quickstep's
     RecentsActivity or app list) now stays for 60 s instead of 200 ms.

**Fix (suggested).**
- While the "sos" yield runs with the kiosk on, write the lock-task list of the kiosk-off LOCKED case: own package +
  `pinLockHelpers` (emergency dialer, Telecom, system dialer, alarm app) + the SOS package.
  - AOSP 16 `LockTaskController.updateLockTaskPackages` (lines 765-789) removes every locked task that lost its
    allowlisting (`removeLockedTask` + `performClearTaskForReuse`). Whatever the kid brought up is closed at once,
    and only the SOS app or an emergency call can stay in front.
  - Restore the list when the yield ends.
- Also shorten `SOS_FLOW_MS` to cover the countdown and the dial (about 15 s). A placed call is held by
  `telecomInCall`/"system_call" anyway.
- End the SOS mark on any yield that isn't "sos" (call, voip, alarm), so a call answered in the window never turns
  into an SOS yield after it ends.
- Optionally, require the SOS gesture setting to be on where it is readable.

**Missing tests:**
- a test that a stop following a pause (Recents) inside the window doesn't yield for more than the grace;
- a test that the end of a system-dialer call re-fronts even when its ring fell in the window;
- a source guard on the narrowed lock-task list during the yield.

---

## Medium

### M1. `setCameraDisabled` turns off the camera for every app, not only for the power-button gesture

**Where:** `lock/CameraLockPlan.kt:126` (`cameraPolicyDisabled = locked || gestureCameraSuspended`), `lock/CameraLock.kt:183-201`.

**What happens.** On Android 14+, `setCameraDisabled` is the `DISALLOW_CAMERA` restriction for the whole device. That
includes `setBackwardCompatibleUserRestriction` in DPMS 14/15/16.
1. **VoIP while LOCKED (design 17).**
   - A parent's Element X video call answered from the lock's ring card stays LOCKED (the lock only yields as
     "voip"), so the camera is off during the whole call. Element Call joins without video or fails `getUserMedia`
     [device].
   - The same happens when the screen is turned off during an unlocked video call: the LOCKED edge disables the
     camera mid-call [device].
2. **Time rules.**
   - During a time rule the gesture's camera is suspended, so `gestureCameraSuspended` is true and the cameras are
     off for every app while unlocked too.
   - That includes the apps the parent marked usable during the rule (`lockUsableApps`, for example a video call to
     grandparents at bedtime).
   - Whether the Quick Settings torch also stops under the restriction is [device].
3. Before 25b only the camera *apps* were suspended while LOCKED. Other apps' camera use was untouched.

**Fix (suggested).**
- Keep the policy only while LOCKED and no call (phone, VoIP) is live: lift it while `VoipCalls.phase != NONE` or a
  call is up, and set it again when the call ends.
- For time rules, either accept the gesture crash over the time-rule screen (it has its own re-front) or ask the user.
  "Every camera off at bedtime" is a product decision that the design didn't take.
- Add tests for both rules.

### M2. "sos" is ordered before "alarm", so a woken alarm no longer ends the alarm exemption

**Where:** `lock/RefrontPolicy.kt:67-68`, `:124` (`alarmAfterResume` ends the exemption only after a yield named
"alarm"), `PinLockRuntime.kt:1007`.

**What happens.**
1. The system clock's alarm turns the screen on, and its alarm screen covers the lock within 3 s. That is a stop in
   the window, so it is marked SOS.
2. `refrontAction` returns `Yield("sos")`, not `Yield("alarm")`, because `sosFlow` is checked first.
3. When the alarm is dismissed and the lock resumes, `alarmAfterResume(remembered, "sos")` keeps `rememberedAlarmMs`.
4. For the rest of `ALARM_RING_MS` (3 min after the alarm time), every later stop of the lock is yielded as
   "alarm". An example is Recents to an allowed app, as in H1 but now outside the 3 s window.

This undoes qa-10-code #4: "the alarm's exemption ends only on a yield named alarm", which is the comment right below
the new line.

**Fix:** put `inputs.alarmRinging` before `inputs.sosFlow`, or let `alarmAfterResume` clear the alarm on a "sos"
yield as well. Add a test: `alarmAfterResume` after an alarm that rang while an SOS mark was set.

### M3. The lock's scrim gives 4.5:1 to pure white only

**Where:** `ui/wallpaper/WallpaperInk.kt:106-121`, the inks in `res/layout/view_lock_glance.xml` and
`activity_pin_lock.xml`.

**What happens.** `lockScrim` stops at the lightest scrim that gives *white* 4.5:1. Several texts sit directly on
the wallpaper in other colours. Computed with the code's own blend (sRGB, the scrim truncated to /255):

| Background | Scrim | White | Date, "N more" (`kid_core_dim` #FBE4DC at 78 %) | "Parent code" (white at 75 %) | Glance "Emergency call" (#FF8A8A) |
| --- | --- | --- | --- | --- | --- |
| White | 0.54 | 4.54 | 2.93 | 3.34 | **2.00** |
| Sunset (built-in) | 0.20 | 4.53 | 2.92 | 3.33 | **2.00** |
| Sky (built-in) | 0.05 | 4.55 | 2.93 | 3.34 | **2.01** |
| Photo, light side 0.8 | 0.49 | 4.50 | 2.90 | 3.31 | **1.98** |
| Navy | 0 | 16.0 | 8.5 | 9.5 | 7.0 |

The glance page's Emergency call link is a safety control. At about 2:1 on two built-in wallpapers every phone has,
it is hard to read in daylight.

**Fix (one of):**
- compute the scrim for the dimmest ink on the wallpaper. The emergency pink needs a background luminance <= 0.053,
  which the 0.6 cap can't give on white, so this needs a higher cap or the next fix;
- give the Emergency link (and the date/"more" line) a dark pill or the card background when the scrim isn't 0;
- make them white with a red icon.

Add a `WallpaperInkTest` case that checks every lock ink on the wallpaper, not only white.

---

## Low

### L1. `GroundDrawable` reuses one `Paint` - a second draw paints the photo at the scrim's alpha

**Where:** `ui/wallpaper/WallpaperRender.kt:55` and `:86`.

**What happens.**
1. `draw()` sets `paint.color = scrimArgb` for the scrim.
2. On the next `draw()` of the same instance, the image branch only resets the shader. `drawBitmap` is modulated by
   the paint's alpha, so the photo is drawn at the scrim's alpha (0.15-0.6) over what lies below, then scrimmed again.
3. On the lock this happens when the glance root's display list is re-recorded without a new drawable. That is a
   child going GONE or VISIBLE (a row arrives or goes, the tile or date shows), since `View.setFlags` invalidates
   the parent.
4. The glance page then darkens visibly (about 0.7·P becomes 0.55·P at a 0.3 scrim) [device].
5. This was latent on Home already. The lock's stronger scrims make it visible.

**Fix:** `paint.alpha = 255` (or a separate scrim `Paint`) before `drawBitmap`.

### L2. After a process restart the lock draws `kid_ground` first, then the wallpaper

**Where:** `PinLockActivity.kt:205-221`, `WallpaperStore.lockReady`.

**What happens.** When the process was killed while the screen was off, the lock started at the next screen-on has no
store state yet (`lockReady` false). It shows the plain ground and switches to the photo once the store's thread has
decoded it. That is a visible switch at power-on, the kind of flash 25b-B removed.

**Fix:** for a colour or gradient, work the state out synchronously; it's cheap. For a photo, hold the first frame
until `lockReady` for a bounded time (for example 150 ms through the existing pre-draw hook). Or accept it and list it
under the device checks.

### L3. `noteProbableSos` resolves the SOS package on the main thread

**Where:** `PinLockRuntime.kt:1056`.

**What happens.** `emergencySosPackage` makes four `queryIntentActivities` calls and
`getResourcesForApplication("com.android.systemui")`. It runs at a lock stop, on the main thread, where qa-10-code #7
keeps PackageManager off.

**Fix:** resolve it with the helpers (`LockTaskChrome.prefetchHelpers` or each `apply()`) and read the cached value.

### L4. The missed-call row overrides the APP_ONLY level

**Where:** `GlancePanel.kt:294`, `:320`.

**What happens.**
- A notification at `GlanceLevel.APP_ONLY` (for example an app's lock-screen visibility SECRET) now says
  "Tapt anrop · <app>" instead of "N varsler". That tells anyone holding the phone what the app chose to hide.
- The row also drops the count ("3 meldinger" becomes "Tapt anrop") when a call is the newest of several notifications.

**Fix:** use the missed-call text only at SENDER and TEXT. At APP_ONLY keep the count text and show the badge only.

### L5. The restart notice for `disable-camera` is shown where the tag doesn't matter

**Where:** `server/AdminPolicies.kt:26`, `server/src/models.rs` `ADMIN_POLICY_EFFECTS`.

**What happens.**
- On Android 15/16 (and 14 with the policy-engine flag), DPMS checks the device owner's
  `MANAGE_DEVICE_POLICY_CAMERA` permission, not the declared tag. `setCameraDisabled` works at once.
- `hasGrantedPolicy(admin, 8)` still reads false until a restart, so the device page asks the parent to restart
  for an effect that is already there.
- The notice is right on Android 14 without the flag.

**Fix:** leave it as it is, or word the line "may need a restart". Cosmetic.

### L6. Design 25's "Deviations and limits" understates the SOS yield

**Where:** `docs/design/25-lock-glance.md:1008-1011`.

**What it says:**
- "While LOCKED only pinned packages can be there: Home and Recents are out of lock task ... Recents only pauses
  (re-fronted as always, within 700 ms in that window)."

**What is missing:**
- With the kiosk on, the pinned packages are all allowed apps.
- Recents to a thumbnail is a stop, not a pause.
- Calls and alarms woken in the window are marked too.

**Fix:** update it with H1's fix.

---

## Missing tests (besides the ones named above)

- **Server:** unticking *every* wallpaper (an empty form, `json_each('[]')`) deletes all rows and the lock choice.
  A device delete removes `device_lock_wallpaper` through both foreign keys.
- **Launcher:**
  - `refreshNow`'s lock fallback when the separate lock photo can't be decoded: it falls back to Home's, and the
    scrim is Home's;
  - a pure helper for that choice would make it testable.
- **Launcher:** the camera policy at a process start while unlocked is set back to enabled (only covered by
  init's existing `CameraLock.onLockChanged` call; no guard pins it now that `cameraPolicy` caches per process).

## Verified OK

**25b-A (re-front on a pause):**
- Gated on `chromeLocked && !finishing`. `finishingAfterUnlock` is set in `unlock()`/`leave()` before any start or
  finish.
- The check stops when the lock resumed or the screen went off.
- The stop's re-front at 200 ms keeps the 2 s / 5 s backoff after it.

**25b-C (status-bar heal):**
- The skip flag is set only when the deferred screen-off heal really ran, and is cleared on every lock resume.
- Later resumes still heal at most once a minute.

**25b-B (the lock-up):**
- `FLAG_ACTIVITY_NO_ANIMATION` is used on every `show()`, including the re-front. `overrideActivityTransition` is
  API 34 (minSdk 34). `windowAnimationStyle` is `@null`.
- Cancelling a pre-draw makes ViewRootImpl schedule another traversal. `glanceFit` depends only on the root's height
  and content, so it can't oscillate and keep the page from drawing.

**25b-B residual (the deferred screen-off work):**
- It is dropped on any screen-on or user-present.
- The update fence is also ended at every lock resume (`PinLockActivity.onResume` calls `UpdateFence.onFront`), so
  a dropped screen-off call doesn't strand it.
- `SelfUpdate.onScreenOn` still cancels its alarm.
- No relock runs at a screen-off (`RelockTrigger.SCREEN_ON` only). The Play window still opens 400 ms later while
  the screen is off.

**Camera policy:**
- Ordering: on before the suspension while LOCKED, off after the release.
- `cameraPolicy == null` at a process start forces a real `getCameraDisabled` read.
- Init re-syncs when not LOCKED; the LOCKED `ProcessStart` edge syncs too.
- The crash guard (lock off, DISABLED) re-enables it.
- The restriction persists over a reboot (fail closed until the process runs).
- Removing the device owner clears it.
- A refused call (14 without the flag) is caught and retried by the next pass.

**SOS resolution:**
- `MATCH_SYSTEM_ONLY` with the preferred package first is exactly AOSP 16's `EmergencyGestureModule`.
- A kid-installed app can't be a system app.
- Settings, the camera and Play core give none. The package is never on the kid's list.
- It is never hidden or suspended through the plan, new-package installs or `kidListable`.
- It is pinned with and without the block bit, and in the kiosk-off LOCKED list.

**25b-E (missed-call rows):**
- The category is the one already read for candidates, so nothing new is read.
- `toString` stays redacted, and the guard allowlist was updated.

**25b-G (the music tile):**
- The tile is tied to the session (playing or paused).
- The keypad's music row and its resources are removed with no dangling references (compiles).
- `PinLockLayoutTest` updated for the keys' 64 dp.

**25f server:**
- The route validates (`""`, a ticked id, else 400; 404 for an unknown device) in one transaction. It is logged,
  nudges, and redirects without a fragment.
- The composite foreign key has its target primary key (`device_wallpapers(device_id, wallpaper_id)`), and the
  pool runs with `foreign_keys(true)`.
- Cascades from untick, wallpaper delete (including `photos.rs`'s lost-file delete) and device delete work.
- `INSERT OR IGNORE` plus the `json_each` delete keeps the untouched rows. No other writer relied on delete-all (only
  the device-insert trigger and this handler write `device_wallpapers`).
- The order comes from `wallpapers.sort, id`, not rowid.
- Migration 0069 follows 0068. `main`'s first-parent history never had 0070, so no deployed database has it.

**Policy contract:**
- `launcher_ui.lock_wallpaper` is always sent (null when unset or when the query fails).
- The snapshot is updated on both sides (`policy_json_keys_snapshot`, `PolicyResponseCompatTest`). An older launcher
  ignores it (`ignoreUnknownKeys`).
- A lock id that's filtered out of `wallpapers` falls back to Home's.

**Scrim math for white:**
- The sRGB blend matches how Android composites. 0.6 gives white 5.7:1 on white.
- The 0.01 steps plus the /255 truncation stay at or above 4.5 (checked numerically).

**Bitmap memory:**
- RGB_565, sampled to the screen, bounds-checked, `Throwable` caught.
- At most two screen-size decodes (Home and a separate lock photo); the same photo is shared.
- No shared bitmap is recycled.

**PWA:**
- `scroll-restore.js` hooks `HTMLFormElement.prototype.submit`, so the select's `onchange="this.form.submit()"`
  keeps the place.
- The card's `<form class="card">` became a `div.card` around two forms. No CSS targets `form.card`.

---

## Suites

Both run once under the shared build lock, on 444ca582.

**Server** (`CARGO_TARGET_DIR=server-target-25b`):
- `cargo test`: **591 passed**, 0 failed.
- `cargo fmt --check`: clean.
- `cargo clippy --all-targets`: 12 warnings, all from before this round (`devices.rs:1321` from 2026-08-15,
  `dns_engine.rs`, dead fields in `models.rs`). None is in 25b or 25f code.

**Launcher** (`--no-daemon --max-workers=2 testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`,
with the stub `tsnet.aar` copied in and removed afterwards):
- **BUILD SUCCESSFUL**;
- 1005 unit tests, 0 failures, 0 errors, 0 skipped.

No probe tests were added. The findings above come from reading the code: these parts are pure functions or
framework behaviour that the JVM unit tests (no Robolectric) can't exercise.

---

## Fixes (lock fix round 25c, branch `lock-fixes-25c`)

| Finding | Commit | What changed |
| --- | --- | --- |
| H1 | 5aeee6fd | The "sos" yield narrows lock task to our package + the lock's helpers + the SOS app (`sosNarrowedPackages`, `LockTaskChrome.sosYield`; the helpers now cached with the kiosk on too); `SOS_FLOW_MS` 15 s; the mark ends on any outcome but its own yield (`sosMarkKept`). `SosYieldTest`: QA's five scenarios, the list, the guards |
| M1 | 022fff42 | `cameraPolicyDisabled(locked, callActive)` = LOCKED and no live call; `PinLockRuntime.callForCamera` follows our calls, VoIP, the system dialer's (re-front checks, the lock back); never set unlocked. Remaining crash states listed in design 25 (25c) |
| M2 | 49d87d3f | "sos" is the last yield: alarm, VoIP and calls first; the alarm's exemption ends as before |
| M3 | 72bcb72a | `lockScrim` for every ink in `LOCK_INKS` (white, `kid_core_dim`, white at 75 %, `kid_emergency_text`), each blended at its alpha, the scrim at its 8-bit alpha; cap 0.8 |
| L1 | 739755b7 | The photo is drawn with an opaque paint |
| L2 | 58fc7c4e | After a process start the first frame waits for `lockReady`, at most 150 ms |
| L3 | 5aeee6fd | `PinLockRuntime.sosPackage`, resolved at init (background) and by every `apply()` |
| L4 | dc64eaf5 | `glanceSubtitle`: no missed-call text or badge at APP_ONLY; "Tapt anrop · N meldinger" when there is more than the call |
| L5 | 0246b24c | `disable-camera` is not reported pending from SDK 35 (permission-checked there) |
| L6 | 3bf68478 | Design 25's limits describe the SOS yield as it is |
| Missing tests | e71efdb7 | Empty untick and device delete clear the lock choice; `lockAfterDecode`; the camera policy at a process start |
