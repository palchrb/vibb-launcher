# Code QA - launcher round 6, part A (r6 §1 the parent-PIN sync, design 41 the flashlight on Home)

- Reviewed: branch `r6-a` at `3cad3cab` (64de50a3, 045baf07, ecfc8f5b, 3cad3cab on aa5a4940), 2026-10-20.
- Ran: `./gradlew testDebugUnitTest -PwarningsAsErrors=true` in `launcher/`: BUILD SUCCESSFUL, 150 classes,
  1409 tests, 0 failed, 0 skipped. `scripts/material-symbols.sh` run as its header says (from the repo root, curl
  allowed here): every pinned SHA-256 matched, including `flashlight_on`/`flashlight_off`, and the regenerated
  `torch_glyph_on.xml`/`torch_glyph_off.xml` (and every other generated file) were byte-identical (`git status`
  clean afterwards). 13 one-line mutations against the targeted tests (table below), each reverted.
- Read: `docs/design/r6-launcher-small-items.md` §1 and `docs/design/41-flashlight.md` with their implementation
  status, `docs/design/qa-r6-launcher-design.md` 41-1…41-6 and r6-1…r6-3, the whole diff.

## The design QA's findings

| Finding | Landed? | Evidence |
|---|---|---|
| 41-1 no camera-policy lift | yes | no `setCameraDisabled`/`CameraLock` in the diff; a `CAMERA_DISABLED` refusal under the lock only hides the lock button (`Torch.kt:134-147`, `TorchRules.kt:60`) |
| 41-2 the lift's ordering | moot | no lift |
| 41-3 the call-edge hook | yes | `MusicCalls.evaluate` -> `callEdge` -> `addCallEdgeListener` (`MusicCalls.kt:54-65`, `MusicMediaPlan.kt:145-153`, `Torch.kt:94-96`) |
| 41-4 the lock button's layout | not this part | part B's (the lock's button) |
| 41-5 Home | yes | `GridTile.Flashlight(lit)`, `homeGrid(..., torch)`, both adapter `when`s, `renderGrid()`, `stateDescription` reset on every bind |
| 41-6 the alarm | yes, one gap | `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`; cancelled on every off path *that succeeds* (see M1) |
| r6-1 only the PIN | yes | `SyncTriggers` holds only `pin`; foreign access, battery and boot untouched |
| r6-2 after the verdict | yes | `ParentPin.kt:75` status read before `checkParentPin`, `:89` request after the `synchronized` block; nothing in `checkParentPin` |
| r6-3 `SyncRunner.request` | yes | `ParentPin.kt:89` |

## The rules this round must not break

1. **No policy lift** - holds. `setCameraDisabled` appears nowhere in the diff; `TorchRulesTest` bans it in `Torch.kt`.
2. **The sync request** - holds. `SyncTriggers.pin` (`SyncTriggers.kt:18-23`): `Ok`, `Wrong`, a `LockedOut` with
   `!lockoutAlreadyRunning`; `false` for `TooShort`, `NotConfigured`, `Unavailable`, `ForeignAccessOn`. The three early
   returns in the `synchronized` block return before line 89, so they never request. `lockoutAlreadyRunning` comes
   from `parentPinStatus(store, fingerprint, now)` with the same `now`, under the same lock. An unreadable state is
   saved as a lockout by that read, so `checkParentPin` then loads a locked state and answers `LockedOut` with
   `lockoutAlreadyRunning = true` - no request (equivalent to before). A `Wrong` can't come from a running lockout.
   `checkParentPin` is unchanged, so its count-first provisional `wrong` is never followed by a request inside the
   lock. `SyncRunner.request` is the call. `synchronized` is inline, so the non-local returns release the monitor.
3. **A call's begin turns it off, its end never relights it** - holds in the code. VoIP (`VoipCalls.addListener`;
   `musicCallActive` counts `VoipPhase.RINGING`), `OngoingCalls.addListener` (a ringing cellular call has state
   RINGING, so `hasLiveCall`), and the audio mode listener all reach `MusicCalls.evaluate`, all on the main thread.
   `evaluate` calls the edge listeners before the music's part. `Torch`'s listener maps `ENDED` to `CALL_ENDED` ->
   `TorchStep(false, KEEP)`. `callHoldStep` now goes through `callEdge` and keeps the same three branches.
   `MusicMediaPlanTest` is unchanged and passes. Gaps: the glue isn't pinned (L1), and the hook hangs on `MusicMedia.init`'s try (L3).
