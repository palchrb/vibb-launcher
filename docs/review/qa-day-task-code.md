# Code QA: day-task (Dagen is a task of its own - a Recents card, the system's task-close animation)

Branch `day-task` (b3094f31, one commit, cut from main 8b1252c0) against main `8baaf932`. Reviewer's branch
`qa-launcher-small-b` = `day-task` + the three small reviews. Design: PLAN "Fix backlog" ("Dagen is not a task of its
own: recents shows an empty frame, Home/Back cut without the app-close animation", the user, 2026-10-27), design 69
(Dagen), E5's H1 (the reminder row's Dagen in its own task).

## Setup

- Worktree on b3094f31 (detached: the branch is held by the implementer's worktree). I read `git diff
  main..day-task` in full, and the code around it: the whole `DayActivity` (`onCreate`, `onNewIntent`, `onResume`,
  `intent`), `EventActivity`/`EventEditActivity`'s `onResume`, Kid Settings' `redirectIfLocked` and its pref
  listener, Home's `redirectToLockScreenIfLocked`, `LockActivity.start`/`startNow`/`render`, the glance's DAY open
  (`GlanceOpener.run`, `GlanceTarget.Day`, `PinLockActivity.go`), `TimeRulesRuntime.recheck`'s own time-rule start,
  `PinLockRuntime.afterUnlock`, `PhoneBookActivity.intent`/`missedCallsIntent`, the manifest's affinities, and
  `LockTaskHelpers`/`LockTaskChrome` for what Recents does under a lock.
- Launcher, full suite under the lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL in 1m 24s, 1880 tests, 0 failures,
  0 errors, 0 skipped** (main: 1878; `DayTaskTest` 2, new). The only warning is kapt's `RELEASE_11` line.
- Mutations: two batches by an exact-replace script, each run `testDebugUnitTest --tests '*DayTaskTest' --tests
  '*LockGlanceGuardTest' --tests '*KidScreensEscapeTest' --tests '*PinLockManifestTest'` (37 tests). The sources were
  put back with `git checkout -- launcher/app/src` after each batch, `git status` empty before this file.
- No emulator (as briefed): `docs/testing/emulator.md` §6w step 8 is the emulator's - the Recents card with "Dagen" and
  the icon, Android's task-close animation on Home and Back, the reminder row landing on its day with an event page
  open, and Recents › Dagen under a time rule coming back to the time-rule screen.

## What was checked and holds

- **The task.** The three Dagen activities carry `taskAffinity="${applicationId}.day"`, none is `excludeFromRecents`,
  all stay `exported="false"`, `DayActivity` keeps `singleTop`, and no other activity of ours shares the affinity -
  all pinned by `DayTaskTest` against the manifest. `DayActivity.intent` is the one way in: Home's tile and the glance
  both call it (`DayTaskTest`, `LockGlanceGuardTest`); `glanceIntent` is gone.
- **`LockGlanceGuardTest`'s pin change is legitimate.** The old line pinned `glanceIntent`'s `addFlags(NEW_TASK)`; the
  new one pins the one intent's own construction `Intent(context, DayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`
  plus the opener's call of `DayActivity.intent(activity, day)`. E5's H1 invariant (Dagen from the lock never in the
  lock's task) is still pinned, now for both callers; mutation D2 (no NEW_TASK) fails it.
- **The reminder row still reaches the day with an event page on top.** `GlanceTarget.Day.epochDay` is a non-null
  `Long`, so the glance's call always has a day and therefore `FLAG_ACTIVITY_CLEAR_TOP`; with `singleTop` Android
  finishes the pages above the existing `DayActivity` and delivers `onNewIntent`, which re-reads `EXTRA_DAY` and moves
  the pager (`positionOf(anchor, day)`). From the lock the order is unchanged: `go()` brings Home (kiosk on), opens,
  then `finishAndRemoveTask()`. Home's tile sends no day and no CLEAR_TOP, so an existing task comes back as it was.
- **The guard mirrors Kid Settings exactly**: the same pref read (`LauncherPreferences.mdm().lockReason()`), the same
  `LockActivity.start(this)`, no `finish()` (Home's `redirectToLockScreenIfLocked` is the same shape). `start` goes
  through `KeyguardCover.afterAndroidUnlock` (at once with the keyguard open - a resumed Dagen means it is) to
  `startNow`: `FLAG_ACTIVITY_NEW_TASK` with `LockActivity`'s default affinity, i.e. Home's task, which comes to the
  front with the time-rule screen on top; Dagen's task stays behind. **It can't loop**: it runs only on Dagen's resume
  (Recents, a tap), the time-rule screen holds Back, and `LockActivity.render` finishes itself the moment the reason is
  `NONE`. **It can't fight `RefrontPolicy`**: that is the PIN lock's re-front for its LOCKED mode, under which no Dagen
  resumes (the LOCKED chrome drops OVERVIEW, features 113, and the lock is in front); the guard reads the time rule's
  `lockReason`, not the lock mode, and `startNow` stamps the start as ours (`PinLockRuntime.startOwn`).
- **A rule beginning while Dagen is open** is covered without a pref listener: `TimeRulesRuntime.recheck` starts the
  time-rule screen itself when a lock begins (`whenUnlocked`, no live call), as do `WindowRelock`, `MdmSyncWorker` and
  `afterUnlock`. Kid Settings' and Home's extra pref listeners are belt and braces; Dagen's omission is defensible.
- **The `.day` task under a pause or a kiosk relock.** Nothing ends it - and nothing ended the "missed-calls task"
  either: `missedCallsIntent` has NEW_TASK but `PhoneBookActivity` has no affinity, so it lands in Home's task and dies
  with `clearTaskOnLaunch`; PLAN's comparison is with something that was never a task. What happens instead: a pause is
  unrestricted; under a time rule with the kiosk on the plan pins our package, OVERVIEW stays unless the PIN lock is
  LOCKED, Recents shows Dagen's card, and the guard puts the time-rule screen back on top (the PLAN's own reason for
  it); under the PIN lock there is no Recents. The lock-up at screen-off no longer clears Dagen (HOME_THEN_LOCK's typed
  Home start used to, through `clearTaskOnLaunch`); the unlock lands on Home with Dagen behind in Recents - "as a normal
  app", as the entry says.
- **Nothing else assumed Dagen died with Home's task.** The only references outside `ui/day` are the two callers;
  `CalendarStore`'s listener and `onResume` close it when Dagen goes off; screen time counts it as before (not a free
  screen); a cold start into `DayActivity` from a Recents card after a process death is the phone book's precedent
  (the missed-call notification's tap). `KidScreensEscapeTest` stays green with the new `LockActivity` import.
- **The Recents card.** `setTaskDescription` on the root with `R.string.day_title` and `R.mipmap.ic_launcher`
  (minSdk 34, the Builder needs no version check); AOSP's `Task.updateTaskDescription` takes the first label and icon
  from the top activity down to the root, so an event page or the form on top keeps the card's name and icon. No
  `FLAG_SECURE` on any Dagen screen, so the card shows the day.
- The one `GridTile.Day` start in Home and the glance's call are pinned by source; the suite's `PinLockManifestTest`
  and `KidScreensEscapeTest` are untouched.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| D1 | manifest: `EventActivity` without the `.day` affinity | `Dagen's three activities share their own task ...` |
| D2 | `intent()`: no `FLAG_ACTIVITY_NEW_TASK` | `LockGlanceGuardTest` `the opener has one send and two starts ...` |
| D3 | `intent()`: no `FLAG_ACTIVITY_CLEAR_TOP` with a day | **not caught** |
| D4 | `redirectIfLocked`: the condition inverted | **not caught** |
| D5 | `EventActivity.onResume`: no guard | `Home's tile and the glance open Dagen with the one intent ...` |
| D6 | Home's tile: the plain in-task intent again | the same |
| D7 | glue: the task description without its label | the same (its `.setLabel(..)` pin; run in D6's batch, both lines are plain `contains` pins) |

The manifest, the one intent's NEW_TASK, both callers, the three guards' presence and the label are pinned; the
guard's own rule and the CLEAR_TOP are not (Q-1).

## Findings

### Q-1 (Low) - the guard's condition and the CLEAR_TOP are unpinned

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/day/DayActivity.kt:233-239` (`intent`), `:242-246`
(`redirectIfLocked`), `DayTaskTest`.

What's wrong: D4 survives - with the condition inverted every Dagen open outside a rule would start the time-rule
screen (which finishes at once, a flash), and under a rule Recents › Dagen would sit over the time-rule screen, the
exact hole the guard closes. D3 survives - without CLEAR_TOP the reminder's tap lands on whatever page was open, not the
day (the emulator step's "then lands on the day view itself" would fail, but only on a phone). `DayTaskTest` pins only
that `redirectIfLocked(this)` appears in the three `onResume`s.

Expected fix: two `contains` pins in `DayTaskTest`: the line `if (LauncherPreferences.mdm().lockReason() ==
LockReason.NONE) return false` followed by `LockActivity.start(activity)`, and `addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)`
inside `if (day != null) {` (a substring of `intent`'s body).

### Q-2 (Low, docs) - launcher/CLAUDE.md still describes `glanceIntent`

`launcher/CLAUDE.md:1437` ("started from the glance's reminder row by `glanceIntent`, `FLAG_ACTIVITY_NEW_TASK` - from
the lock's task it died with the lock, E5's H1"); the branch doesn't touch the file.

What's wrong: `glanceIntent` no longer exists; the Dagen UI bullet doesn't know the `.day` task, the one intent, the
Recents card or the guard.

Expected fix: replace that clause with: Dagen is a task of its own (`taskAffinity` `.day` on the three activities, in
Recents with "Dagen" and the launcher's icon as its card); the one `DayActivity.intent` (NEW_TASK; with a day also
CLEAR_TOP, so the reminder row lands on the day view) serves Home's tile and the glance; the three screens redirect to
the time-rule screen on resume while a lock reason is set (Kid Settings' rule), since Recents can bring the task over it.

### Q-3 (Nit) - the reminder's CLEAR_TOP throws away an open "Ny avtale"

Before this branch the form died at every screen-off anyway (Home's task was cleared); now it survives the lock-up, and
the one thing that still discards it silently is a reminder row's tap + code (CLEAR_TOP finishes `EventEditActivity`,
nothing saved). Acceptable - say so in `intent`'s KDoc, or deliver a day without CLEAR_TOP when the top is the form.

### Q-4 (Nit) - the phone book and Kid Settings keep the in-task behaviour

Home's phone-book tile (`PhoneBookActivity.intent`, no NEW_TASK, no affinity) and the Settings tile still open inside
Home's task: no Recents card, the theme's fade. The user's complaint named Dagen; whether the two should follow is a
product call for the user, not a defect here.

### Q-5 (Nit) - `onNewIntent` doesn't `setIntent(intent)`

Harmless today (`onCreate` prefers `STATE_DAY` after a recreation, and nothing else reads `intent` later), but a later
reader of `intent` would see the first start's day.

## Docs

PLAN's entry and §6w step 8 match the code (the affinity, the one intent, the task description, the guard, the
`dumpsys activity recents` count). The manifest comment is right. Q-2 is the one stale place.

## Verdict

**Merge.** Nothing Medium: the task, the one intent, the reminder's landing and the guard all hold against the code,
and the guard can neither loop nor fight the PIN lock. Q-1 (two source pins) and Q-2 (one doc clause) are a few lines
and can ride with the merge or the next launcher round. The animation and the card are §6w step 8's to prove.

Suite results, verbatim: `BUILD SUCCESSFUL in 1m 24s` - 1880 tests, 0 failures, 0 errors, 0 skipped.
