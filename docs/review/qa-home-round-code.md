# Code QA: home-round (Home at the top after an unlock, the "Media on the lock screen" row, Home's own ground during a transition)

Branch `home-round` (c493aa5e) against main `358bfe86`: 778d0edd (item 1, Home starts at the top after an unlock),
fed6d9c4 (item 2, the "Media on the lock screen" row), c493aa5e (item 3, Home draws its own ground while a transition
into it runs). Reviewer's branch `qa-home-round` = `home-round` + this file.
Also read: `CLAUDE.md`, `launcher/CLAUDE.md`, design 32 (§8 step 1, the black stand-ins), design 08's new last
section, design 59d's step B and the unlock hand-over (`GlanceOpener`, `HomeFront`), the three PLAN entries, the
coordinator's emulator notes `docs/testing/2026-10-24-home-round-run.md` (main checkout), and `qa-r11-glance-code.md`
for the format.

## Setup

- I read `git diff 358bfe86..home-round` in full: `HomeReset.kt`, `HomeGround.kt`, `HomeActivity.kt`, `HomeFront.kt`,
  `BootHome.kt`, `PinLockActivity.go`, `MediaLockScreen.kt`, `SettingsFragmentLauncher.kt`, `preferences.xml`, both
  `strings.xml`, the five test files, `launcher/CLAUDE.md`, PLAN, design 08 and `android-settings.md`. I also read
  the neighbours: `HomeActivity`'s whole lifecycle (`onCreate`, `gate`/`showContent`/`showNight`, `onStart`,
  `onResume`, `onPause`, `resumeContent`, `onNewIntent`, `onStop`), the manifest entry (`singleTask`,
  `configChanges="orientation|screenSize"`), `GlanceOpener` (`run`, `watchSend`, `checkSend`, `facts`),
  `WallpaperGround`, `WallpaperRender` (`GroundDrawable`, `centreCrop`), `WallpaperApplier.screen`/`render`,
  `WallpaperStore`'s bitmap, every `HomeFront.bring`/`bringAfterUnlock` caller and the other typed HOME starts
  (`BootCover`, `BootCoverActivity`), `systemPageIntent`, `systemPageAccess`, the fragment's other rows and its
  applied listener, and `Application`'s `LauncherApps.Callback` and `appLoads`.
