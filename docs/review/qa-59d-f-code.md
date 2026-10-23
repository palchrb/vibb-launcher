# QA 59d step F - code (`d59d-round2-f`, `a21c4700..3f2719be`)

Code QA of design 59d step F (the boot cover as the BFU face that asks for Android's PIN pad), against
`docs/design/59d-android-mode-inventory.md` §5, §8.3, §8.4 item 6, §8.5 "F" and §8.6 ("Step F as built"; §8 rules
over §5), the design QA's Q59d-8/9/10 (`qa-59d-design.md`), the revision check, design 59's "F3" section, the two F3
code QAs and `launcher/CLAUDE.md`'s 16b/F3 bullet. Reviewed 2026-10-23 on `qa-59d-f` (= `3f2719be`). Launcher only,
no emulator (as §8.3 says for round 2). AOSP behaviour is cited from memory of android14/15 (`KeyguardController`,
`PhoneWindowManager.dismissKeyguardLw`, SystemUI's `KeyguardViewMediator`/`DismissCallbackRegistry`,
`ActivityClientController.onBackPressed`); [device] = only the phone can tell.

**Run** (from `launcher/`, `flock ~/build.lock ./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest`):
exit 0, BUILD SUCCESSFUL, **1586 tests, 0 failures, 0 errors, 0 skipped** (`BootCoverPlanTest` 23, `BootCoverManifestTest`
8, `KeyguardCoverPlanTest` 25). No Kotlin warning in the build log (only kapt's known `RELEASE_11` line), so CI's
`-PwarningsAsErrors=true` should pass too.

**Verdict: merge after fixes.** The bar holds: the cover never dismisses the keyguard itself, is showWhenLocked only
while the gate holds (and drops it within one main-thread pass of any event after `isUserUnlocked`), shows only the
mark and the hint line, and an own-mode phone never calls `setShowWhenLocked` or `requestDismissKeyguard`. F3's
take-over is untouched. Two Shoulds are about the face itself, not the gate: Back on the BFU face isn't consumed
(F59d-1), and several reachable states leave the face with no ask and no hint line (F59d-2). Both are small fixes.
Findings: 0 Blocker, 2 Should, 5 Nit. Mutations: 16 run, 13 killed, 3 survived (F59d-4).

## The bar, traced

| Claim | Verdict |
|---|---|
| Every path to `setShowWhenLocked(true)` | one call site, `applyLockedFace` (`BootCoverActivity.kt:291-297`), which sets the flag to `padGate()` read right there. `padGate` (`:280-287`) = `waitForLauncher` (create's marker read) **and** a fresh `BootCoverGuard.androidMode` **and** `isUserUnlocked == false` (`!= false`: a null service counts as unlocked; any exception = false). Callers: `onCreate` (after `waitForLauncher` is read, `:122-123`), every `onResume` (`:159`), the `ACTION_USER_UNLOCKED` receiver (`:138`), every `padStep` (`:302`), and F3's poll once unlocked (`:240`). Each reads the gate live, so a true is only ever written while the user is locked. Holds |
| Every path to `requestDismissKeyguard` | one call site, `askPad` (`:321-338`), which re-reads `padGate()` first and is reached only from `padStep`'s `PadAction.ASK`; `bootPadStep` returns ASK only with `gate` true (`BootCoverPlan.kt:387`). It only asks: AOSP's `PhoneWindowManager.dismissKeyguardLw` hands it to SystemUI's `dismiss`, which for a secure (BFU, strong-auth) keyguard shows the bouncer; a not-showing keyguard answers `onDismissError`. No `FLAG_DISMISS_KEYGUARD`, no `dismissKeyguard` attribute, no `turnScreenOn` (pinned). Holds |
| Never showWhenLocked once `isUserUnlocked` | `isUserUnlocked` never goes back to false within a boot, so every `applyLockedFace` after the unlock writes false. The flag drops at the first of: the receiver, the 250 ms poll (only while resumed), any resume or any pad event. A cover that is stopped and cached at the unlock (its receiver deferred) keeps a stale true until its next resume; its `onResume` writes false first thing, so at most one occluding frame of the bare mark over an AFU keyguard - nothing a kid can act on (a tap reads the gate false). Holds |
| Shows nothing but the mark and the hint line | the content is the `ImageView` and one `TextView` (`:126-135`); the root's tap only runs `padStep(TAPPED)`. Nothing opens, no intent, no service. The shade and Quick Settings over the occluded BFU keyguard are §8.4 item 6 (b) [device] |
| Own mode (no android marker) unchanged | `waitForLauncher` false -> `padGate` false at every call -> `applyLockedFace` never calls `setShowWhenLocked` (`show == showsWhenLocked == false`), `bootPadStep` returns NONE for every event, the line stays `GONE`. The only difference: the root is now clickable (a dead tap; TalkBack, F59d-6). Holds |
| F3's take-over | `coverWaitStep` and its callers are unchanged except the one `if (unlocked) applyLockedFace()` line in `waitStep`. `coverWaitStep` is STAY while locked, so nothing of F3 runs before the PIN. Holds |
| Crash guard | every new call is caught or can't throw: `padGate` catches, `telecomInCall` catches, `askPad` catches around `requestDismissKeyguard` and answers ERROR, `getFont` is `runCatching`, `kid_ink_dim` and `nunito_semibold` exist (APK resources, readable BFU). Re-entry (`askPad`'s synchronous ERROR inside `padStep`) is safe: `pad` is assigned before the action runs |
| `telecomInCall()` BFU | `TelecomManager.isInManagedCall` needs `READ_PHONE_STATE` (or the dialer role): a runtime grant held by system_server for the uid, not CE data; the `:bootcover` process is the same uid. The fallback `AudioManager.getMode` needs nothing. Neither reads CE storage. A ringing call is a managed call, so no ask while it rings either. [device] §8.4 item 6's "reboot during a call" |
| The in-memory cap | `autoAsksThisProcess` (companion, `:406`) seeds every new instance (`:88`) and is written after every step (`:307`), so a second cover instance in the same `:bootcover` process (the Home key, F59d-1's Back) carries the count; a new process starts at 0, as §8.3 says. A crash loop is the guard's (2 per boot); a non-crash death (LMK) of the foreground HOME is the only unbounded case and has no loop driver. Holds |

## Lifecycle

- **The ask id**: every new ask (`RESUMED`, `TAPPED`, `RETRY`) takes `++askId`; `padAnswer` drops an answer of another id
  or after `onDestroy` (`:341`). STOPPED closes an open ask without bumping the id, so a late answer of that ask lands
  either before the next ask (then `bootPadStep` drops it: `!pad.open`) or after it (dropped by the id). A second
  `requestDismissKeyguard` while one is out is harmless: SystemUI's `DismissCallbackRegistry` keeps both and notifies
  both; the older is dropped by its id. Holds.
- **The stop rule vs §5 rule 4** (power with the pad up): the pad goes at the sleep; the cancel and the stop come in
  either order, both with `isInteractive` false (PowerManager's wakefulness changes before WM and SystemUI hear of the
  sleep), and either way the automatic ask's count comes back and the stop re-arms, so the screen-on's resume asks
  once. Tested both orders (`a cancel with the screen off isn't counted`). Holds; [device] that `isInteractive` already
  reads false when the cancel callback runs.
- **The receiver and the poll** both call `applyLockedFace`, on the main thread, each reading the gate live: no
  interleaving can write a stale true after a false (both reads see the same monotonic `isUserUnlocked`). No race
  leaves the flag true after the unlock beyond the stopped-and-cached case above.
- **`onPause`'s `removeCallbacksAndMessages(null)`** drops the posted RESUMED and a pending retry; `onResume` posts the
  retry again while `retryDue` (`:161`), and STOPPED clears `retryDue`. Holds (the re-post isn't pinned: M14).

## The implementer's four choices beyond the spec

1. **A tap asks with an ask still marked open** - sound. A tap can reach the cover only when nothing of SystemUI is
   over it, so an "open" ask there is a lost callback; refusing the tap would leave the face dead until a stop. The
   earlier callback, if it ever fires, is dropped by its id. Not pinned by a test (M7 survived): F59d-4.
2. **A stop closes an open ask** - sound, and needed: the power press's stop may come before the cancel, and the
   emergency dialer from the pad's Emergency button stops the cover with the ask possibly unanswered; without the
   close, the next resume would never ask (`!pad.open`). Its one gap: the closed ask leaves no line (F59d-2 (b)).
3. **The line after a failed retry** - sound: the ask ended without the PIN, the kid needs the hint.
4. **The resume's ask as a plain post** - sound. `KeyguardController.dismissKeyguard` needs only
   `visibleIgnoringKeyguard`, which holds from the resume; a keyguard not showing yet answers `onDismissError`, which
   the 1 s retry covers. "After the first frame" would add a frame callback for no gain.

## Findings

**F59d-1 (Should): Back on the BFU face isn't consumed - it finishes the cover, and SystemUI's face can show before a
new cover comes.**
- `BootCoverActivity` registers no back callback (the app has `enableOnBackInvokedCallback="true"`, targetSdk 36). With
  no callback of the app's, Back runs the activity's default (`Activity.onBackInvoked` ->
  `ActivityClient.onBackPressed`), and `ActivityClientController.onBackPressed` moves a task root to the back only when
  it is a `MAIN`/`LAUNCHER` activity - the cover is `MAIN`/`HOME` - so the cover is finished.
- Before step F the cover was behind the keyguard BFU, so Back never reached it there. Now it is the face, and Back
  right after a cancel is what a kid does (the pad's Back cancels, the next Back lands on the cover).
- What follows: the keyguard is un-occluded (SystemUI's clock face, which §5's aim says the kid never sees), the
  system resumes HOME, which BFU resolves to the cover again - a new instance, armed, `line = false`. Each Back costs one
  automatic ask; after the third, the new instance shows the bare mark (F59d-2 (c)).
- Fix: while `padGate()` holds, consume Back (an `OnBackInvokedCallback` registered in `applyLockedFace` when the flag
  goes true and unregistered when it goes false, so F3's post-unlock Back behaves as today; or `onBackPressed`
  overridden to do nothing while gated). Pin it in `BootCoverManifestTest` next to `applyLockedFace`'s body.
- Device check (add to §8.4 item 6): Back twice after a cancel - no SystemUI face, no `wm_destroy_activity` of the
  cover, the line stays.

**F59d-2 (Should): reachable states leave the face with no ask and no hint line.**
The line shows only after a CANCELLED or a failed retry (`BootCoverPlan.kt:404`, `:408`, `:413`), and every automatic
ask needs a resume after a stop. These states get neither:
- (a) A RESUMED held back by a call (`:393`, `inCall`): the Home key during a BFU call brings the cover over the call
  screen (Q59d-9's case). The arm is kept, but if the call then ends with the cover still in front there is no new
  resume, so no ask and no line until the kid happens to tap or turns the screen off and on.
- (b) A stop that closes an open ask (`:402`) with the screen on - §8.6 (a2)'s case if the BFU pad stops the cover, or
  the pad's Emergency button: the late cancel is then no current ask's answer (dropped by `!pad.open`), so the line
  never comes. If (a2) fails on the device, after the third Back the kid sees the bare mark: the implementer's (a2)
  says "bounded, not a loop", which is true, but not that the face then has no hint.
- (c) A new cover instance after the cap (F59d-1's Back, or any re-start of the HOME): `line = false`, RESUMED refused
  by the cap.
- The pad's Emergency button is behind that unhinted tap too (the power menu's Emergency and SOS remain).
- Fix (pure, in `bootPadStep`): a RESUMED under the gate that doesn't ask for any reason but an open ask (a call, the
  cap, the screen off) sets `line = true`; a STOPPED that closes an open ask sets `line = true`. The first face before
  the first ask stays bare, as the implementer wanted (a held-back resume is no "before any ask" flicker: no pad is
  coming). Tests: one per case; the existing `assertEquals(BootPad(), inCall)` in the call test changes to
  `BootPad(line = true)`.

**F59d-3 (Nit): the line is hidden only by a `padStep`, not by the unlock.**
`tapLine` visibility is set only in `padStep` (`BootCoverActivity.kt:308`), and SUCCEEDED keeps `line`
(`BootCoverPlan.kt:403`). After cancel -> tap -> PIN, the line is gone only if `isUserUnlocked` already reads true when
`onDismissSucceeded` runs. As I read AOSP it does (LockSettingsService waits for the unlock's progress listener before
`checkCredential` returns), but nothing in our code relies on that elsewhere; if it lags, F3's wait shows "Trykk for å
låse opp" over the revealed cover until Home comes, and a tap does nothing. Fix: `applyLockedFace` sets the line
`GONE` whenever `show` is false, and SUCCEEDED clears `line`.

**F59d-4 (Nit): the tap-over-an-open-ask choice isn't pinned.** Mutation 7 (`TAPPED -> if (pad.open) none else ...`)
survives: no test taps with `open = true`. Add `bootPadStep(BootPad(open = true, auto = true, autoAsks = 1), TAPPED,
...)` -> ASK, `auto = false`, count unchanged. Also unpinned, both harmless (M12, M14): the line's `gate &&` in `padStep`
and the retry's re-post in `onResume`; a source line each in `BootCoverManifestTest` would do.

**F59d-5 (Nit): three KDocs still say the cover is never showWhenLocked.**
- `BootCoverActivity.kt:46-47`: "behind Android's keyguard before the PIN (never showWhenLocked)" - the class KDoc's new
  paragraph (`:54-58`) amends it two paragraphs down, but the old sentence stands.
- `BootCoverPlan.kt:167`: "the cover (behind Android's keyguard, never showWhenLocked) is what the PIN's dismissal
  reveals".
- `BootCover.kt:142-144`: "The cover is never showWhenLocked: Android's lock screen (the restart line, the pad) stays
  the face before the PIN."
- Fix: "in android mode the BFU face over the keyguard while the pad gate holds (59d step F), else behind it". Also
  `launcher/CLAUDE.md`'s bullet says the line shows "after a cancel"; the code also shows it after a failed retry
  (§8.6 says so) - add "or a failed retry".

**F59d-6 (Nit): the root is clickable in every mode, and the line has no side padding.**
- `setOnClickListener` on the root (`BootCoverActivity.kt:133`) makes it a clickable, unlabelled node for TalkBack in
  own mode and after the unlock too (a dead "double-tap to activate"), and BFU it is unlabelled as well. Fix: give the
  root `contentDescription` = the line's text while the gate holds and `importantForAccessibility = NO` otherwise (or
  set the listener only while gated). [device] TalkBack on the BFU face.
- The line is `WRAP_CONTENT` with no horizontal padding: at font scale 1.3-2.0 on the Jelly Star's 320 dp "Trykk for å
  låse opp" (18 sp) wraps edge to edge, and two lines starting 168 dp below the centre (~452 dp of 569) reach ~550 dp
  at 2.0 - under a 48 dp three-button navigation bar (the window is edge-to-edge at targetSdk 36). Fix: 24 dp side
  margins (`FrameLayout.LayoutParams` `leftMargin`/`rightMargin`) and the bottom inset as a bottom margin, or anchor
  the line to the bottom above the inset. [device] font scale 2.0 on `handy36` at `wm density 240`.

**F59d-7 (Nit): device checks the code assumes but §8.4 item 6 / §8.6 don't list.**
- Back on the face after a cancel (F59d-1).
- The Home key during a BFU call: the cover over the call screen, no ask while the call lives, and after the call
  ends with the cover in front: the line (with F59d-2's fix) and an ask at the next tap or screen-on.
- The first resume's ask answered `error` (keyguard not ready): a `retry` ask 1 s later (`Boot cover asks ... (retry,
  ...)`), not a second error.
- (a): an ask line while `dumpsys` shows the keyguard **not** occluded is a failed (a) too - SystemUI's bouncer over
  SystemUI's face (benign, Android's PIN), and not "gate (a) passed". (§8.6 (a) reads "no resume and no ask line" as
  the failure; a cover resumed behind the keyguard would ask.)
- (b): with the shade, the Home and Overview gestures and the power menu over the occluded BFU keyguard (Overview must
  stay unavailable, as on SystemUI's own BFU face).
- (a2) as written, plus: whether `onDismissCancelled` comes at all for the BFU pad's Back (`Boot cover: Android's PIN
  pad cancelled`); without it only F59d-2's fix leaves a hint.

## Docs

- Design 59's F3 section ("Over the keyguard only as the BFU face, gated at runtime"), its run check 3's note and
  `launcher/CLAUDE.md`'s 16b/F3 bullet say what the code does: the gate, the runtime flag's one call, the one ask, the
  rule's parts, the drop at the unlock, the inert fallback. Only the "after a cancel" detail is short (F59d-5).
- §8.6's "as built" list matches the diff: the files, the functions, the constants, the strings (nb/en), the six new
  tests, the flipped pins, every rule as coded, the four choices. Its device list is right as far as it goes; F59d-7
  adds what the code also assumes.

## Mutations

Each mutation applied alone, `testDebugUnitTest` run on `BootCoverPlanTest`, `BootCoverManifestTest` and
`KeyguardCoverPlanTest`, then reverted (`git status` clean at the end).

| # | Mutation | Result |
|---|---|---|
| M1 | `bootPadStep`: a tap is counted (`autoAsks + 1` on TAPPED) | killed: `BootCoverPlanTest` "no automatic ask in a call ... a tap still asks" |
| M2 | `bootPadStep`: RESUMED asks during a call (`!inCall` dropped) | killed: same test |
| M3 | `bootPadStep`: every error retries (`!pad.retried` -> `pad.open`) | killed: "an error is asked again once a second later, the pair counting once" |
| M4 | `bootPadStep`: RESUMED asks again without a stop (`pad.armed` dropped) | killed: "one automatic ask per resume after a stop" |
| M5 | `bootPadStep`: a screen-off cancel is counted (`closedCount` never gives back) | killed: "a cancel with the screen off isn't counted" |
| M6 | `bootPadGate`: true once unlocked (`androidMarker` alone) | killed: "the gate is the android marker with the user still locked" |
| M7 | `bootPadStep`: a tap is ignored while an ask is open | **survived** (F59d-4) |
| M8 | `setShowWhenLocked` moved out of `applyLockedFace` into `onCreate` (`setShowWhenLocked(waitForLauncher)`; count still 1) | killed: `BootCoverManifestTest` "the cover shows over the keyguard only gated at runtime ..." |
| M9 | a second `requestDismissKeyguard` in `onResume` | killed: `KeyguardCoverPlanTest` "requestDismissKeyguard only in the lock" and `BootCoverManifestTest` |
| M10 | `askPad` without its `padGate()` re-read | killed: `BootCoverManifestTest` |
| M11 | `padAnswer` without the ask-id check | killed: `BootCoverManifestTest` |
| M12 | the line shown without the gate (`if (pad.line)` in `padStep`) | **survived** (harmless: `bootPadStep` without the gate keeps `line`, but no ask happens; F59d-4) |
| M13 | the unlock receiver no longer calls `applyLockedFace` | killed: `BootCoverManifestTest` |
| M14 | `onResume` no longer re-posts a due retry | **survived** (a dropped retry then waits for a tap; F59d-4) |
| M15 | STOPPED leaves the ask open | killed: "a cancel with the screen off isn't counted" |
| M16 | SUCCEEDED sets the line | killed: same test (its success assert) |

13 killed, 3 survived. The brief's required mutations (count a tap, ask during a call, retry twice, ask again without
a stop, `setShowWhenLocked` out of the gated function, a second `requestDismissKeyguard`) are all killed. Working tree
clean afterwards (`git status`: only this file).
