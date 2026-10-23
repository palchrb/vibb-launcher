# QA (code): the rc.3 run's fix round (RC3-1..3)

Branch `rc3-fix` (e968656c code + tests, 2bdb3ccb docs) on main e84514f2. Launcher only. Reviewed 2026-10-23 against
design 59d §8.14 (and §8.3, §8.6 step F, §8.10 step B, §8.12 step M) and the fix run's evidence
(`docs/testing/2026-10-23-rc3-fix-run.md`). No emulator was run for this review.

## Summary

The three fixes are correct and narrow. The pure rules are well tested. Out of 23 mutations, 21 were caught and the
two that weren't are equivalent: no reachable state tells them apart.

- **RC3-1:** the silence clock's lifecycle is sound. The pause drops every callback, the resume posts the clock again,
  the stop and every answer drop it, `onDestroy` clears the handler, and `padAnswer` checks `isDestroyed`. Only one
  `padSilent` is ever queued.
- **RC3-2:** the flag is set on every path into ASK, and the window stays opaque.
- **RC3-3:** the Back hold keeps step M's "never stranded". Without a stamp, Back is held for at most 30 s + 250 ms
  after the unlock, and then the hand-over's disable counts as the take-over and Back is let go.

There is one Medium finding: the cadence after the third re-ask, as the brief asked. The rest are Low (one rule edge
and doc accuracy) or Nit.

Counts: High 0, Medium 1, Low 4, Nit 2. **Verdict: merge after a fix round** (Q-1 and Q-2 are code and test changes,
each a few lines; the rest are words).

## Setup

- Worktree on `qa-rc3-fix` = `rc3-fix` + this file. JDK 17, `local.properties` (gitignored), no `tsnet.aar` (the
  debug stub).
- **Full suite:** `./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest`.
  BUILD SUCCESSFUL. **1630 tests, 0 failures, 0 skipped**, the same count as the fix run reports. The `%` rename
  clears CI's warnings-as-errors.
- **Mutations:** each one was applied alone, then `BootCoverPlanTest`, `BootCoverManifestTest` and
  `AndroidLockFaceTest` were run, then the file was reverted with `git checkout --`. At the end the tree was clean
  apart from this file.

## Mutation table

`BootCoverPlanTest` names are shortened: *silent* = `an ask with no answer is asked again at 3, 6 and 12 s, then every
12 s - uncounted, the same ask`, *RC3-3* = `no Home start of the cover's before the take-over, Back held until it - the
30 s hand-over still rescues`, *B2* = `f3-run B2 - destroyed by the system while in front, ...`.