4. **The 10-minute alarm** - partly. `Torch.kt:168-176`: `setExactAndAllowWhileIdle`, with the inexact fallback only
   when `canScheduleExactAlarms()` is false. It is re-armed on every `LIT` from the platform callback, and
   `FLAG_UPDATE_CURRENT` with the same request code replaces the earlier alarm. The receiver is `exported="false"`
   (manifest). Every off path that the platform confirms cancels it. A switch-off that fails cancels it too (M1).
5. **Screen-off changes nothing** - holds. The torch registers no screen event, and `TorchRulesTest` bans the tokens in
   `Torch.kt`. That test only scans `Torch.kt`, though (L2).
6. **No flash unit** - holds. `torchCamera` returns null, so `state == null`, `homeGrid` adds no tile and
   `lockButtonShown` is false. `init` catches everything and leaves `state = null`. `toggle` returns early on null.
7. **Home tile** - holds. The click calls `Torch.toggle` and nothing else. Long-press returns false for anything but
   `GridTile.App`. There's no animation: the static `PressFeedback` only, and the state comes back through the callback.
   On the look: indigo (`#6065A9`) is `TONE_INDIGO`, one of the six `ICON_TONES` in `ui/home/IconTones.kt:21-24`, so
   it is a Vibb tone, in the same family as the slate Settings tile. Lit is `kid_accent` peach with a `kid_ground` glyph, not yellow. Accepted.
8. **GPL placement** - holds. New code is in `torch/`, `push/`, `music/`, `server/`, `ui/home/` and `ui/HomeActivity.kt`,
   and none of it in `ui/list/` or `apps/`. The glyphs are Apache-2.0 with "Converted from" lines.
9. **Logs** - holds. The torch logs exception class names, `CameraAccessException.reason` and the locked flag, never the
   camera id or anything about the user. The PIN path adds only `SyncRunner`'s "Sync for [pin_event]", no PIN material.

## Findings

**M1 (Medium) - a switch-off that fails still cancels the alarm, so the torch can stay lit for good, and a refused
off-tap under the lock also hides the button.** `TorchRules.kt:51`: `CALL_BEGAN`/`AUTO_OFF` ->
`TorchStep(switchOff = lit, alarm = CANCEL)`. `Torch.act` (`Torch.kt:125-132`) cancels whether or not
`setTorch(false)` worked, and `setTorch` swallows the failure. The case the implementation-status note leans on is a
torch lit on Home before the PIN lock locks, with the probe finding that the camera policy refuses `setTorchMode`
with `CAMERA_DISABLED`. In that case:
- The 10-minute alarm fires and its off is refused. The torch stays lit, no alarm is pending any more, and the
  refusal hides the lock's button.
- A call beginning does the same.

