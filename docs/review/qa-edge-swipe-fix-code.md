# Code QA: edge-swipe-fix (the re-ask after a stolen home gesture, design 59d §8.16)

Branch `edge-swipe-fix` (9f796e16) against main `ab0268dd`. Reviewer's branch `qa-edge-swipe-fix` = `edge-swipe-fix` +
this file. Design: 59d §8.9 (option (b)), §8.11 ("§8.9: decision (a)"), §8.13, §8.16. Report:
`docs/testing/2026-10-23-edge-swipe-capture.md` and the "Resolved" part of `docs/testing/2026-10-23-glance-topinset.md`.

## Setup

- Worktree on 9f796e16. I read `git diff ab0268dd..edge-swipe-fix` in full (`StatusBarHealPlan.kt`, `PinLockRuntime.kt`,
  `PinLockActivity.kt`, `GlancePanel.kt`, `StatusBarHealTest.kt`, 59d, `emulator.md`, `launcher/CLAUDE.md`, PLAN, the
  topinset report). I also read the callers and neighbours: `HomeActivity.onCreate`/`onNewIntent`, `HomeFront`, the boot
  cover's Home starts, `BootCover`, `HomePin`, `BootMarkPlan`, `resumeAsFace`, `onStage`/`askAndroid`/`androidAnswered`.
- Full launcher suite under the shared lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL, 1637 tests, 0 failures, 0 errors,
  0 skipped** (`StatusBarHealTest` 13). No new warning: only kapt's `RELEASE_11` line.
- Mutations: applied by an exact-replace script, one batch at a time, with at most one mutation per test method in a
  batch. Each run covered `StatusBarHealTest`, `LockGlanceTest` and `LockMediaGuardTest`. The sources were put back with
  `git checkout` after each batch. The worktree was clean (`git status` empty) before this file.
- No emulator (as briefed).

## What was checked and holds