- Launcher suite (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug
  testDebugUnitTest`, from `launcher/`): **BUILD SUCCESSFUL in 2m 17s, 54 actionable tasks: 54 executed; 1675 tests,
  0 failures, 0 errors, 0 skipped.** `HomeResetTest` 6, `HomeGroundTest` 7, `MediaLockScreenTest` 5, `HomeFrontTest`
  6, `AndroidLockFaceTest` 18, `LockGlanceGuardTest` 30, `HomeRound7Test` 9, `KidScreensEscapeTest` 2. No new
  warning: only kapt's `RELEASE_11` line. (No server change in the branch, so no cargo run.)
- The readability of `media_controls_lock_screen` (item 2) was settled without an emulator: I parsed the
  `framework.jar` dex out of the SDK's API 36 (`android-36/default`) and API 35 (`android-35/google_apis`) system
  images (the dex is stored uncompressed and contiguous; Adler-32 checked) and read `Settings$Secure`'s field
  annotations (Q-1).
- Mutations: an exact-replace script applied one mutation at a time, ran the named test class(es) under the lock and
  restored the file. A baseline run with no change passed first. The worktree was clean before this file.
- No emulator, as briefed.

## What was checked and holds

### Item 1 - Home at the top after an unlock

- **The rule.** `homeResetDue` is true for `LOCK_LEFT_FOR_HOME` and `BOOT` only. `homeArrivalOf` gives `PLAIN`
  whenever `EXTRA_BROUGHT` is missing, whatever `EXTRA_ARRIVAL` says, and `OURS` for a missing, unknown or `PLAIN`
  name on our start. So an older build's start, `BootCover`'s and `BootCoverActivity`'s typed HOME starts (brought,
  no arrival) and every `bringAfterUnlock` (relock, update, role change, install mode) are `OURS`. The dark
  lock-up's `bring(animate = false)` and the lock's "root lock task" start are `OURS` too. `HomeResetTest` pins that
  only `BootHome`, `HomeFront` and `PinLockActivity` name an arrival.
- **`homeResetPending`.** A due arrival sets it. `LOCK_LEFT_FOR_APP` clears it. `OURS` and `PLAIN` keep it, which
  covers the relock's queued Home that can land after the leave and before the resume. It is folded from every
  `onNewIntent` and from a first `onCreate` (never a recreation: the intent then is the old one). It is taken at
  `resumeContent` (`HomeActivity.kt:547`) and dropped at `onStop`. Android delivers `onNewIntent` to a stopped
  `singleTask` Home before its `onResume` (to a resumed one after a temporary pause), so the fold always comes before
  the resume it is meant for.
- **The reset itself.** `resetToTopIfDue` is the only `scrollToPositionWithOffset(0, 0)` and it is unanimated. It runs
  before `renderWallpaper`/`render` in the resume, so the first traversal after the restart lays the grid out from
  position 0 and no frame shows the old place. The contacts row goes to its start too. The bind uses the icons
  already rendered (`HomeGridAdapter`'s `icons` map), so the reset costs one layout. An UNLOCKED edge while resumed
  (`modeListener` -> `resumeContent`) takes it as well. A leave into the time-rule screen (`redirectToLockScreenIfLocked`
  returns before `resumeContent`) drops it at the stop, which is right: that isn't Home.
- **The kid's place is kept** on the Home button and Back from an app: no extra (`PLAIN`, not due), and Back brings no
  intent. The kid's press on Home itself keeps round 7's smooth scroll (`homePressToTop` needs `!broughtByUs`, so
  none of our starts scroll there). It is still the one `smoothScrollToPosition(0)` (pinned).
- **SystemUI's own face or pad.** When the glance stopped under an app (59b step 0), the unlock brings no Home, so
  nothing resets and the kid lands back in that app. That matches the PLAN wording ("whenever the lock is left for
  Home"). A call over the unlock brings no Home either (`lockLeave`).
- **`go()`.** The target is now read before `HomeFront.bring`. `bring` changes no lock mode (`endDarkLockUp` only),
  so the `takeIf { mode == UNLOCKED }` reads the same value as before. A null target (a refused unlock, the
  switch-off, the crash guard, onResume's leave) is `LOCK_LEFT_FOR_HOME`, so Home is reset when Home is what the kid
  gets. `lockLeaveArrival` treats the kiosk root's target (which the opener never opens, design 25 §1.4) as Home.
- **`LockGlanceGuardTest`'s adjusted pin still pins what it did.** The take is `val target = pendingOpen?.takeIf {
  PinLockRuntime.mode == LockMode.UNLOCKED }`, the open is `target?.let { GlanceOpener.open(..) }` after it, the
  bring comes before the open, the open comes before `finishAndRemoveTask()`, and there is one `GlanceOpener.open(`.
  Opening from `pendingOpen` directly, or before the bring, still fails the pin. `HomeFrontTest` and
  `AndroidLockFaceTest` only follow the new signatures (`arrival`), and the main-thread hop forwards `arrival`
  (pinned).
- **Spoofing.** Home is exported. Any app could already send `EXTRA_BROUGHT`, and now `EXTRA_ARRIVAL` too. The only new
  effect is that Home's grid scrolls to the top. Harmless.

### Item 3 - Home's own ground during a transition into Home

- **The trigger.** The copy starts only from `groundArrived()`: `onNewIntent` with `!contentUp` (Home stopped
  behind an app: the Home button or gesture, every `HomeFront` start), and a first `onCreate` by a typed HOME intent.
  Back and a plain resume arm nothing: `onStart`/`onResume` don't call it (pinned). In `onCreate` the render returns
  early (`!contentShown` before `gate()`), and the first resume's `renderWallpaper` draws it.
  `onNewIntent` -> `groundArrived()` -> `renderWallpaper()` puts the copy on the content root before the restart's
  first frame, which the coordinator's frames confirm (gradient from the first frame).
- **The timing.** `homeGroundDue` holds the copy for `since < 700` or `!enterDone && since < 2500`, and ends it at
  once on `leftFront` (onPause) and at onStop (the state reset, the recheck removed). `homeGroundRecheckInMs` posts
  one recheck at the floor's end and, while the animation hasn't ended, one at the cap's. `HomeGroundTest` walks the
  rechecks to the answer turning. `onEnterAnimationComplete` sets `enterDone` one `postOnAnimation` frame later. When
  it comes after the floor, the copy goes on that frame. When it comes before, the floor's recheck ends it. When it
  never comes, the cap does.
- **The interactions:**
  - A lock-up during the hold: the screen-off pauses Home (`leftFront`, transparent at once), the LOCKED edge's
    `showNight` takes the content off the window, and the stop resets the state. The dark lock-up's own Home start
    then arms a new state, but `renderWallpaper` returns at `!contentShown`, and that state is gone at the next stop.
  - A configuration change: rotation is in `configChanges`, so no recreation. Any other change pauses, stops and
    recreates with `savedInstanceState`, so it never re-arms. See Q-6 for the one way that matters.
  - A second HOME intent while Home is still behind: the floor moves later. That is harmless.
  - A second HOME intent while Home is already resumed: see Q-2.
- **Design 32 holds.** The copy is `binding.root`'s background, set only by `WallpaperGround.apply` from
  `renderWallpaper`, which returns while `!contentShown`. The content isn't on the window while LOCKED or at a dark
  lock-up (`showNight` sets the night ground's root, `homeDarkResume` branches first). The window's background, the
  black `TaskDescription` and `setRecentsScreenshotEnabled(false)` are untouched, so Home's snapshot stays black.
  `HomeGroundTest` pins the single `WallpaperGround.apply(` call, the early return, the night path and the
  `onCreate` stand-ins.
- **59d step B is folded in unchanged.** `faceLeave` keeps "until the stop" whatever else, `groundArrived` keeps it,
  and onStop clears it. `HomeFrontTest` pins `faceLeave` with `leftFront` and `enterDone` set.
- **Cost.**
  - Main thread: one `GroundDrawable` allocation per render, about five renders per arrival. Setting the background
    can request a layout.
  - GPU: for at most 2.5 s, one extra full-screen layer of overdraw in Home's window (the bitmap or the gradient
    rect), and SurfaceFlinger still composes the wallpaper layer below because the window stays translucent.
  - With a photo, the first frame also uploads the store's RGB_565 bitmap as a texture (about 5 MB at 1080x2400) on
    the RenderThread. That is a few ms, the same as Home has always done when Android's wallpaper isn't ours.
- **Memory.** Nothing new is decoded. `WallpaperStore` keeps the one decoded photo whenever the current fill is an
  image (`WallpaperStore.kt:183`, `:223`), whether or not `systemShowsOurs`. So the copy draws a bitmap that is
  already held. The texture is transient.
- **No visible step when the copy goes.** The scrim is the same in the copy (`FULL`) and the steady state (`SCRIM` or
  `TRANSPARENT`), and so is the ink, so the status-bar icons don't flip. The photo's placement is `centreCrop` over
  the root's bounds in both Home's copy and `WallpaperApplier`'s render. The two agree as long as the render size
  (`resources.displayMetrics`) equals Home's full window. See Q-7 for the case where it doesn't.

### The first-frame question (the coordinator's ANRs and the 5.9 s Settings display)

- Nothing in this round runs on Home's create/start path except two extra reads (`noteArrival`) and a data-class copy
  with an early-returning render (`groundArrived`).
- At resume it adds one layout request (the reset) and one drawable allocation. Neither can delay Home's first frame
  or its window's readiness. The copy draws in the frame Home draws anyway, and its texture upload is on the
  RenderThread (photo only; the coordinator's run used a gradient).
- onPause/onStop add one render at most, which is microseconds. The pause and stop timeouts and `BLASTSyncEngine:
  Unfinished container ... HomeActivity` mean the main thread was busy. During provisioning it was busy with the
  enforcer's storm.
- That storm is pre-existing. `Application.loadApps` itself is a `trySend` on a conflated channel. A stack sampled
  there means the main thread is consumed by the stream of `LauncherApps` callbacks, one per hidden or suspended
  package:
  - each callback scans the icon cache (`evictIcons`), checks the clock app, and launches a coroutine;
  - `onPackageChanged` also runs `WindowRelock.onPackageInstalled` on IO, which can trigger a full
    `AppEnforcer.apply`;
  - each finished load posts a list that Home renders on the main thread.

  That is Q-4, a separate item.
- Settings: the media row adds one `resolveActivity(MATCH_SYSTEM_ONLY)` and one SettingsProvider call to every
  `updateSystemPageRows`. That call always throws (Q-1). The update runs on the main thread at onResume and after each
  `AppEnforcer.apply`, next to the three existing rows' resolutions. Under a system_server swamped by the hide/suspend
  storm, each binder call can stall, so the row adds a little to that work. It isn't plausibly 4 s, and one sample each
  on a just-booted emulator with a cargo build running can't be attributed. Q-1's fix removes the Settings call.

### Item 2 - the "Media on the lock screen" row

- **The same mechanism as the write-settings and fingerprint rows.**
  - `MediaLockScreen.pageIntents` is `systemPageIntent(ACTION_SOUND_SETTINGS)`: `MATCH_SYSTEM_ONLY`, an unsuspended
    system package that isn't ours. On a managed phone that is during the pause or the override only.
  - The access is `systemPageAccess` via the fragment's shared `pageAccess`. "No page" comes only after an
    `ActivityNotFoundException`, or once the window's plan is applied.
  - `openFirstPage` goes through a result launcher whose callback re-reads the row.
  - `updateMediaLockRow` is in `updateSystemPageRows`, so it runs on resume, after the tap, when the pause starts, and
    after each apply through the applied listener (posted to main).
  - A PAUSE_FIRST tap opens nothing and re-reads, as the other rows do.
- **Opening Sound & vibration adds no escape.** It is reachable only from the PIN-gated Settings, and only while
  Android's Settings is unsuspended (the pause), when the parent can open Settings anyway.
- **Strings.** The same nine names are in `values/` and `values-nb/`. The open, pause-first and no-page lines name
  the manual path in each language (pinned): "Lyd og vibrasjon › Medier › Vis medier på låseskjermen av" and "Sound
  & vibration › Media › Show media on lock screen off". `preferences.xml` shows the row always, under "Fingerprint"
  (pinned).
- **The state line: see Q-1.** The read can never succeed for us.

### Pins and docs

- `launcher/CLAUDE.md` (the Home bullet, the Drawing bullet, the rows bullet), design 08's new section and PLAN's
  three entries describe the code as built, apart from Q-1's state line and Q-5.
- PLAN keeps the three entries in "Approved for the next fix batch" with an inline **Done**/**Built**. That is the
  convention `write-settings-row` used.
- Nothing in the launcher-music bridge, the shared contract files (`server/testdata/`, test resources), the AIDL or the
  policy shape is touched. No server change.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| M0 | baseline, no change | passes (`HomeResetTest`, `HomeGroundTest`, `MediaLockScreenTest`) |
| M1 | `homeResetDue`: `BOOT` not due | `HomeResetTest` "only a leave for Home and the boot ...", "the reset owed folds ..." |
| M2 | `homeResetDue`: `OURS` due | `HomeResetTest` (the same two) |
| M3 | `homeResetPending`: `LOCK_LEFT_FOR_APP` keeps the owed reset | `HomeResetTest` "the reset owed folds the arrivals ..." |
| M4 | `homeResetPending`: a later arrival drops the owed reset | `HomeResetTest` "the reset owed folds the arrivals ..." |
| M5 | `lockLeaveArrival` ignores `lockWasRoot` | `HomeResetTest` "the lock's leave - Home unless ..." |
| M6 | `homeArrivalOf`: an unbrought intent with an arrival extra is believed | `HomeResetTest` "the arrival read from the intent" |
| M7 | `homeGroundDue`: no 700 ms floor | `HomeGroundTest` "after a HOME intent - the floor, ..." |
| M8 | `HOME_GROUND_MIN_MS` 700 -> 400 | **not caught** (range pin 300..1000) |
| M9 | `homeGroundDue`: no 2.5 s cap | `HomeGroundTest` "after a HOME intent ...", "the rechecks fall ..." |
| M10 | `HOME_GROUND_MAX_MS` 2500 -> 1500 | **not caught** (range pin 700..3000) |
| M11 | `homeGroundDue` ignores `leftFront` | `HomeGroundTest` "after a HOME intent ..." |
| M12 | `homeGroundDue`: no arrival read as an arrival at 0 | **not caught** (equivalent on a phone: `elapsedRealtime` is past 2.5 s at any Home start) |
| M13 | start: `onNewIntent` arms even with the content up | `HomeGroundTest` "armed by a HOME intent from behind only ..." (source pin) |
| M14 | start: `onCreate` arms on a recreation too | `HomeGroundTest` "armed by ..." (source pin) |
| M15 | end: onPause doesn't end it | `HomeGroundTest` "armed by ..." (source pin) |
| M16 | glue: `renderWallpaper` doesn't remove the pending recheck | **not caught** |
| M17 | glue: `onEnterAnimationComplete` sets nothing | `HomeGroundTest` "armed by ..." (source pin) |
| M18 | media: only 1 is on (2 reads off) | `MediaLockScreenTest` "the switch read as SystemUI reads it ..." |
| M19 | media: an unreadable switch doesn't open | `MediaLockScreenTest` "the state line and the action line ..." |
| M20 | media: unreadable shown as "On" | `MediaLockScreenTest` "the state line and the action line ..." |
| M21 | media: no action line unless known on | `MediaLockScreenTest` "the state line and the action line ..." |
| M22 | glue: `go()`'s arrival from `pendingOpen`, not the UNLOCKED target | `HomeResetTest` "the lock's leave and the boot say why ..." |
| M23 | glue: no `resetToTopIfDue()` in `resumeContent` | `HomeResetTest` "Home's grid goes back to the top only from the rule" |
| M24 | glue: `onNewIntent` doesn't fold the arrival | `HomeResetTest` "Home's grid goes back to the top only from the rule" |

Every briefed mutation of the rules' logic is caught: `homeResetDue`, `homeResetPending`, `homeGroundDue`'s floor,
cap and `leftFront`, the start conditions, and the media mapping. The floor's and the cap's **values** survive a
change inside their range pins (M8, M10). So does one glue line (M16). M12 is equivalent on a real phone (Q-6).

## Findings

### Q-1 (Medium) - `media_controls_lock_screen` isn't readable by Vibb One: the row always says "Ukjent"

`server/MediaLockScreen.kt:15` (the KDoc's "The switch itself is readable"), `:27`, `:49`, `:53-57` (`shown`);
`SettingsFragmentLauncher.kt:262-272`, `:415-424`; `docs/setup/android-settings.md:68-72`; `launcher/CLAUDE.md:748`;
PLAN (the media card entry, "Its state line reads ...").

What's wrong:

- From Android 12, SettingsProvider refuses a hidden `Settings.Secure` key to a caller targeting S+ unless the field
  carries `@Settings.Readable`. Only system, platform-signed and core-uid callers are exempt; a device owner isn't.
  The error is "Settings key: <..> is not readable. From S+, settings keys annotated with @hide are restricted to
  system_server and system apps only, unless they are annotated with @Readable". I found that message in the API 36
  image.
- In the API 36 and API 35 framework dex, `Settings$Secure.MEDIA_CONTROLS_LOCK_SCREEN` is declared with **no**
  annotation. As controls from the same class and dex, `MEDIA_CONTROLS_RESUME` and `ANDROID_ID` carry
  `Settings$Readable`, and the `LOCK_SCREEN_*` notification keys carry `SystemApi` + `Readable`. The key was added
  in Android 13, after S's bulk annotation.
- So `Settings.Secure.getInt(cr, "media_controls_lock_screen", 1)` throws `SecurityException` on every phone this
  launcher runs on (minSdk 34, targetSdk 36). `runCatching` makes it null.
- The effects:
  - the state line is always "Ukjent"/"Unknown";
  - the action line never goes away;
  - `mediaLockTapOpens` is always true;
  - the setup doc's "go back - the row now says 'Off'" never happens. The parent who follows it will think the
    switch didn't take.
- There is no other way to read it:
  - `Settings.Secure.getString` is the same `GET_secure` call to the same per-name lookup with the same check;
  - a `ContentResolver.query` of `Settings.Secure.CONTENT_URI` for the name goes through the same lookup;
  - the DPM has no read for it (`setSecureSetting` is a write allowlist, and this key isn't on it).

The row's main job still works: it opens the page and names the path.

Expected fix: drop the state line and the read.

- The summary is the action line alone (open, pause first, a moment, no page). The tap always opens.
- Remove `MEDIA_CONTROLS_LOCK_SCREEN(_DEFAULT)`, `mediaOnLockScreen`, `mediaLockStateText`, `mediaLockTapOpens`,
  `MediaLockScreen.shown`, the summary format and the three state strings in both locales. Keeping the read "in case"
  only keeps dead code that throws a binder exception on every row update.
- Reword the KDoc and the docs to say Android doesn't let an app read the switch, so the check is the switch on
  Android's page. The docs are `android-settings.md` step 2 (no "the row now says Off") and its first paragraph's
  "but Vibb One can read it", plus `launcher/CLAUDE.md` and PLAN.
- `MediaLockScreenTest`: pin that `MediaLockScreen.kt` calls no `Settings.Secure.get` (comment: not `@Readable` since
  Android 13), and keep the action-line and path pins.
- Optional: on the emulator, logcat shows the provider's "is not readable" line when the row opens today.

### Q-2 (Low) - a Home press on Home during the hold drops the copy mid-transition

`HomeActivity.kt:535-538` (onPause's `leftFront`), `:651`.

What's wrong: Android pauses a resumed `singleTask` Home to deliver a new intent (`NewIntentItem`'s pre-execution
state; this file's own comments rely on it). Home resumes when the app-close transition starts, and that transition
runs about 300-450 ms. A second Home press inside it, a double press or a kid mashing the button, therefore gives
onPause -> `leftFront = true` -> transparent at once. The re-delivered intent then has `contentUp` true, so nothing
re-arms. The black frames the round removed can come back for the rest of that transition. `HomeGroundTest` has no
row for it.

Expected fix: don't treat the delivery pause as leaving the front. For example, have onPause post the `leftFront`
change (`refreshHandler.post { if (!resumedNow) { ground = ground.copy(leftFront = true); renderWallpaper() } }`).
The temporary pause, `onNewIntent` and `onResume` run in one transaction, so the post sees `resumedNow` again and does
nothing. A real app start over Home has no resume after it, so the copy still goes before the app's first frame.
Remove that post at onStop. Pin it next to the onPause pin.

### Q-3 (Low, accept or cheap fix) - the "known gap": a row whose target can't open leaves Home where it was

`PinLockActivity.go` (`:1162-1166` on the branch), `HomeReset.kt:41` (`lockLeaveArrival`), PLAN's residual.

What's wrong: `hasTarget` means a target was taken, not that something opens. When the opener's step is NOTHING,
the kid lands on Home and it isn't reset. That happens for a package that isn't installed, suspended or hidden by
then, a kiosk with no lock task, or a LAUNCH with no launch intent. The PLAN's list also names "refused send", but
`checkSend`'s REFUSED falls back to the launch intent, so a refused send usually still opens the app. Only "no launch
intent" leaves Home. All of these are rare: the parent blocked the app between the row and the PIN, or a kiosk
misconfiguration. The kid sees a Home that is merely not reset, which is the pre-round behaviour.

Expected fix: acceptable as is. If wanted, it is cheap because every NOTHING fact is synchronous:

- add a `GlanceOpener.opens(activity, pending): Boolean`, which is `glanceOpenStep(facts(..)).open` not NOTHING, and
  a launch intent present for LAUNCH;
- pass it as `hasTarget`;
- `HAND_TO_TIME_RULE_SCREEN` can stay "an app": Home redirects to the time-rule screen and drops the reset anyway.

Either way, correct the PLAN residual's "refused send".

### Q-4 (Low, separate item, pre-existing) - debounce the app-list reload under a package-changed storm

`Application.kt:60-140` (the `LauncherApps.Callback`), `:500-528` (`appLoads`, `loadApps`).

What's wrong: see "The first-frame question". At provisioning the enforcer hides and suspends many packages, and each
package fires `onPackageChanged` on the main thread. Each callback then:

- evicts icons by a cache scan;
- requests a load;
- checks the clock app;
- launches an IO coroutine that can run `AppEnforcer.apply`.

The conflated channel keeps one load running and one pending, so loads run back to back for the whole storm. Each
finished load posts a list that Home's observer renders on the main thread. Together with a starved CPU, that is
consistent with the two "no focused window" ANRs. Nothing in this round contributes.

Expected fix: a separate PLAN item in the RC3-4 family.

- Give `appLoads`' consumer a quiet period: after a receive, `delay(300)` and drain until 300 ms pass with no request
  or 2 s have passed, then load once. `iconsToEvict` already accumulates the packages.
- Optionally, coalesce `WindowRelock.onPackageInstalled` per burst the same way, so a storm can't queue one
  `AppEnforcer.apply` per package.
- Pin the quiet period in `HomeRound7Test`, next to the conflated-channel pin.

### Q-5 (Low, docs) - the docs still say the cause wasn't measured; small wording

`docs/design/08-ui-polish.md:291-292` ("Which of the two it is wasn't measured"), its "Device check owed" bullet, and
PLAN's black-flash entry ("emulator check owed ... If the recording still shows black ...").

What's wrong: the coordinator's run measured it. The old build showed 2-3 black frames and this branch shows 0, with a
gradient, gesture navigation and kiosk off. Back stayed clean, and no seam was seen when the copy went. So the cause was
Android's wallpaper not yet shown under Home. The docs should say so, and keep only what is still owed:

- 3-button navigation;
- kiosk on;
- a photo and a plain colour;
- the lock's leave on both pads;
- screen off/on (design 32).

Two smaller wording points:

- "never for Back or Recents" (design 08, `launcher/CLAUDE.md`, `HomeGround.kt`'s header) is true for opening
  Recents. But leaving Quickstep's fallback Recents to Home sends a typed HOME intent, so the copy does arm then.
  That is harmless and correct. Say "never for Back".
- The HomeGround header's "an update" in the list of `HomeFront` starts is fine. `BootCover`'s typed HOME starts are
  brought starts too, and they arm the copy at a create, harmlessly.

Expected fix: update design 08's section and the PLAN entry with the run's result and the remaining checks, and reword
the Recents clause.

### Q-6 (Low, pins) - the floor's and the cap's values are pinned only as ranges; one glue line survives

`HomeGroundTest.kt:47-48`; `HomeActivity.kt` `renderWallpaper`'s `refreshHandler.removeCallbacks(groundRecheck)`.

What's wrong: the brief's numbers are the user-visible part of the fix. Moving the floor to 400 ms (M8) or the cap
to 1.5 s (M10) passes the suite, because `HomeGroundTest` checks only `MIN in 300..1000` and `MAX in MIN..3000`.
Dropping `renderWallpaper`'s `removeCallbacks(groundRecheck)` (M16) passes too. M12 (no arrival read as an arrival at
0 ms) survives, but it is equivalent on a phone: the boot clock is past 2.5 s at any Home start.

Expected fix (lean):

- pin `HOME_GROUND_MIN_MS == 700L` and `HOME_GROUND_MAX_MS == 2_500L` exactly, with the user's numbers in the
  message (the ranges stay as the design's bounds);
- pin `refreshHandler.removeCallbacks(groundRecheck)` before the `postDelayed` in `renderWallpaper` (without it every
  render adds another recheck; harmless today, but it is the single-recheck invariant the KDoc states).

A rarer gap in the same area, which needs no pin: a configuration change other than rotation during the hold (the
parent's language, applied by `LauncherLocales.applyIfSafe` in `resumeContent`) recreates Home with
`savedInstanceState`. So the copy is gone for the rest of that one transition.

### Q-7 (Note) - a photo's hand-over relies on `resources.displayMetrics` equalling Home's full window

`WallpaperApplier.kt:63-66` (`screen`), `WallpaperRender.centreCrop`, design 08's "Placement".

What's wrong: Android's wallpaper is the photo centre-cropped into a `displayMetrics`-sized bitmap and then scaled to
cover the display. Home's copy is the photo centre-cropped into the root's full, edge-to-edge bounds. If the app's
display metrics exclude a bar or the cutout (3-button navigation on some versions or OEMs), the system's picture is
zoomed by H/(H - bar), about 3-5 %, against the copy. That shows as a small zoom step when the copy goes. A gradient
only shifts a little. This is pre-existing in the same form for 59d's face-leave copy and for the FULL ground. The
coordinator saw no seam with a gradient and gesture navigation.

Expected fix: none in code now. Make "a photo, 3-button navigation" an explicit row of the owed device check. If it
shows a step, render the applier's bitmap at `WindowManager.maximumWindowMetrics` (or `currentWindowMetrics.bounds`)
instead of `displayMetrics`.

### Q-8 (Nit) - small leftovers

- `onNewIntent` arms the copy for any intent while the content isn't up. `onCreate` checks `CATEGORY_HOME`. Home is
  also a `LAUNCHER` target, but such an intent brings Home from behind too, so this is harmless. Add the category
  check, or say so in the comment.
- `onEnterAnimationComplete`'s `postOnAnimation` callback isn't removed at onStop. A late one sets `enterDone` on the
  next arrival's state. The 700 ms floor makes that harmless. Keep the Runnable in a field and remove it at onStop
  and in `groundArrived`, or accept it.
- `renderWallpaper` allocates a new `GroundDrawable`/`ColorDrawable` on every call, now about five per arrival.
  Setting a new background can request a layout of the whole root. Cheap, but `WallpaperGround.apply` could skip the
  set when the kind, fill, bitmap and scrim are unchanged.
- `MediaLockScreen.kt` sits in `server/` next to the other rows' helpers. That is consistent, so no change, but its
  header calls the setting "readable" (Q-1).

## Docs

- `launcher/CLAUDE.md`'s three bullets match the code. Q-1 changes the rows bullet's state-line sentence, and Q-5 the
  Drawing bullet's "Recents".
- Design 08's section matches the code. It needs the run's result (Q-5) and the photo/3-button check (Q-7).
- `android-settings.md`'s new steps are right about the path and the pause, and wrong about the row's state (Q-1).
- PLAN's three entries are accurate apart from Q-1's state line, Q-3's "refused send" and Q-5's "not measured".

## Verdict

**Merge after a fix round.** No Blocker or High. One Medium: Q-1, the media row's state line can never be read.

- Item 1's rule is sound and minimal. Only a leave for Home and the boot reset, the reset is taken at the one resume
  that follows, and nothing else moves the kid's place.
- Item 3 fixes the black flash on the emulator without touching design 32: the copy is never on the window while
  LOCKED or at a dark lock-up, and the snapshot stays black. It adds no memory, and nothing that can delay Home's
  first frame.
- The provisioning ANRs are the pre-existing package-changed storm, not this round.

The round should take:

- Q-1: drop the state line and the read; fix the docs.
- Q-2: the delivery pause must not end the copy.
- Q-5: docs with the run's result.
- Q-6: the exact pins.

Q-3 is accept-or-cheap, Q-4 is a separate PLAN item (the `loadApps` debounce), Q-7 is a device-check row, and Q-8
holds nits. After the round, the coordinator's remaining device checks are: 3-button navigation with a photo, kiosk
on, both pads, and on the user's emulator the reset with a full grid and the row during a pause.
