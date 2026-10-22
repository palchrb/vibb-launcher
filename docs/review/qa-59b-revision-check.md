# Revision check of design 59b (docs/design/59b-android-lock-face.md, revised 2026-10-22)

Against `qa-59b-design.md` (Q59b-1..12), design 59 §3.3 / "Step 3 as built", and `launcher/.../lock/` on
`fix-f3-boot-cover`. Not committed.

## Findings, one line each

- Q59b-1 RESOLVED: §2 `lockLeave(.., glanceInFront)` (activity `resumed`, default true); not in front -> no Home, no
  `GlanceOpener`; kiosk on + `startedLockTask` keeps `(true, true)`; in front -> today's rule; `rootLeave` kept.
  `stopLockTaskFirst` is the same in every row, so `stopOwnLockTask()` still agrees. Own mode unchanged (§9 rows).
- Q59b-2 RESOLVED: §2.1 gone; §3 step 2 `voipCall` (ringing and IN_CALL) -> Yield; §1 says the arrow gives no event in
  a call; open question 3 gone.
- Q59b-3 RESOLVED: `androidRefronts`, an input of the step, zeroed at a screen-off and `USER_PRESENT` only, cap 2;
  why `refrontAttempt` can't be the cap is named; test "Refront, Refront, Stop".
- Q59b-4 RESOLVED: the grace always writes `voipFsiTry` (`sent = true` once, or `sent = false` -> CARD, `voipFsiDue`
  false, no later send), from `ringStartedElapsedMs`; fits `voipRingUi`'s `!last.sent -> CARD` (`VoipCallPlan.kt:290`).
- Q59b-5 RESOLVED: "The covered ring" names the 15 s + 2 s path and the 45.8/45.834 timing; candidate rule written
  (§4.4); the dump records flip + 15 s (§7); check 5b is the 40 s ring.
- Q59b-6 RESOLVED: §2/§3 step 4: Android open + mark -> leave without Home, never a re-front; no mark -> today's
  re-front kept as the RELOCK backstop; `presentLeave` re-reads (bound: N3).
- Q59b-7, -8 (scope: N2), -9 (window gone, `audioInCommunication` gates the reopen, check 6b), -10 (`lockScreenFill`,
  night + mark, photo opt-in kept, transient explained), -11 (no name, setter clears on rollback), -12: all RESOLVED.

## Contradictions

- None with design 59 §3.3 / step 3 as built: `faceStep` and its table are untouched, §2 only drops a Home start; own
  mode (never-give-up re-front, `DarkLockUp`, ring lock-up + wake + send) stays gated on face on + Android locked,
  with `RefrontPolicyTest` / `VoipCallPlanTest` rows saying so.
- N1 (Low, wording) Design 59 §3.3 still says LEAVE is "a row's target opens; Home below"; 59b changes that for the
  not-in-front leave without saying it amends it. Fix: one line in 59b's status, and the same in design 59 and
  `launcher/CLAUDE.md` at build.

## Code references
All spot-checked and hold: `PinLockActivity.kt` :393/:399/:1006/:1025/:1048/:451/:76; `LockTaskRoot.kt:74-79`;
`RefrontPolicy.kt:80`; `PinLockRuntime.kt` :1041/:1441/:1465/:1474/:1482/:1492/:1562/:1582/:687-690; `VoipCallPlan.kt`
:140-143/:270/:289/:365-380; `VoipCalls.kt` :66/:401; `PinLockState.kt` :208-224; `Wallpapers.kt:92`;
`WallpaperApplier.kt:170`; manifest :165; `AndroidLock.presentSinceLocked` :65.

## New problems

- N2 (Low) The source test "no `startActivity` in `lock/` bypasses `noteOwnStart`" fails on today's non-"ours" sites:
  `HomeFront.kt:64`, `GlanceOpener.kt:119,135`, `BootCover.kt:155`, `BootCoverActivity.kt:206`; the time-rule screen is
  outside `lock/`. Fix: name the allowlist (glance `show()`, HomeFront, GlanceOpener, BootCover*) and the scope.
- N3 (Low) `presentLeave`'s re-read after `ANDROID_ANSWER_WAIT_MS` has no bound (Android re-locked -> loop). Fix: once.
- N4 (Low) §4's grace has no timer: the glance gets events only at resume/pause and the ring poll is 2 s, so the 3 s
  send (glance in front) and the dark "wake + card" need a `postDelayed` `voipRingStep` from the ringing event,
  cancelled at the ring's end; the dark outcome needs a trigger now that `VoipRinging` no longer wakes at once. Say
  so in §8 step 1.
- N5 (Low) §3 step 5 "`show()` adds `FLAG_ACTIVITY_NO_USER_ACTION`" reads as all shows. Fix: face-mode re-front only,
  with a test row (own mode unchanged).
- N6 (Low, checks) Nothing guards that an in-front unlock keeps Home. Fix: check 3 adds "Lås opp" + a row + PIN lands on
  Home with the target above; check 1 also run once with a kiosk-on `startedLockTask` glance (Home kept).
- N7 (Nit) The QA-fix list's "§2.1 dropped" uses the QA's numbering; say "the QA's §2.1".

## What is left for the implementer brief

1. Build step 0 first (`glanceInFront`, `presentSinceLocked`, a single `presentLeave` retry), then steps 1-4; fold in
   N1-N7 (source-test allowlist, the grace timer, `NO_USER_ACTION` scope, the in-front regression checks).
2. Leave §4.4's re-posted-ring rule unbuilt until the flip + 15 s notice dump confirms it; check 5b only measures it.
3. Emulator-only: checks 1 (both kiosk settings), 2 (exactly two `re-front` lines), 5/5b, 6b, 7.