| # | Mutation | Caught by |
|---|---|---|
| M1 | SILENT re-asks with the screen off (drop `!interactive`) | BootCoverPlanTest *silent* |
| M2 | the re-ask is counted (`autoAsks + 1`) | *silent* |
| M3 | an automatic ask is re-asked in a call (drop `pad.auto && inCall`) | *silent* |
| M4 | a tapped ask isn't re-asked in a call (`inCall` for `pad.auto && inCall`) | *silent* |
| M5 | a lost gate keeps `silentDue` | *silent* |
| M6 | SILENT bypasses the gate | *silent* |
| M7 | after the third re-ask, give up (600 s) - the first build's schedule | *silent* |
| M8 | `BOOT_PAD_SILENT_EVERY_MS` 6 s | *silent* (the Q-1 change must update it) |
| M9 | each re-ask restarts the clock at 3 s | *silent* |
| M10 | a stop keeps `silentDue` | *silent*; `a cancel with the screen off isn't counted ...` |
| M11 | SILENT drops its `!pad.open` check | **NOT CAUGHT** - equivalent: every transition that closes `open` also clears `silentDue` |
| M12 | RELAUNCHED in the dark keeps `silentDue` | **NOT CAUGHT** - equivalent: the resume's post then meets `!pad.open` (`none`), and the next ask or stop resets it |
| M13 | a retry's ask starts no clock | *silent* |
| M14 | `coverHoldsBack` ignores the stamp | *RC3-3* |
| M15 | `coverHoldsBack` holds before the unlock (drop `unlocked`) | *RC3-3* |
| M16 | `coverHoldsBack` = face only (the old rule) | *RC3-3* |
| M17 | `coverDestroyStartsHome` ignores `takenOver` (Back's Home start before the stamp) | *B2*; *RC3-3* |
| M18 | `applyBackHold` passes `takenOver = true` | BootCoverManifestTest `the cover shows over the keyguard only gated at runtime, ...` |
| M19 | `onResume` doesn't post the silence again | the same BootCoverManifestTest |
| M20 | `padStep` doesn't drop the post with the open ask | the same BootCoverManifestTest |
| M21 | `onDestroy`'s `takenOver` from memory only (no fresh stamp read) | BootCoverManifestTest `android mode keeps the cover enabled ... (59d step M)` |
| M22 | the glance's flag is cleared by the leave (no `finishingAfterUnlock` guard) | AndroidLockFaceTest `59d B - the face's leave hides the content first, ...` |
| M23 | the flag in own mode too (drop `AndroidLock.faceOn`) | the same AndroidLockFaceTest |

21 of 23 caught; the two survivors are equivalent mutants (no reachable state tells them apart), so no test is owed.
M18-M23 are caught by source pins, not by behaviour - as the rest of these activity tests.

## Checks (no finding)

- **SILENT and the cap/arm:** a re-ask keeps `autoAsks`, `armed` and `auto` (M2). Only a stop arms. A tap or a
  relaunch restarts the clock at 3 s, with `reasks = 0`.
- **The gate:** `padStep` reads the gate again for every SILENT, and `askPad` reads it once more before
  `requestDismissKeyguard`. When the gate is lost, the pad closes and its clock is dropped (M5, M6).
- **`askId` and a real answer:** every re-ask adds a callback to SystemUI's `DismissCallbackRegistry`, and SystemUI
  notifies all of them (`notifyDismissSucceeded(4)` in m2). So the newest id always gets the answer, and the older
  ones are dropped. Two narrow cases remain:
  - A SILENT can land in the binder round trip of a real answer. A dropped SUCCEEDED is harmless: the unlock flips
    the gate. A dropped CANCELLED leaves the ask open, and the next re-ask brings the pad back once.
  - Both cases are timing-narrow and self-healing. Not worth code.
- **Relaunch:** a carried pad with `silentDue` posts the clock at `onResume`. RELAUNCHED's `ask()` then replaces it
  with a fresh 3 s, and `removeCallbacks` keeps it to one post.
- **Back on the BFU face:** `coverHoldsBack(face = true, ..)` is true, as `holdBack(show)` was. Back stays registered
  through the unlock until the take-over, with no unregister/register gap, because `holdsBack` only changes on an
  edge.
- **The pad's own Back:** the bouncer is SystemUI's focused window, so our `OnBackInvokedCallback` never sees its Back.
- **Calls and the hold:** the take-over (`BootCover.init` -> `takeOver` -> `markTakenOver`) runs at
  `PinLockRuntime.init` whatever a call does. So the hold ends at the stamp in a call too, and START_HOME then works
  as before.
- **The stamp in `onDestroy`:** it is read fresh (the stamp file, or the cover's own disable), so a stamp written after
  the last 250 ms poll still counts (M21).
- **The inert cover:** before the unlock it is not held, as before. After the unlock it is held until the take-over,
  the same as an active cover.
- **RC3-2, where the flag is set:** every stage change goes through `GlancePanel.step` or `resumeNow`, and both call
  `onStage`. So the flag is set on every way into ASK: the swipe up, a row, and a re-ask by tap.
  - It is cleared on cancel, own keypad, screen-off -> LockUp and back.
  - A recreated activity starts at GLANCE with a fresh window.
- **RC3-2, opacity:** `PinLockTheme` has a black `windowBackground`, so the window stays opaque. The night mark and
  `FLAG_SECURE` are unaffected, and lock task doesn't look at the flag.
- **RC3-2, cost:** the wallpaper is visible behind the glance only during ASK. That is a static wallpaper's surface,
  culled behind an opaque layer: negligible.
- **RC3-2, two paths with no change from before:**
  - `UNLOCK_TAP` -> LEAVE (Android's lock already open when the swipe comes): the flag and the leave come in one pass,
    variant C of the fix run. It slides as before. No regression, and rare.
  - A leave refused as the kiosk root (`leaveRefused`): `finishingAfterUnlock` is never reset, so the flag stays on.
    It is invisible on an opaque window.

## Findings

### Q-1 (Medium): the cadence after the third re-ask should be 6 s, not 12 s

`launcher/app/src/main/java/com/kidslauncher/mdm/lock/BootCoverPlan.kt:424`
(`BOOT_PAD_SILENT_EVERY_MS = 12_000L`).

**Why 6 s.** The keyguard became ready 26-42 s after the first ask, which is always past the 12 s re-ask. From then on
the kid looks at SystemUI's clock with dead touches until the next re-ask. At 12 s that wait is up to 12 s; r5's was
3.6 s by luck.

**No cost found:**
- A re-ask with the pad up only adds a callback and logs `Show primary bouncer requested`. `show` returns early when
  the bouncer is showing, and the digits stay (m1/m2).
- The registry is cleared at every answer.
- The re-asks stop at the screen-off/stop, so a screen-on period bounds how many there are (about 5 per 30 s at 6 s).
- The IPC is trivial.

**Fix:**
- Set `BOOT_PAD_SILENT_EVERY_MS = 6_000L`, so the re-asks come at 3, 6, 12, 18, 24 s ...
- In the test `an ask with no answer is asked again at 3, 6 and 12 s, then every 12 s`, expect
  `listOf(3_000L, 6_000L, 12_000L, 18_000L, 24_000L)` and rename it.
- Change "every 12 s" to "every 6 s" in the constant's KDoc, `bootPadStep`'s KDoc, 59d §8.14, `launcher/CLAUDE.md`,
  PLAN's RC3-1 line and the rc3-run report's "Fixed in" note. (M8 confirms the test pins the constant.)

### Q-2 (Low): a SILENT that comes "not now" stops the clock but leaves the ask open

`BootCoverPlan.kt:568`:
`!interactive || (pad.auto && inCall) -> PadStep(pad.copy(silentDue = false), PadAction.NONE)`.

The rule counts on "the stop that follows" to close the ask. If no stop comes, the ask stays `open` with nothing
posted. Two ways that happens:
- An automatic ask's call ends before a call screen covered the cover (`isInManagedCall` goes true, then false, while
  the cover stays resumed).
- A SILENT is handled with the screen already off but before `onPause`, and the screen comes back on before any stop.

Then:
- Nothing asks again.
- RESUMED says `none`, because the ask is open.
- The line stays hidden, again because the ask is open.

So the face is bare with an ask SystemUI may have dropped: F59d-2's "never bare with no pad coming". A tap still asks,
since the root is clickable while gated. This is rare, but it is exactly RC3-1's failure shape.

**Fix:** skip the tick and keep the clock:
`!interactive || (pad.auto && inCall) -> PadStep(pad, PadAction.NONE, bootPadSilentInMs(pad.reasks))`.
`silentDue` stays true. The stop still drops the clock, and the pause drops the post, which the resume puts back. Then
adapt the test's two "not with the screen off / in a call" lines:
- the result keeps `silentDue` and has a `silentInMs`;
- a later SILENT with the call over asks.

The activity's "not asked again" log then reads "skipped (screen off or a call)".

### Q-3 (Low): RC3-2 changes the leave's transition for a row's target and the time-rule leave too, and neither is verified

`PinLockActivity.kt:273`. The fix run's "Not verified" says "The same leave path, with no new code there".

The new flag does reach those paths. The closing glance is now collected with `SHOW_WALLPAPER` whenever the success
came from ASK. So with a row's target opening over Home (`GlanceOpener.open(.., homeBelow)`), the shell sees a closing
change with the wallpaper next to an opening one without it. The wallpaper transit type then changes for the target's
open as well as for the glance's close. Nothing shows this is wrong, but it is a different animation choice than the
rc.3 run saw.

**Fix:** reword the "Not verified" line, and put "a row's target after the pad (photo ground, 24 fps), and the
time-rule hand-over" on the next device run's list (the Jelly Star).

### Q-4 (Low): the claim "before the stamp, the system's HOME brings a new cover" isn't guaranteed

Where it is stated:
- `BootCoverPlan.kt:374`, the KDoc of `coverDestroyStartsHome`: "before the stamp the system's HOME brings a new cover
  (or our Home)".
- 59d §8.14's RC3-3 bullets.

When a cover in front is finished by the system before the stamp, the home root task resumes its next activity. On a
non-graceful boot that can be the stock launcher left below, which is F3R-1's case and the reason
`coverDestroyStartsHome` exists. It stays up until `BootHome`'s start. With Back held, no known trigger is left, so
the trade (no Home start into a busy main process) is right. But the docs should call it a residual and not a
guarantee.

**Fix:**
- Reword the KDoc and §8.14: "the residual: a system finish before the stamp shows what the home task has below until
  our Home's boot start; Back no longer causes one".
- Add `Boot cover finished before the take-over` to the next run's log greps.

### Q-5 (Low): RC3-4's measurement should include the PIN-to-stamp time against the 30 s hand-over

PLAN.md, the RC3-4 item.

In the fix run's r2 the take-over came 24 s after the PIN (`Unlocked setup took 10795 ms`). The cover's
`COVER_LAUNCHER_WAIT_MS` is 30 s. If the stamp ever comes later than that:
- the hand-over disables the cover and starts Home into the still-busy main process, which is RC3-3's shape again;
- Back stays held until then.

**Fix:**
- Add "the stamp's time after the unlock (`Boot cover taken over`), and its margin to the 30 s" to RC3-4's list of
  things to measure on the release build.
- PLAN's "`Unlocked setup took` 4.5-19 s" cites a 19 s that neither report has (they have 4.5-10.8 s). Cite where it
  comes from or correct it.

### Q-6 (Nit): `applyBackHold`'s KDoc says the opposite of the code

`BootCoverActivity.kt:380`: "An unreadable unlock state counts as locked here (held)".

Counted as locked, it is *not* held, because the hold after the unlock needs `unlocked`. Only the face holds it. The
behaviour is fine, since the face covers the locked case.

**Fix:** "counts as locked here: held only as the face".

### Q-7 (Nit): §8.14 calls the old answer "never-arriving"

`docs/design/59d-android-mode-inventory.md:941`: "the old (never-arriving) answer is dropped".

The old callbacks do arrive once SystemUI answers. The registry notifies every callback it holds
(`notifyDismissSucceeded(4)` in m2), and the old ones are dropped by id.

**Fix:** "the older callbacks, answered with it, are dropped by id".

## Docs

- §8.14 states the rules as built, apart from Q-4 and Q-7.
- `launcher/CLAUDE.md`'s boot-cover paragraph matches the code: SILENT, `coverHoldsBack`, and
  `coverDestroyStartsHome` "only once taken over". Its RC3-2 sentence matches `onStage`. It also catches up step L's
  `JELLY_STAR_CLOCK` = `AOSP_EMULATOR_CLOCK` from 86779771, which the code has.
- PLAN's RC3-4 and RC3-8 entries are accurate, apart from Q-5's 19 s.
- The rc3-run report's "Fixed in" and "Open" notes are accurate. Q-1 changes "every 12 s" there.