- **No loop with the heal or our own starts.** A gesture ask can only follow a Home start without
  `HomeFront.EXTRA_BROUGHT`, and nothing the ask does starts Home: `requestDismissKeyguard`, the pad's cancel, `back()`
  and the re-ask don't. The heal writes lock-task features only, and the face's re-front starts the lock, not Home.
  Every Home start of ours carries the extra: `HomeFront.bring`/`bringAfterUnlock` (the relock, install mode's end, the
  update, a role change, the lock's leave, the dark lock-up, BootHome) and the boot cover's `startHome`. `HomePin`'s
  re-seat only rewrites PPAs and starts nothing. The boot cover's locked-boot start resolves to the cover alone before
  the unlock.
- **"Never a third time" is pinned.** In the pure rule, `!retried` is caught. In the glue, `gestureAskRetried = retry`
  and the `askAfterGesture(retry = true)` / `(retry = false)` call sites are caught. A second mark needs a new system
  Home start at least 2 s after the first, and the re-ask creates none. So at most two asks per 2 s.
- **A swipe every 0.5 s** (on the `hw.mainKeys = yes` AVD): the first swipe marks, the lock's resume takes the mark,
  the first ask goes out, the re-occlusion cancels it at about 0.55 s, and the re-ask goes out at about 1.25 s. The
  swipes at 0.5/1.0/1.5 s are inside the 2 s gap, so they mark nothing, but a swipe that lands while the re-ask is in
  flight can make SystemUI cancel it (retried, so it isn't asked again). That round then has no pad. The swipe at about
  2 s marks again. The worst case is 2 asks per 2 s, never a pad storm, and the pad stays once the swiping stops.
- **Parent commands are "ours".** Step A's `bringAfterUnlock` goes through `HomeFront`, so it carries the extra and
  marks nothing.
- **Android mode at boot** never marks. The boot's first CE start is UNLOCKED (`processStartUnlocked`), and the cover's
  starts carry the extra. Own mode at boot does mark: see Q-1.
- **The boot cover's asks (step F, RC3-1, its 3-ask cap)** run in `:bootcover` before the unlock. They share no state
  with the main process's lock and the gesture ask, so nothing interacts.
- **The ASK stage.** The take is skipped while `askedAt != null`. `GlancePanel.askAfterGesture` gates on
  `glancePageShown(stage)` (ASK included, as JL-1's swipe does) and on `leaving`. `LockGlanceTest`'s "the swipe's only
  step" pin reads the swipe's body only, so the new `step(Unlock)` in `askAfterGesture` doesn't touch it. The suite is
  green.
- **Fingerprint.** While the glance occludes the keyguard, SystemUI listens for a fingerprint only with the bouncer up
  (or from dark). A pad that is up adds listening and takes nothing away.
- **Threading.** Everything new is main-thread only: `HomeActivity.onCreate`/`onNewIntent`, the lock's `onResume`,
  `noticeCame` (main per its KDoc), and the screen-on receiver (registered without a handler). Home's
  `onNewIntent`/`onCreate` run before Home's locked resume, and that resume starts the lock, so the mark comes before
  the take. If a lock resume ever ran first, the mark would wait for the next resume, at most 3 s (Q-2's window).
- **Own mode.** The take opens our keypad (`GlanceStageEvent.Unlock` -> KEYPAD). `onStage` closes any ask, so
  `askFromGesture` is reset and no re-ask can happen.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| M1 | drop the 2 s limit in `gestureAskDue` | `a home gesture that got through ...` ("an ask 1.9 s ago") |
| M1b | glue: `lastAskElapsedMs = null` (the 2 s limit dropped at the call) | **not caught** |
| M2a | drop the 1 s notice guard | `a home gesture ...` ("a notice 500 ms ago") |
| M2b | drop the 1 s screen-on guard | `a home gesture ...` ("a screen-on 999 ms ago") |
| M2c | glue: `lastScreenOnElapsedMs = null` | **not caught** |
| M3a | allow a third ask: drop `!retried` | `a gesture's ask cancelled ...` ("the one re-ask went") |
| M3b | allow a third ask: `gestureAskRetried = false` in `askAfterGesture` | the runtime glue test |
| M4a | let a call-live mark through: drop `!callLive` | `a home gesture ...` ("a call is live") |
| M4b | glue: `callLive = false` | the runtime glue test |
| M5 | take the mark after 3 s (window + 1 s) | `a home gesture ...` ("the lock never came back in time") |
| M6a | mark on our own Home start: drop `!broughtByUs` | `a home gesture ...` ("our own HomeFront start") |
| M6b | glue: `broughtByUs = false` at the call | **not caught** |
| M7 | `takeGestureAsk` never clears the mark | the runtime glue test |
| M8 | re-ask after a screen-off: drop `sameScreenOn` | `a gesture's ask cancelled ...` ("a screen-off since") |
| M10 | the mark's time never stamped (`gestureAskAt = now` gone: no limit, and the take always fails) | **not caught** |
| M11 | `noticeCame` doesn't stamp `lastNoticeElapsed` | the runtime glue test |
| M12 | the answer doesn't reset `askFromGesture` | **not caught** |
| M13 | an ask that didn't go keeps `askFromGesture` | **not caught** |
| M16 | `GlancePanel.askAfterGesture` asks while the page is leaving | the runtime glue test |
| M17 | glue: `interactive = true` at the call | **not caught** |

All six briefed mutations are caught in the pure rule. Two of them (the 2 s limit, our own Home start) survive at the
glue's call site. So do the screen-on and interactive inputs, the mark's stamp, and both resets of `askFromGesture`.
See Q-3.

## Findings

### Q-1 (Medium) - a Home start at a process start marks a gesture ask: our keypad at an own-mode boot, the pad or keypad after an update

`PinLockRuntime.kt:865` (`gestureAskDue` in `homeIntent`), fed by `HomeActivity.kt:249-250` (JL-7's `onCreate` path).

What's wrong: the mark takes any Home intent without the extra as "a gesture that got through", provided our lock is
LOCKED and the screen is on. The system also starts Home itself at a process start, with a plain HOME intent:

- **An own-mode boot.** Every process start is LOCKED, and the screen is on. FallbackHome finishing at the unlock, or
  the own-mode boot cover disabled at the take-over, makes the system start HomeActivity: `onCreate`, no saved state,
  CATEGORY_HOME, no extra. `lastScreenOnElapsed` is null in a new process (no `SCREEN_ON` comes at boot), and so are
  the notice stamp and the last ask. So `gestureAskDue` is true and the mark is set.
  - Whether it is taken depends on 16e. The lock comes at `bootMarkStartMs` + 3 s, and that start is
    `min(cover's first frame, Home's mark shown)`. With the boot cover, or when Home's mark isn't shown within the 1 s
    wait (a slow start), the lock resumes within 3 s of Home's `onCreate`. The mark is then taken, and the boot comes
    up on our keypad instead of the glance. Without the cover it misses the window only by the time from Home's
    `onCreate` to its first focus, which is a few hundred ms.
- **A package-replaced restart with the screen on** (Install Now installs at once, screen on or off). The force-stop
  removes our activities, and a new process is LOCKED (not the boot's first CE start). The system's plain HOME start
  creates HomeActivity, and `bringHomeAfterUpdate` then puts the lock up within 3 s. In android mode Android's pad pops
  over the glance, unasked. In own mode the keypad shows. Which comes first, the system's start or
  `PackageReplacedReceiver`'s `HomeFront` one, I can't prove without a device. Either order lands a plain HOME intent on
  Home (`onCreate` or `onNewIntent`), so the mark is set.

The heal accepted the same false positive (JL-7), because a heal at boot is harmless. An ask isn't: it changes what
the kid sees. §8.16 says the trigger "is the same proof as the heal", but at a process start it is no proof of a
gesture.

No security impact: the ask grants nothing a handle swipe doesn't.

Expected fix: a Home start can only "bring the lock back" if the lock was up in this process when it came. Make that a
pure input of `gestureAskDue`, e.g. `lockWasUp = PinLockActivity.instances > 0` read in `homeIntent`, or a runtime flag
set by the lock's first resume of the process. That is false at a boot, after an update and at any restart where the
system creates Home before the lock exists. Add a test row ("no lock in this process yet: a boot's or an update's Home
start") and pin the argument at the call. Device check: an own-mode reboot (`smoke-test.sh REBOOT=1`) comes up on the
glance, not the keypad. This also covers a crash restart in which the system creates Home fresh.

### Q-2 (Low) - the mark outlives a screen-off, a call or a mode edge, and the take re-checks nothing

`PinLockRuntime.kt:889-893` (`takeGestureAsk`), `PinLockActivity.kt:743` (the take), `PinLockActivity.kt:317-323` (the
retry's guard).

What's wrong: the brief expects a screen-off, a call, a notice wake and a pause or window edge to clear the mark. They
don't. `gestureAskPending` is cleared only by the take, and `gestureAskTaken` checks only the 3 s age. The gates are
evaluated at the mark alone.

The mark goes untaken when the lock's next resume returns before the take: `leave()` when not LOCKED, or
`resumeAsFace()` returning true. It also goes untaken when no resume comes. Any lock resume within 3 s then asks:

- the screen-on after a quick off and on, where the lock-up's lock resumes at the wake;
- a lock resume for a call that started meanwhile (`LockResumed` -> `showCall`). In android mode a pad asked then sits
  in SystemUI's shade window, above the incoming call screen.

The window is small (3 s, and it needs a skipped take), so this is Low. The retry's guard (`askAfterGesture`) checks
`resumed`, `isFinishing`, `finishingAfterUnlock` and LOCKED, but not a live call. `resumed` covers the common case (a
call screen pauses the lock).

Expected fix: keep `screenOffs` with the mark and drop the mark when it differs at the take. Clear the mark at
`PinLockRuntime`'s LOCKED edge and at screen-off. At the take, and in the retry's guard, also require the screen on
and `!callLiveNow(...)`: the take is pure and cheap, so give `gestureAskTaken` those inputs. Pin each one.

### Q-3 (Low) - the glue's arguments to `gestureAskDue`, the mark's stamp and both resets of `askFromGesture` are unpinned

`StatusBarHealTest.kt:297-321`.

What's wrong: mutations M1b, M2c, M6b, M10, M12, M13 and M17 survive. The test pins `callLive = callLiveNow(context),`
and `lastNoticeElapsedMs = lastNoticeElapsed,` but not `locked = ...`, `interactive = interactive(context)`,
`broughtByUs = broughtByUs`, `lastScreenOnElapsedMs = lastScreenOnElapsed` or `lastAskElapsedMs = gestureAskAt`. It
doesn't pin `gestureAskAt = now` either, whose loss turns the feature off silently: the take always fails. Nor does it
pin `if (current) askFromGesture = false` or `if (askedAt == null) askFromGesture = false`. Without them the kid's own
later swipe ask inherits the gesture flag and is re-asked after a quick cancel.

So two of the briefed guards (the 2 s limit, our own start) are caught only in the pure rule, not where they're wired.

Expected fix: pin the whole `gestureAskDue(...)` call as one block, e.g. compare the text between `if (gestureAskDue(`
and `) {` with the expected eight lines, plus `gestureAskAt = now`. Pin the two resets in `androidAnswered` and
`askAfterGesture`. Keep it lean: a few `contains`, no new matrix.

### Q-4 (Low, docs) - `emulator.md` §3 puts the comment on the `hw.mainKeys` value line

`docs/testing/emulator.md:77`: `hw.mainKeys = no          # REQUIRED - see below: ...`.

What's wrong: the block is what a reader pastes into `config.ini` ("replace these keys"). As far as I know, the
emulator's ini reader treats only a line starting with `#` as a comment. A trailing `# ...` stays part of the value, so
the value is `no          # REQUIRED ...`. Its boolean parse then falls back to the property's default, and
`hardware-properties.ini` here has `hw.mainKeys` default **yes**. That is exactly the failure the line warns about. I
couldn't confirm the parser without the emulator, but the fix costs nothing.

Expected fix: put the comment on its own line above (`# REQUIRED - "yes" (...) means no navigation bar`) and keep
`hw.mainKeys = no` bare. The check steps that follow (`getprop qemu.hw.mainkeys` = `0`, the `taskbar mDisabledFlags`
line) are right and would catch it.

### Q-5 (Low, docs/device checks) - the safety net's own case isn't verified, and the device check lists only the root cause

PLAN.md (the `edge-swipe-fix` entry), 59d §8.16 "Verified on `jelly36`".

What's wrong: §8.16 keeps the net "for a desync on a phone, where the heal fixes only the next swipe". It was verified
only on `hw.mainKeys = yes`, where the heal can't act. In the desync case, the same Home start also runs the
`SYSTEM_HOME` heal: a visible 250 ms SYSTEM_INFO flip and a second lock-task pass while the first ask is out. Whether
the pad still stays (or whether the heal's pass is what cancels the first ask) is unchecked.

The first ask's cancel was measured at 0.25-0.3 s, which equals `STATUS_BAR_HEAL_FLIP_MS`. The heal fired on the user's
AVD too, at most every 5 s. So confirm the cancel also comes on a swipe that has no heal. If it does, the
re-occlusion is the cause.

PLAN's device check (ten handle swipes on the Jelly Star, all `TYPE_RESET_GESTURE`) proves the root cause, not the net.

Expected fix: add to §8.16 and PLAN's device check:

1. On `jelly36` with `hw.mainKeys = no` and the 16d debug override (HOME in the LOCKED features), one handle swipe. The
   heal fires, the pad stays, and the next swipe is refused.
2. One swipe on `hw.mainKeys = yes` within 5 s of the last heal. Is the first ask still cancelled?
3. Q-1's own-mode reboot.

### Q-6 (Nit) - `askAndroid`'s KDoc is now orphaned

`PinLockActivity.kt:313-316`.

What's wrong: the new `askAfterGesture` and its KDoc were inserted between `askAndroid`'s KDoc ("`requestDismissKeyguard`
- Android's PIN pad over the glance; ...") and `askAndroid`. Two KDocs now sit back to back above `askAfterGesture`, and
`askAndroid` has none.

Expected fix: move `askAfterGesture` (with its KDoc) above the `requestDismissKeyguard` KDoc.

### Q-7 (Nit) - `GESTURE_ASK_QUIET_MS`'s KDoc promises a call-start guard that isn't built

`StatusBarHealPlan.kt:81`: "a notification, a call's start or a screen-on this recent".

What's wrong: no call start is stamped. The call guard is `callLive` at the mark.

Expected fix: "a notice or a screen-on this recent (a live call never asks: [callLive])", or stamp a call's start if
one is wanted (with Q-2's take re-check it isn't needed).

### Q-8 (Nit) - §8.11 still says option (b) isn't built

`docs/design/59d-android-mode-inventory.md:758`: "So (b)'s premise ... is false and (b) isn't built".

Expected fix: append "(§8.16 built it as a safety net, 2026-10-23)". §8.15's last line already points there.

### Q-9 (Nit) - `homeIntent` runs `callLiveNow` (a Telecom binder call) on every Home intent

`PinLockRuntime.kt:865-874`.

What's wrong: the call's arguments are evaluated before the pure rule short-circuits. So
`TelecomManager.isInManagedCall` and the audio-mode read run on the main thread for every Home press while unlocked
too. That's harmless but wasted.

Expected fix: evaluate `mode == LockMode.LOCKED && interactive(context) && !broughtByUs` first, or keep the call as is
and wrap it in an `if (mode == LockMode.LOCKED && !broughtByUs)`. Pin whichever shape is chosen (Q-3).

## Docs

- §8.16 states the rules as built (the trigger, the mark's gates, the take, the re-ask, "never a third time"), the
  root cause (`hw.mainKeys = yes`: no NavigationBar/TaskbarDelegate, so no `home_disabled`) and the proof (the same
  image and data, one line flipped, the user's dump reproduced; 13 swipes clean on `no`). It is accurate apart from the
  untested desync case (Q-5) and the process-start case (Q-1). Its "it never asks with a call live ... or within 1 s of
  a notice ... or a screen-on" holds only at the mark (Q-2).
- `launcher/CLAUDE.md`'s heal bullet matches the code.
- `emulator.md` §3: the `REQUIRED` note and the fix-and-check steps for a profile-less AVD are right. `avdmanager`
  without a profile does give `hw.mainKeys = yes`: `hardware-properties.ini`'s default is yes, and every AVD here has
  `no` set explicitly. The inline comment is the one problem (Q-4).
- PLAN: the entry and a device check are there; the check covers the root cause only (Q-5).

## Verdict

**Merge after a fix round.** Q-1 (Medium) makes an own-mode boot (with the cover or a slow start) and a screen-on update
restart ask unasked. The fix is a single pure input. Q-2..Q-5 are small and belong in the same round. Q-6..Q-9 are
nits. The core design holds: it can't loop, it is bounded to two asks per 2 s, our own and the parent's starts are
excluded, and the boot cover and fingerprint are untouched.
