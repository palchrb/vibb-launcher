# Code QA: launcher-small-a (five small launcher items, one with a server part)

Scope: `git diff main...launcher-small-a` (merge base `ef15e842`, 5 commits `fe190b0d..056dfabb`, 28 files: launcher,
server, docs) against PLAN's five Launcher queue items and their decided fixes, design 08 "The copy stays",
`docs/testing/2026-10-24-home-round-run.md` ("Owed after launcher-small-a"), `docs/notes/server/notifications.md`
("App update notices"), `launcher/CLAUDE.md` and `server/CLAUDE.md`. Reviewed on branch `qa-launcher-small-a`.
(`git diff main..launcher-small-a` also shows main's newer pwa-fixes-c/server 0.52.0 work as removed: main moved on
after the branch point - nothing of it is this branch's; a merge keeps it.)

**Verdict: merge after the fix round (Q-1, Q-2; the Lows with it).** Every item does what its decided fix says, the
code is small and clean, both suites are green, and 13 of 15 mutations were caught. Two things need a decision or a
small change before the merge: the permanent copy moves the hand-over step to Home <-> phone book / Kid Settings
(Q-1), and the trail rule also delays every pass's own Home update from 300 ms to 3 s, unpinned and against the
docs (Q-2).

Per item:

1. **Home's permanent ground** - right. `homeGroundDue(contentShown)` is passed only from `renderWallpaper`, which
   returns early without the content, so the copy is never drawn at a lock-up: the night ground replaces
   `binding.root` with `setContentView` (`HomeActivity.showNight`), `setRecentsScreenshotEnabled(false)` and the
   black task description are unchanged, so the snapshot a screen-off shows is still black (design 32). Nothing
   is lost at design 59d step B's face leave: the root keeps its FULL drawable across night/content swaps (the view
   and its `wallpaper_ground_key` tag survive), and a first show renders in `resumeContent` before the first frame,
   so the face's leave gets the copy without the mark. Memory: still the store's one decoded bitmap; the root's
   `GroundDrawable` only references it; an unchanged key keeps the drawable (no allocation per render). Cold start
   is unchanged (the store's initial state has `systemShowsOurs = false`, so Home drew FULL navy before the load
   already). The kiosk root has no transitions to change. The removed state (`HomeGroundState`, rechecks, the
   posted pause end, `onEnterAnimationComplete`, `HomeFront.takeOwnGroundDue`) is fully gone and `HomeGroundTest`
   pins its absence. The two risks the implementer named are real: Back's predictive animation (device check b) and
   the phone book / Kid Settings (Q-1).