The torch then burns in a pocket until someone unlocks and taps Home's tile. The note's "the torch then goes off by
the 10-minute alarm" is wrong while the phone stays locked, because that off is refused too. This only bites if the
probe finds the policy blocks the torch (the probe isn't done yet), hence Medium. Fix: cancel the alarm only on the
platform's `DARK`, and re-arm (retry) it when a switch-off fails. Hide the lock button only on a refused *on*-tap,
so a lit torch always keeps its off button. Add a `torchStep` test for "off refused -> re-arm".

**L1 (Low) - the glue's call, alarm and button wiring isn't pinned.** These mutations survived the targeted tests:
- (d) `if (edge == CallEdge.ENDED) toggle(app)` in Torch's edge listener, which relights at a call's end.
- (f) `TorchAlarm.CANCEL -> Unit` in `Torch.act`.
- (g2) `lockButtonShown get() = !refusedOnLock`, the button with no flash unit.
- (i2) `MusicCalls.evaluate` passing `NONE` for a ringing VoIP phase.

The pure rules are well covered, but all four of the rules' promises can be broken in the glue with a green suite.
Fix: add source checks of the kind `TorchRulesTest` already uses. Each needs one line:
- The edge listener's body is only the `act(app, torchStep(...))` line.
- `TorchAlarm.CANCEL -> cancelAutoOff(app)`.
- `get() = torchLockButton(state, refusedOnLock)`.
- `musicCallActive(VoipCalls.phase, OngoingCalls.hasLiveCall, systemCall(ctx))`.

**L2 (Low) - "screen-off changes nothing" is only checked in `Torch.kt`.** Mutation (e) survived: Music's existing
screen-off receiver (`MusicSessions.kt:264`) was made to call `Torch.autoOff`. Fix: have `TorchRulesTest` list the
callers of `Torch.autoOff`/`Torch.toggle` across `src/main` and allow only `TorchOffReceiver`, `HomeGridAdapter` and
the lock's button.

**L3 (Low) - the torch's call-off depends on `MusicMedia.init`'s order.** `MusicSessions.kt:257-259`:
`VolumeCap.init` runs first inside the same `try`. If it throws, `MusicCalls.init` never runs, so there are no call
edges and the torch stays lit through calls (the music hold is lost too, but that was true before). Fix: give
`MusicCalls.init` its own `try`, as `MusicListening`'s comment says for the headset watch.

**Nit 1 - one extra prefs load per check.** `ParentPin.kt:75`: `parentPinStatus` runs `prepareParentState`, then
`checkParentPin` runs it again, which costs one extra prefs load per PIN check. That's harmless. An unreadable state
is now committed and logged by the status read instead of by `checkParentPin`, with the same result.

**Nit 2 - the SyncTriggersTest order check relies on the source's indentation.** It reads `ParentPin.kt` and finds
the `synchronized` block's closing brace by its indentation (`"\n        }\n"`), so a reformat breaks it loudly
rather than silently. It still caught mutation (c).

## The deviations

- **`synchronized(this)` block, request outside the lock** - right. Kotlin's `synchronized` is inline with
  try/finally, so the early returns release the lock. Keeping `SyncRunner`'s monitor out from under `ParentPin`'s keeps
  the class's "takes no other lock" rule.
- **Indigo `kid_torch_tile`** - accepted. It is `TONE_INDIGO`, one of the palette's six tones, and it reads apart from
  the slate Settings tile beside it.
- **Glyphs written by hand, hashes unverified** - resolved. My run of the script verified both pinned hashes against
  the pinned commit and regenerated byte-identical drawables.
- **A refused off-tap under the lock hides the button** - wrong as built (M1). A lit torch should keep its off button,
  and the alarm doesn't rescue it as the note claims.

## Mutations

Targeted tests: `--tests '*SyncTriggers*' --tests '*Torch*' --tests '*HomeModel*' --tests '*MusicMediaPlan*' --tests '*ParentPin*'`.

| # | Mutation | Result |
|---|---|---|
| a | `SyncTriggers.pin`: `LockedOut -> true` (a running lockout syncs) | caught by `SyncTriggersTest` (rule) |
| b | `TooShort` moved to the `true` branch | caught by `SyncTriggersTest` (rule) |
| c | `ParentPin.check`: request before `checkParentPin`, inside the lock, and the after-line removed | caught by `SyncTriggersTest` (by source) |
| d | Torch's edge listener: `if (edge == CallEdge.ENDED) toggle(app)` (relight at a call's end) | **NOT caught** (L1) |
| e | Music's screen-off receiver also calls `Torch.autoOff` | **NOT caught** (L2) |
| e2 | `Torch.init` registers an `ACTION_SCREEN_OFF` receiver that turns it off | caught by `TorchRulesTest` (screen) |
| f | `Torch.act`: `TorchAlarm.CANCEL -> Unit` (alarm not cancelled on off) | **NOT caught** (L1) |
| f2 | `torchStep`: `DARK -> KEEP` | caught by `TorchRulesTest` (alarm) |
| g | `torchLockButton = !refusedOnLock` (button with no flash unit) | caught by `TorchRulesTest` (no flash) |
| g2 | `Torch.lockButtonShown get() = !refusedOnLock` | **NOT caught** (L1) |
| h | Home tile placed after Settings | caught by `HomeModelTest` |
| i | `musicCallActive`: `voip == IN_CALL` (a ringing VoIP call is no call) | caught by `MusicMediaPlanTest` and `TorchRulesTest` |
| i2 | `MusicCalls.evaluate` maps a ringing VoIP phase to `NONE` | **NOT caught** (L1) |

## Verdict

**Approved with fixes.** §1 is correct and well pinned. The flashlight keeps every hard rule: no policy lift, the sync
only after a final result, a call turns it off and never relights it, screen-off changes nothing, and no flash unit
means no tile. Fix M1 before the lock's button (part B) ships: a lit torch must keep a way off under the lock, and a
failed switch-off must not drop the auto-off alarm. L1-L3 are cheap test or robustness additions for the same follow-up.
