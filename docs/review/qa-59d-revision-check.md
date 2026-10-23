# Revision check: design 59d §8 against its design QA (Q59d-1..14)

Checked: `docs/design/59d-android-mode-inventory.md` §8 (at `553a29c9`) against `docs/review/qa-59d-design.md`, with §2, §3, §5 and §7
read for contradictions. Read-only on code.

## Per finding

- Q59d-1: taken (§8.2 item 1: gate, new inputs, `screenOnUnshown` rule, three tests).
- Q59d-2: taken (§8.1 Q2, §8.2 item 5: no 15 s end, cost named, `LockWakeTest`, both doc edits).
- Q59d-3: taken with a change: `ANDROID_ASK_OPEN_MS` gets a value (2 min) the QA left open; capture-first is §8.4 item 1.
- Q59d-4: taken (§8.2 item 3: rule, call source, tests, doc amendment).
- Q59d-5: taken ("fixes R1" replaced by target frames and a recording against check 9; children, not the decor).
- Q59d-6: taken (`ASK` stage, transitions, tests to rewrite).
- Q59d-7: taken (`noAnimation` parameter, two callers pinned, the close fade goes).
- Q59d-8: taken (§8.3 gate, reset in receiver and `onResume`, all three pins and docs).
- Q59d-9: taken (no auto ask in a call, retry once counted, screen-off cancel uncounted, cap per process).
- Q59d-10: taken (§8.4 item 6 (a)-(c), with (a)'s fallback stated).
- Q59d-11: taken (C then N1, log guard, `wm_new_intent` in §8.4 item 2).
- Q59d-12: taken (`kg` kept, cost named, `LockUpPlanTest` row).
- Q59d-13: taken (R2's decision listed under "Out of round 1", with its reason).
- Q59d-14: taken (round 1 order C, N1, H, B, E, G; F round 2 in parallel, same overlap named).

## Contradictions

§8 says "Everything above stays the specification", but text above still says what §8 overrides:
1. §2.1 row "ScreenOn backstop start" (REMOVE in face mode), §3 "Screen-on: Nothing is started" and §3 step C ("No ScreenOn
   backstop start") contradict §8.2 item 1 (the gated backstop stays; Q59d-1's Blocker).
2. §3 "Notice wake" ("lights for 15 s", "end without `lockNow`"), §3 step E ("or keeps it. The user's choice") and §2.4's 15 s
   row contradict §8.1 Q2 (no end; the phone's timeout).
3. §3 step H ("if 'back to the last app' ... never starts Home") and §2.2 ("unless the user picks Q1") are the option the user
   did not pick (§8.1 Q1: Home, except in a live call).
4. §2.2 row F2 ("at most `ANDROID_ASK_TIMEOUT_MS`") and §3 step G ("the feature write") contradict §8.2 item 6 (`ANDROID_ASK_OPEN_MS`,
   every DPM write).
5. §3 step B ("Fixes R1", "No KEYPAD stage", Home `NO_ANIMATION`) contradicts §8.2 item 4 (ASK stage; R1 already fixed by
   59b; a new `noAnimation`, not `animate = false`).
6. §5 rules 1-3 are unconditional ("while the user is locked", cap "per boot"); §8.3 adds the marker gate and a per-process cap
   but says "§5 as written, plus". §5 itself should be amended.
7. §8.1 Q1 says "no Home start" in a live call; §8.2 item 3 keeps Home as the kiosk root (root + call -> `(true, true)`). The
   second is right (Q59d-4); the first needs "except as the kiosk root".
8. §8.4's header ("one run after both rounds merge") contradicts "G's capture before G is built": G is round 1's last step, so a
   run is needed mid-round, before merge.

Order in §8.2 has no conflict with a QA dependency (H before B, C before N1, G after its capture).

## Device checks §8.4 lacks

- C: Q59d-1's own scenario - force-stop or self-update the launcher while dark, then wake -> the glance starts, not SystemUI's
  face (and a lock-up whose start threw). Item 2 has only the ordinary wake.
- B: the ASK stage's cancel, error and STAY paths (target dropped, no keypad chrome), and `OWN_KEYPAD` (no credential).
- G: a re-run of RC F2 after G is built. Only the pre-build capture is listed.
- H: kiosk on and off are different rows; item 3 names neither.
- F: item 6 gates work that §8.3 builds first; if (a) fails, say whether the merged code and flipped pins stay inert or go.

## What an implementer would still guess

- G: why 2 min for `ANDROID_ASK_OPEN_MS`; what to do if the capture's cancelling write isn't a chrome-pass DPM write (e.g. a
  WM focus change); whether a held `setLockTaskPackages` blocks a window opening a package during the ask.
- B: the trigger and timing for "the content shows again" after a refused finish; `glanceStageStep`'s `face` parameter on
  every caller; what STAY does to a half-sent `GlanceOpener` target.
- C: where `LockEvent.ScreenOn`'s `face` and `lockOnTop` come from for a new process (default false?).
- N1: which "700 ms check", and what `sosMarkAtPause` reads.
- F: whether the auto ask resumes when a call ends under the cover; `telecomInCall()` is read only once unlocked today
  (`BootCoverActivity.kt:195`), so the BFU read is asserted, not shown; whether the 1 s error retry is a second ask in one
  resume and counts 1 or 2 of the 3.
- Docs: both rounds edit `launcher/CLAUDE.md`; only "the second to merge rebases" is said.