2. **F1** - right. `AndroidLock.faceOn` is the right fact: it is set at the process start (`atProcessStart`, before
   any activity) and on every apply (`face()`), and `render()` reads it live together with `pinUsable` (also set per
   apply) - the stand-in being decided at init doesn't matter, the prompt is decided per render. A face change while
   the lock is up shows at the next render (a digit, the ticker, a resume), exactly as a `pinUsable` change always
   did - no new staleness. In own mode (or when Android's credential is gone, `faceOn` false) UNUSABLE stays. The
   keypad stays disabled while unusable (`keypadEnabled`), which only matters in own mode (r10 run F2: as the face
   our keypad isn't reachable).
3. **QR enrolment syncs** - right. Both QR paths go through `applyProvisioningExtras`
   (`MdmDeviceAdminReceiver.onProfileProvisioningComplete`, the in-app scanner in `SettingsFragmentLauncher`), the
   request is on `onSuccess` only, neither caller requested a sync before (Settings' "Enroll now" does its own and
   doesn't use this function), and `SyncRunner`'s coalescer folds it with any sync the anchor's start or the first
   SSE open asks for. The device owner is exempt from the FGS start limits, so the receiver's coroutine may start
   the anchor.
4. **The pass's trail** - right as built, with Q-2 and Q-3. The classification is sound: hiding sends
   `PACKAGE_REMOVED`, unhiding `PACKAGE_ADDED`, so `onPackageAdded/Removed` about a touched package are the trail
   too, and a real install of an untouched package stays normal (300 ms). `noteRequest` before `trySend` keeps the
   flag ahead of the consumer's take; the races all err to "normal" (300 ms). The touched set is in memory only - a
   process death clears it, nothing persists - and bounded by the package count. The consumer's other callers
   (`reloadApps`, the window edges) pass `null` and stay normal.
5. **Installed versions** - right. `getPackageInfo` replaces the `getApplicationInfo` call (one binder call per app
   as before, no added cost on the report path), minSdk 34 has `PackageInfoFlags` and `longVersionCode`. The clip
   is 60 code points on both sides (Kotlin `offsetByCodePoints`, Rust `clip` by chars). The server reads both
   leniently, stores them in `installed_apps_json` (no migration), and the other readers of that JSON compare by
   package only (`log_play_events`, the pending uninstalls, window_relock), so a version change is never "a new app".
   Matching: F-Droid by the asset id (= versionCode), GitHub/manual by the versionName against `version_of(tag)` - a
   leading `v` on either side ignored, a prefix row handled, `-rc.N` compared verbatim ("1.2.0-rc.1" matches only a
   versionName "1.2.0-rc.1"; a "1.2.0-rc1" app raises nothing - conservative, fine). Once per phone and version by
   the `<app id>:<code>` key; a downgrade raises nothing (`is.code <= was.code`) - but that guard is untested (Q-4).
   An older launcher's report (no codes) raises nothing and is taken. The music app is excluded from the list's
   path (`ta.id != music_id`), so it is never raised twice.

## Findings

### Medium

**Q-1. The copy moves the zoom step to Home <-> phone book and Home <-> Kid Settings.**
`calls/PhoneBookActivity.kt:159` and `ui/kidsettings/KidSettingsActivity.kt:202` still call
`WallpaperGround.apply(this, binding.root)` (TRANSPARENT over Android's wallpaper when it is ours). The user's video
shows that Android renders the photo at a slightly different scale than the copy. Before this branch, Home and these
two screens all showed Android's render, so switching between them showed no step. Now Home shows the copy, and every
switch to the phone book (the grid's first tile) or Kid Settings (swipe left) swaps the copy for Android's render.
During the default activity transition, Home's closing window fades or slides with the copy over the static real
wallpaper, which can show as a soft double image. When the transition ends, the step the user filmed shows. Going back
does the same in reverse. This is no longer a rare hand-over: the kid does it many times a day.
*Fix (XS-S):* draw the copy on both screens too (`WallpaperGround.apply(this, binding.root, ownGround = true)`, the
same pure rule as Home's). It costs no new memory: the store's one bitmap, and the same GPU texture in our process.
Two identical copies cross-fade with no visible change. Android's own render then shows only under the shade and in
Recents. Pin it in `HomeGroundTest` (all three call sites own the ground). The root-cause alternative is Q-7's
`maximumWindowMetrics` render in `WallpaperApplier`, which would make Android's render match the copy. That is bigger
and needs the Jelly Star to prove it. Either way, device check (c) confirms the result. If the user wants to keep the
round XS, ask first. Do not leave it as it is, because the defect the round fixes just moves to a more frequent
transition.

**Q-2. A pass's own Home update now waits 3 s instead of 300 ms (unpinned, and the docs still say 300 ms).**
`ui/home/AppLoads.kt:268`: when a burst started with trail callbacks during the hold (every callback about a package the
running pass changes is in `touchedNow`), the hold's end sets `firstAt = t` but leaves `normalAt` null. The burst is
then trail-only and loads 3 s after the pass ends, at most 30 s. Before this branch it loaded 300 ms after the pass
("one load per pass"). Every parent-driven change reaches Home's grid about 2.7 s later than before: an "Allow" from the
PWA, a pause starting or ending, the override, a time rule's edge, an allow-blocked command. The follow-up was decided
for the stragglers after a release, and that alone ("after a release, a longer quiet window for callbacks about the
packages the pass touched"). It was not decided for the pass's own result. The "do it now" actions are meant to show
at once (the parent-override rule). `launcher/CLAUDE.md:71` still says "the hold's end counts as a request, so the
callbacks the pass's last packages send after it join the same load, 300 ms on (one load per pass)". Mutation L7
(`normalAt = t` at the hold's end) was NOT CAUGHT, so no test pins either behaviour.
*Fix (XS):* the hold's end counts as a normal request: `normalAt = t` in the `wasHeld` branch. The pass's own load is
again 300 ms after it, and the stragglers that come later form their own trail burst and fold as designed. In the
jelly36 numbers that is one more load (the pass's own) than now. Add a `HomeRound7Test` case: trail requests during a
hold, then nothing, loads at release + 300 ms; then stragglers 1-3 s apart fold into one or two loads. If the user
prefers the 3 s, keep the code, correct `launcher/CLAUDE.md` and pin that behaviour instead.

### Low

**Q-3. A pass that touches nothing refreshes the trail window, so the trail may never expire.**
`ui/home/AppLoads.kt:120` (`appLoadTrailAfterRelease`): with `touched` empty and an earlier trail inside its window,
the function returns `AppLoadTrail(kept, now)`, so every pass moves `releasedAt` forward. That includes a steady-state
pass whose loop skips every package. Passes come from every sync, the SSE nudges, the lock's and the windows' edges, the
Play window and the camera lock. While they come less than 2 min apart, the packages one pass touched stay "the trail"
for the whole busy period. A later real update or uninstall of such a package (a catalog push, a Play update) then waits
3 s quiet instead of 300 ms. The effect is small, but it is not the documented "2 min after the pass".
*Fix (XS):* `if (touched.isEmpty()) return previous?.takeIf { now - it.releasedAt in 0 until windowMs }`, which keeps
the earlier release's time. Then the chain joins only passes that changed something. Add the case to the trail test.

**Q-4. The "higher code than the previous report" guard for listed apps has no test (S1 NOT CAUGHT).**
`server/src/handlers/tracked_apps.rs:1524`: with `is.code <= was.code ||` removed,
`every_checked_catalog_app_raises_app_updated_by_its_listed_code` still passes. The guard is what stops a burst of false
notices at the rollout. Take a phone whose checked catalog apps are already at their stored releases when it updates to
this launcher. Its first report with codes has no previous code, so no event. The second report has `was == is ==` the
release code, and that key was never raised. Without the guard, that report raises "<app> updated on <phone>" for every
such app. The same applies to a downgrade onto the stored release (a Play beta replaced by the catalog's build).
*Fix (XS):* add both cases to the test: a first report with codes already at the release, then the same report again,
raises nothing; going from a higher code down to the release's raises nothing.

**Q-5. `installed_apps`' two new fields are not a shared contract file.**
Every recent status-body addition has a byte-identical file in `server/testdata/` and the launcher's test resources:
`time_status_report.json`, `dns_upstream_status.json`, `power_report.json`, `zone_status_report.json`. This one is pinned
only by literals on each side (the launcher's `SyncScheduleTest` substrings, the server's hand-written body). The server
reads the fields leniently, so a drift (a renamed property, a naming-strategy change) would fail silently: no error, and
no notices ever. The risk today is low, because both sides spell the same literal keys.
*Fix (S):* `server/testdata/installed_apps_report.json` (two entries, one with both fields, one with neither),
byte-identical in `launcher/app/src/test/resources/`. The launcher's `SyncScheduleTest` encodes exactly it, the server's
`app_updates` test posts it, and both compare the copies. Add the paths to `launcher.yml`/`server-ci.yml` and the root
CLAUDE.md like the others. Alternatively, write one line in the notifications note saying why it isn't shared. It should
be shared.

### Nits

**Q-6.** `docs/testing/2026-10-24-home-round-run.md:108`: "only packages a pass suspended or hid count as its trail". It
is every package the pass *changes*, including unsuspending and unhiding (`AppEnforcer.kt:438`, after the
`continue`). Say "changed".

**Q-7.** `docs/notes/server/notifications.md` and PLAN say "the versionName against the tag's version, a leading 'v'
ignored". `-rc.N` is compared verbatim. Add half a line saying an app whose versionName spells its RC differently from
the tag ("1.2.0-rc1" vs "v1.2.0-rc.1") raises nothing, so nobody later reads that as a bug.

## Mutation pass (15; all reverted, `git status` clean)

| # | Side | Mutation | Result |
|---|------|----------|--------|
| L1 | launcher | `groundKind` ignores `ownGround` (Home transparent again, the hand-over back) | caught (HomeGroundTest, HomeFrontTest) |
| L2 | launcher | UNUSABLE in android mode (`!usable` without `!androidFace`) | caught (LockGlanceTest) |
| L3 | launcher | the QR sync on `onFailure` instead of `onSuccess` | caught (ProvisioningModeTest) |
| L6 | launcher | `installedVersionName` unclipped | caught (SyncScheduleTest) |
| L4 | launcher | the trail burst at 300 ms / 2 s (no 3 s / 30 s) | caught (HomeRound7Test x2) |
| L5 | launcher | the touched set never expiring (`appLoadTrails` ignores the window) | caught (HomeRound7Test x2) |
| L7 | launcher | the hold's end counts as a normal request (300 ms after a pass) | **NOT CAUGHT** (Q-2) |
| L8 | launcher | `release` doesn't clear `touchedNow` | caught (HomeRound7Test) |
| L9 | launcher | a request not about packages (`null`) counted as the trail | caught (HomeRound7Test) |
| L10 | launcher | a normal request joining a trail burst ignored | caught (HomeRound7Test) |
| S1 | server | `listed_app_updates` without `is.code <= was.code` (a downgrade or the same code raises) | **NOT CAUGHT** (Q-4) |
| S2 | server | `cap_installed_apps` keeps the versionName unclipped | caught |
| S3 | server | the GitHub match against the raw tag (its `v` not stripped) | caught |
| S4 | server | every catalog app, not only the ones checked for the phone | caught |
| S5 | server | an F-Droid row matched by versionName instead of versionCode | caught |

Score: **13 / 15 caught**.

## Suites

- Launcher: `./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest`: BUILD
  SUCCESSFUL, 1793 tests, 0 failures.
- Server: `cargo test`: 1328 passed, 0 failed. `cargo fmt --check`: clean. `cargo clippy --all-targets`: the 13 known
  warnings (models.rs, devices.rs, storytel.rs, dns_engine.rs), none in changed code.

## Emulator checks to add (to "Owed after launcher-small-a")

The six of item 1 (a-f), F1, the QR sync, the trail run and the installed versions are listed already and are right.
Add:

- **1g (after Q-1's fix):** Home -> phone book and Home -> Kid Settings and back, with a photo wallpaper, 24-30 fps, read
  frame by frame. No step and no double image during or after the transition. *Before* the fix, record the same once:
  it tells whether Q-1's step is visible at the transition.
- **1h:** Back from an app into Home *with* the shade's wallpaper zoom recently used (pull the shade on Home, close it,
  open an app, press Back). Android's wallpaper zoom state differs then, so it is the likeliest case for a seam.
- **4b (after Q-2's fix):** an "Allow" from the PWA for a blocked app, and a pause started from the PWA. In logcat, the
  time from the pass's end ("AppEnforcer" apply done) to the next "N apps loaded" should be about 300 ms, and the new
  tile shows on Home within a second of the pass.
- **5b (server):** a phone whose checked catalog app is already at the stored release, updated to this launcher. Two
  status reports in a row should give no "updated" notice (Q-4's case, end to end).

## Fix round (2026-10-25, the implementer)

Every finding fixed as decided; both suites green again: launcher 1795 tests, 0 failures (`assembleDebug
testDebugUnitTest -PwarningsAsErrors=true`); server 1331 passed, `cargo fmt` clean, `clippy --all-targets` the 13 known
warnings. Mutation re-check: **L7** (the hold's end not a normal request again) now **caught** by `HomeRound7Test`'s
"a pass's own load is 300 ms after its release, the later stragglers fold"; **S1** (`listed_app_updates` without
`is.code <= was.code`) now **caught** by `app_updates::an_app_already_at_the_release_or_going_back_to_it_raises_nothing`.

- **Q-1** - Fixed in 7e3af771. `PhoneBookActivity` and `KidSettingsActivity` pass `ownGround = true` to
  `WallpaperGround.apply` (the store's one bitmap, no new memory). The screens that show Android's wallpaper
  (`windowShowWallpaper` through `UIObject.showsSystemWallpaper`, `backgroundWallpaper` in `styles.xml`) are exactly
  three: Home, the phone book and Kid Settings - all three own their ground now; `HomeGroundTest` pins the set (every
  `showsSystemWallpaper`/`isHomeScreen` override) and that none calls `apply` without `ownGround`. The lock's
  `FLAG_SHOW_WALLPAPER` while it asks for Android's pad is on its opaque window with its own ground (left alone).
  Design 08 "The copy stays", `launcher/CLAUDE.md` "Drawing".
- **Q-2** - Fixed in 7e3af771. The hold's end sets `normalAt = t` (a normal request): the pass's own load is 300 ms
  after it again; stragglers after it form their own trail burst. New `HomeRound7Test` case pins both timings (a 10-15 s
  pass with 50 trail callbacks: loads at 15 300 and, for 15 stragglers 2 s apart, 47 000). `launcher/CLAUDE.md` round 7
  (4) corrected.
- **Q-3** - Fixed in 7e3af771. `appLoadTrailAfterRelease` with nothing touched returns the earlier trail if still live,
  unchanged (its release time kept), else `null`; the trail test adds the case and a stream of empty passes that lets
  the trail expire by its own window.
- **Q-4** - Fixed in ebf6cc80. `an_app_already_at_the_release_or_going_back_to_it_raises_nothing`: an older launcher's
  report, then two reports at the release's code (no event), a beta above it and the step back onto it (no event).
- **Q-5** - Fixed in ebf6cc80 (both dirs in one commit). `server/testdata/installed_apps_report.json` (one entry with
  installer and both fields, one with neither), byte-identical in `launcher/app/src/test/resources/`: `SyncScheduleTest`
  encodes exactly it and compares the server's copy; `app_updates::the_installed_apps_contract_matches_the_launchers_copy`
  compares, `the_installed_apps_contract_raises_app_updated` posts it after a report one release back (the event, the
  stored fields). Root `CLAUDE.md` "Shared between the two" and its CI paragraph, `launcher.yml`'s and `server-ci.yml`'s
  path filters (and the latter's comment) list it.
- **Q-6** - Fixed (docs, this commit): the run doc says every package a pass *changed* (suspended, unsuspended, hid or
  unhid).
- **Q-7** - Fixed in ebf6cc80 (`docs/notes/server/notifications.md`; PLAN with this commit): an RC spelled differently
  from its tag raises nothing, by design; the higher-code guard is described there too.
- **Emulator checks**: the four added to `docs/testing/2026-10-24-home-round-run.md`, "Owed after launcher-small-a" -
  1g, 1h, 4b, 5b (1c now expects no step, after Q-1).
