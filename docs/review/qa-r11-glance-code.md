# Code QA: r11-glance (the lock's glance page scrolls whole, the compact player from two rows, rows swipe away)

Branch `r11-glance` (28326fb6) against main `1074dc59`. Reviewer's branch `qa-r11-glance` = `r11-glance` + this file.
Design: `docs/design/r11-launcher-glance-scroll.md` (§0 F1-F8, §1-§5, §8, "As built"). Also read: r9 (§2, §7),
design 25 §3/§9, 59d §8.13/§8.15, and `qa-edge-swipe-fix-code.md` for the format.

## Setup

- I read `git diff 1074dc59..r11-glance` in full: `GlancePanel.kt`, `LockGlance.kt`, `view_lock_glance.xml`,
  `GlanceReader.kt`, `GlanceStore.kt`, `MissedCallNotice.kt`, `MissedCallNotifier.kt`, `MissedCallsRepo.kt`, the
  strings and the id, the tests, the PWA line and its test, `launcher/CLAUDE.md`, PLAN, and the design notes. I also
  read the neighbours: `PinLockActivity.dispatchTouchEvent`/`onCreate`, `GlanceStore`'s marks and rows,
  `GlanceReader.connected`/`disconnected`, `noticeStep`/`missedCallNotice`, and the notifier's `current` setter and
  `dismiss`.
- Launcher suite (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug
  testDebugUnitTest`): **BUILD SUCCESSFUL, 1654 tests, 0 failures, 0 errors, 0 skipped.** `LockGlanceTest` 33,
  `LockGlanceLayoutTest` 12, `LockMediaGuardTest` 4, `LockGlanceGuardTest` 28, `MissedCallNoticeTest` 26,
  `StatusBarHealTest` 13. No new warning: only kapt's `RELEASE_11` line.
- Server (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-r11-glance CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0`, under
  the lock): `cargo test` **`test result: ok. 1210 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out`**.
  `cargo fmt --check` is clean. `cargo clippy --all-targets` exits 0 with the 13 known warnings (`kid_phone_server`
  bin "generated 13 warnings"), none in this branch's lines.
- Mutations: an exact-replace script applied one mutation at a time, ran the named test classes and restored the
  file. A baseline run with no change passed first. The worktree was clean (`git status` empty) before this file.
- No emulator, as briefed.

## What was checked and holds

- **The axis.** `glanceTouchAxis` uses the `ScrollView`'s own slop, in dp, from the same DOWN. On the event where the
  axis becomes HORIZONTAL, the panel returns `CANCEL_VIEWS` before the views see that MOVE. So the `ScrollView` can
  never have intercepted it: it needs a MOVE with |dy| > slop, and every earlier MOVE was within the slop on both
  axes. A tie is vertical. A lift within the slop is the tap.
- **The traces from the brief:**
  - A sideways drag that starts on the music tile, the alarm line, the "more" line or a gap in the scroller can't
    dismiss anything (`rowAt` hits only bound row views) and doesn't unlock (`swipeBlocked = true`). The views get
    the events as before.
  - On the bottom block or the handle (outside the scroller), `dismissRow` is null and today's `glanceSwipeStep`
    rule is unchanged. A control keeps its touch. The handle's swipe up still asks.
  - A vertical drag on a row scrolls when the page can scroll (`scrollTakes` at the DOWN), and unlocks past a third
    of the page when it can't.
  - A tap after a cancelled drag is clean: the drag sent the views their cancel, and the next DOWN resets everything
    and passes.
  - Screen-off mid-drag: the system's `ACTION_CANCEL` springs the row back. `stop()`'s `settleDismiss` then springs
    any drag back at once and commits only a dismissal already released.
  - A post or removal mid-drag: `renderNow` sees `dismissBusy()` and only sets `renderPending`. The tiles, the torch,
    the alarm and the stage are still bound; the rows and the layout wait. The held render runs at the commit, at
    the spring-back's end, or at the next DOWN.
  - Listener unbound: `release` checks `canDismiss` and springs back, logging the constant line. A disconnect between
    the lift and the commit makes `GlanceReader.dismiss` return false, and the rebind brings the row back. Nothing is
    lost: the notification stays in the shade.
  - Covered (the VoIP card) at the DOWN: PASS, nothing recorded. Covered mid-drag: the next event springs the row
    back. The ASK stage: no `dismissRow`, and Android's pad window takes the touches anyway.
- **No stale index can dismiss another conversation.** `Dragged` captures the row object at the axis decision, from
  the `rows` the views were bound from. From that event until the commit nothing rebinds the rows (`dismissBusy`
  covers the drag and the animation). The commit uses `d.row`. The DOWN's index is the only early piece. A render
  between the DOWN and the slop rebinds view and row together, so the row that moves is the row that is dismissed.
- **A held render is never lost** on the normal paths. I traced BACK (`swipeReset` before `springBack`, so the end's
  flush runs), the second finger (the flush waits for the UP's `drag == null` branch), the covered branch, DISMISS
  (the commit renders), the missing listener (spring-back after `swipeReset`), `dropDrag` at a DOWN, and `swipe()`'s
  catch. The one hole is an exception between `dismissing = d` and the animator's start (Q-7).
- **Animators.** Every update and end listener is inside `guarded`. `settleDismiss` removes the end listeners before
  `cancel()`, so no collapse or second commit can follow. `collapse` returns when `dismissing !== d`. `bindRows`
  cancels a running spring-back without its listener and `rest()`s every view. The height and the gap go to 0
  through `layoutParams`, and `rest()` writes `WRAP_CONTENT` and the 8 dp gap back only when they differ. View i
  then shows row i+1 at the pixels the collapse left.
- **"Weakens nothing" holds:**
  - No new path out of the lock: the dismiss code calls no `step`, `tap`, opener, `send` or `startActivity`.
  - The panel still has no `cancelNotification` (the substring ban covers `cancelNotifications` too), no prefs, and
    no `Intent(`.
  - `requestDismissKeyguard` stays pinned to its two files by `KeyguardCoverPlanTest`.
  - The only cancel is `GlanceReader.dismiss`'s, of `GlanceStore.keysOf`'s keys. Those are kept candidates only: the
    ticked apps' clearable, non-ongoing, non-summary, non-call/alarm notifications, never ours.
  - The delete intents NMS sends for `REASON_LISTENER_CANCEL` run as the app. While LOCKED, lock task blocks any
    activity start of an unpinned package, so a delete intent can't open anything over the lock (F3: the same as
    Android's own shade).
- **`keysOf` / `hide`.** `keysOf` filters by package and conversation key, so it never touches another room or another
  app with the same shortcut id. `postTimeMs <= upTo` leaves a post that came after the row out of the cancel.
  `hide` is the opened mark, `max`-merged and memory only. A newer post (every post gets a fresh `postTime` in NMS,
  updates included) is newer than the mark, so the row comes back: the mark can't swallow new messages. No keys left
  still hides and returns true, as specified.
- **Missed calls.**
  - `dismissedMarksAfter` keeps the later mark and prunes everything older than 14 days at each write, the call log's
    window, so nothing it could still hide is dropped.
  - `noticeMarks` takes the later of seen and dismissed per number.
  - `missedCallSummaries` still gets `seenMarks` alone (pinned), so the phone book keeps "Pappa 1".
  - Several contacts: the marks map and `withoutDismissed`'s set both hold them.
  - The dismissal pass reads `CallPolicyStore.state`, not the effective state. So a no-calls time rule's
    emergency-only view can't empty the notice and mark everything read.
- **The "last contact's Clear marks the unread range read" line is right, not a side effect.** With `ownDismiss` the
  view is the rules' full view, so an empty notice means every contact's row was dismissed (or seen). The Clear is
  then what the shade's own swipe of ours does, and `mayMarkRead` still needs `rules.callsEnabled`. Marking read
  doesn't touch the badges, which count by the seen marks and later calls, not by `new`. Two nits: the KDoc says "the
  shown range" where the Clear marks up to the pass's `unread.newestId`, and see Q-6 on the invariant's wording.
- **§1 the scroller.**
  - `glanceLayout` is pure. I checked every row of the note's table by hand against the model (278.8, 348.8, 328,
    392, 456 / 55 / 31, 712 / 311 / 287, 438 / 37 / 13). The user's case is 113.7 + 12.7 + 20.2 + 90 + 14 + 120 =
    370.6 dp, inside both 401 and 425 dp.
  - `boundRows`, `bindRows`' `take(8)` and `more = n - 8` agree.
  - `placeProfile`'s `contentStart`/`contentTop` add `lockGlanceTop.paddingStart`/`paddingTop` (16/0) to the root's
    (now 0 + insets). That is the same window position as before the padding moved. The scroller is the root's first
    child at its `paddingTop`, so the keyguard-bar baseline path is intact. The margins come from the root's location
    and don't depend on the scroll.
  - `pageWidth()` gives 288 dp at 320.
  - `scrollTo(0, 0)` stays in the lock-up branch.
  - Every parent between a row and the scroller has `clipChildren` false (`top`, `list`, `rows`, plus `top`'s
    `clipToPadding`). A row slid ±scroller width therefore leaves at the screen's edge, and the `ScrollView` clips
    vertically.
  - TalkBack: the root has no node, the scroller is one scrollable node, and the order is clock, date, alarm, tile,
    timer, rows, "more", then the bottom block.
  - Font scale 2.0 and 320x569 overflow into the scroll, never a squeeze (no fit code is left; `LockGlanceTest` bans
    the old names).
- **§2.** `GLANCE_COMPACT_FROM_ROWS` = 2 on the bound rows. `shownTile` and `tileChanged` use the same rule.
  `placeList` writes 22/14 dp only when the margin differs.
- **The PWA's "it can't be screenshotted" is true as written** (the brief's item 6). `PinLockActivity.onCreate` adds
  `FLAG_SECURE` to the lock's window unconditionally (`PinLockActivity.kt:512`), before any content. That is pinned
  by `LockGlanceGuardTest` ("FLAG_SECURE is on the lock's window before its content") and `PinLockManifestTest`. It
  doesn't depend on the Advanced page's "Block screenshots" switch, which is the phone-wide `setScreenCaptureDisabled`.
  The clause predates this branch. No change needed.
- **`StatusBarHealTest`'s option (b)** (`lockWasUp`, `gestureAskDue`, 6 mentions) is untouched by the branch
  (`PinLockRuntime`/`HomeActivity` unchanged) and green.
- **Strings**: `glance_dismiss_action` is in both `values/` and `values-nb/` ("Remove" / "Fjern", with comments), and
  `glance_action_dismiss` is in `ids_glance.xml`. There is no `announceForAccessibility`. The build passes with
  warnings as errors.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| M1 | compact from 3 rows (`rows <= 2` full) | `LockGlanceTest` "the music tile - full with 0 or 1 rows, compact from 2 ..." |
| M2 | no row cap (`rows = n`) | `LockGlanceTest` "the layout - every row up to eight ...", "the music tile ..." |
| M3 | a tie is horizontal | `LockGlanceTest` "the touch's axis ..." |
| M4 | slop strict (`<` for NONE) | `LockGlanceTest` "the touch's axis ..." |
| M5 | 40 % strict (`>`) | `LockGlanceTest` "the dismiss - 40 percent or a fling ..." |
| M6 | fling in any direction | `LockGlanceTest` "the dismiss ..." |
| M7 | fling without the 16 dp | `LockGlanceTest` "the dismiss ..." |
| M8 | fling `>` 500 | `LockGlanceTest` "the dismiss ..." |
| M9 | `glanceDismissAlpha` unclamped | `LockGlanceTest` "the dragged row fades ..." |
| M10 | `withoutDismissed`: the plain row always out | `LockGlanceTest` "the dismissed missed-call rows stay out ..." |
| M11 | `withoutDismissed`: numbers ignored | `LockGlanceTest` "the dismissed missed-call rows stay out ..." |
| M12 | `keysOf` without the `upTo` bound | `LockGlanceGuardTest` "the glance's one cancel ..." (source pin) |
| M13 | `keysOf` without the package | `LockGlanceGuardTest` "the glance's one cancel ..." (source pin) |
| M14 | `noticeMarks` takes the seen mark first | `MissedCallNoticeTest` "a call after the dismissed mark ..." |
| M15 | prune edge strict | `MissedCallNoticeTest` "the dismissed marks are pruned ..." |
| M16 | a new mark overwrites a later one | `MissedCallNoticeTest` "the dismissed marks are pruned ..." |
| M17 | the dismissal pass counts against the effective state | `LockGlanceGuardTest`, `MissedCallNoticeTest` (source pins) |
| M18 | the dismissal pass alerts | `MissedCallNoticeTest` "the notifier's dismissal pass" |
| M19 | glue: drop `if (swipeInScroll) swipeBlocked = true` | **not caught** |
| M20 | glue: drop `rest(view.root, i)` in `bindRows` | **not caught** |
| M21 | glue: "Fjern" off the GLANCE stage (`removeByAction`) | **not caught** |
| M22 | glue: a drag on the ASK stage (`dismissRow`'s stage check) | `LockGlanceGuardTest` "a dismissal changes only ..." |
| M23 | glue: `release` ignores the listener | `LockGlanceGuardTest` "a dismissal changes only ..." |
| M24 | glue: `dismissBusy` only while animating | `LockGlanceGuardTest` "a dismissal changes only ..." |
| M25 | glue: `settleDismiss` dropped from `stop()` | `LockGlanceGuardTest` "a dismissal changes only ..." |
| M26 | glue: `step(GlanceStageEvent.Unlock)` added in `commitDismiss` | **not caught** |

Every briefed pure mutation (M1-M18) is caught. Four glue mutations survive: M19, M20, M21 and M26 (Q-4).

## Findings

### Q-1 (Low) - a tap that lands during the 300 ms slide/collapse opens the row below it

`GlancePanel.kt:525` (the DOWN records no row while `dismissing != null`, rightly), `GlancePanel.kt:822-823` (the
commit's `render()`), and `bindRows` rebinding the click listeners by index.

What's wrong: after a lift past the threshold, the rows aren't rebound for 300 ms. A DOWN in that window on row view
j (showing row j, j > the dismissed index) passes to the views as a press. The commit then rebinds view j to row j+1
and moves it down. The UP still clicks view j, whose listener is now `tap(row j+1)`. The keypad comes with row j+1's
hint, and after the PIN the wrong conversation opens. A kid tapping the next row right after a swipe is a plausible
sequence. A post that re-sorts rows under a held finger had the same effect before r11, but a dismissal makes it
systematic. There is no security impact (the PIN gates the open).

Expected fix: don't rebind the rows under a touch that is down. Either hold the commit's render while `swipeDown !=
null`, or make the next event return `CANCEL_VIEWS` when a commit rebinds during a gesture (a `cancelViewsNext` flag).
With the first option, also call `flushRender()` after `swipeReset()` on a NONE gesture's lift (today a render held
then would wait for the next DOWN). Pin it.

### Q-2 (Low) - TalkBack's "Fjern" works under the VoIP ring card

`GlancePanel.kt:880-893` (`removeByAction`), `PinLockActivity.kt:763`.

What's wrong: the design says no dismissal starts under the VoIP card (§5, the touch table's last row). The touch
path honours that through `covered`, but the accessibility action checks only `started`, `leaving`, the stage and
`dismissBusy()`. The glance isn't hidden from accessibility while the ring card shows (only the keypad is), so
TalkBack can still focus a row behind the card and run "Fjern". Row clicks there predate r11; the dismissal is new.

Expected fix: give the panel the covered state (the activity already knows it: `voipRing.root.visibility`), refuse
"Fjern" while covered, and preferably hide the glance's content from accessibility under the card. Pin the guard
next to the stage check.

### Q-3 (Low) - `alert && !ownDismiss` can swallow a real new call's alert

`MissedCallNotifier.kt:294`.

What's wrong: `step.alert` is true only for a call newer than any alerted (or after the call log's ids restart). If a
new missed call is in the log when the dismissal's pass runs (it is logged just before Telecom's broadcast), that
pass posts it silently and sets `lastPosted`. It doesn't advance `KEY_ALERTED_UP_TO`. Telecom's broadcast then
recomputes an equal notice (`shown && notice == lastPosted` -> Keep), so that call never alerts or wakes the screen.
The window is narrow, but the guard buys little: the dismissal itself never makes `step.alert` true.

Expected fix: drop `&& !ownDismiss` (a dismissal's update alerts only when it carries a genuinely new call, which
should alert). Or keep it, but then also leave `lastPosted` unset for a suppressed alert so the broadcast's pass posts
it with sound. Update `LockWakeTest`'s and `MissedCallNoticeTest`'s pins either way.

### Q-4 (Low) - pins: the dismiss machinery is outside the "only step" pin, and four glue mutations survive

`LockGlanceTest.kt:629-630` (`LockMediaGuardTest`'s pin reads only `swipeNow`), `LockGlanceGuardTest.kt:185` and
`:287`.

What's wrong:

- "No new path to the keypad" is pinned only inside `swipeNow`'s text. `dismissTouch`, `release`, `collapse`,
  `commitDismiss`, `settleDismiss`, `removeByAction` and `flushRender` sit after `swipeReset`. A `step(...)`, `tap(`
  or target capture there passes the suite (M26). It would still lead only to the keypad, not out of the lock, so
  this is Low.
- The cancel ban in the glance files is narrowed from the substring `cancelNotification` to `cancelNotification(`.
  So `::cancelNotification` or `cancelNotification (` would slip past. The panel keeps the stricter substring.
- `GlanceReader.dismiss(` and `MissedCallNotifier.dismissContact(` are counted only inside `GlancePanel`. Nothing pins
  that no other file (Home, a receiver) calls them: the offenders scan covers only `server/`, `push/` and `crash/`.
- Survivors: M19 (sideways inside the scroller blocks the unlock), M20 (`rest()` on every rebind; without it a reused
  view keeps a collapsed height of 0 or a slide) and M21 ("Fjern" only on the GLANCE stage).
- Smaller: the panel's references to `MissedCallNotifier.`/`MissedCallsRepo.`/`GlanceStore.`/`GlanceReader.` aren't
  allowlisted. A `MissedCallsRepo.markSeen(` or `GlanceStore.markOpened(` from the panel would pass, and the old
  blanket `dismiss` ban used to stand in for that.

Expected fix (lean):

- Pin the text from `// ---- a row swiped away` to `private fun swipeControls(` to contain no `step(`, `tap(`,
  `captured =`, `promptLabel =` or `GlanceOpener`.
- Ban `Regex("""cancelNotification(?!s\()""")` in the glance files.
- Pin `GlanceReader.dismiss(` and `MissedCallNotifier.dismissContact(` callers across `src/main` to `GlancePanel.kt`
  alone.
- Pin `if (swipeInScroll) swipeBlocked = true` inside the HORIZONTAL branch, `rest(view.root, i)` in `bindRows`, and
  `state.stage == LockStage.GLANCE` in `removeByAction`'s `free`.
- Optionally allowlist the panel's members of those four objects (`current`, `addListener`, `removeListener`, `rows`,
  `contentIntent`, `canDismiss`, `dismiss`, `dismissContact`).

### Q-5 (Low) - sequential missed-call dismissals flash the other contact back

`GlancePanel.kt:828-832` (`hideMissed`), `GlancePanel.kt:1148-1154` (`shownRows`).

What's wrong: the held set is dropped as soon as `MissedCallNotifier.current` changes, whichever pass changed it.
Dismiss Pappa, then Mamma, before the notifier answers. Pappa's pass posts C1 = {Mamma}, `current` changes, the set
{Pappa, Mamma} goes, and Mamma's row comes back until her own pass clears ours. The set solves only "both before any
answer". The same happens when a queued recompute that started before the mark changes `current`. It's a flash of
tens of ms; nothing is lost.

Expected fix: keep each number hidden until a `current` arrives that was computed after its own `dismissContact`. For
example, `dismissContact` takes a callback that is posted to main when its pass is done, and the panel drops that
number then. Or keep the set while any listed number is still in `current`'s contacts with no call newer than its
mark. Cheap either way. Fine to leave as is if the emulator shows no visible flash.

### Q-6 (Low, docs) - say what a missed-call dismissal changes besides our notification

The design §3.5, `launcher/CLAUDE.md` (the glance bullet), and `MissedCallNotifier.dismissContact`'s KDoc
(`MissedCallNotifier.kt:166-174`).

What's wrong: the brief's invariant is "the only state a dismiss changes is that row's notifications and the
per-contact mark". The plain row's `MissedCallNotifier.dismiss`, and the last contact's Clear, also mark call-log rows
read (`new = 0, is_read = 1`) and reset Telecom's count, without the PIN. That is the user's decision (the plain row
uses the notifier's dismiss) and the shade's own behaviour, and the badges don't depend on it, so it is acceptable.
But it is a third kind of state and isn't written down where the invariant is. The KDoc also says "marks the shown
range read" where the Clear marks up to the pass's `unread.newestId` (all unread missed rows, inbound-only contacts'
included, as every other Clear does).

Expected fix: one clause in §3.5 / "As built" and in `launcher/CLAUDE.md`: "...and, for the plain row or the last
contact, the call log's unread missed calls are marked read, as the shade's swipe of ours does". Fix the KDoc's
range.

### Q-7 (Low) - a fault after `dismissing = d` freezes the rows until the screen goes off

`GlancePanel.kt:733-758` (`release`), `:762-789` (`collapse`), `:500-508` (`swipe`'s catch).

What's wrong: `dismissing` is set before the slide's animator is built and started, and `collapse` reads layout
params before its animator exists. A throw there (caught by `swipe()`'s catch or `guarded`) leaves `dismissing` set.
`dismissBusy()` then holds every row render, and no new message shows until `stop()`/the lock-up's `settleDismiss`
commits it. The row also stays half slid. It's unlikely, but it's the one way a held render can be lost for minutes.

Expected fix: in `swipe()`'s catch, call `settleDismiss()` (it commits a released dismissal and springs a drag back),
or set `dismissing` only after `start()`. Guard `collapse` so that a throw commits at once (`commitDismiss(d)` in a
catch).

### Q-8 (Note) - a sideways first movement off a row in the scroller now blocks the unlock for the whole gesture

`GlancePanel.kt:562`.

What's wrong: as designed (§3.2's table), but the implementer already kept the unlock's own rule outside the
scroller. On the clock, the date or the tile nothing else takes a sideways gesture. A lazy diagonal swipe up whose
first 8 dp lean sideways used to unlock once it turned vertical (`glanceSwipeStep` judges "mostly vertical" at each
moment). Now it does nothing when the page can't scroll.

Expected fix: optional. Keep the block only for a DOWN on a row (where the gesture became the row's), and leave the
unlock's own rule elsewhere. If kept, emulator check 2 should include "a slightly diagonal swipe up on the clock".

### Q-9 (Nit) - the spring-back can fight a new drag on the same row; the row jumps by the slop

`GlancePanel.kt:690-720`, `:559`.

What's wrong: a DOWN within 150 ms of a spring-back doesn't end it unless a render is pending. A new sideways drag on
that row then sees the animator's updates and its end overwrite `translationX`/`alpha` until the next MOVE. Also,
`follow(drag, dx)` at the decision moves the row by the full slop at once (Android's shade subtracts it).

Expected fix: `springing?.end()` in the DOWN branch (next to `dropDrag`). Optionally follow `dx - sign(dx) * slop`.

### Q-10 (Nit) - small doc and code leftovers

- `docs/design/r9-launcher-glance-small-items.md` §2 ("the rows scroll", the fit) and §7 Q3 have no "superseded by
  r11" pointer. Design 25 and 59d got one.
- `PinLockActivity.kt:759`'s KDoc and `launcher/CLAUDE.md` ("feeds every touch to `GlancePanel.swipe` first (not under
  the VoIP ring card)") say the card bypasses the swipe. It doesn't: every touch is fed with `covered`, which r11's
  spring-back relies on. Reword.
- `renderNow` doesn't clear `renderPending` when it isn't busy, so one stale extra render can follow. Set it to false
  in the non-busy branch.
- `tileChanged` doesn't update `laidOut`, so a tile change is logged only at the next render's layout line.
- `GlanceReader.dismiss` triggers a render through `hide` -> `changed()`, then `commitDismiss` renders again. That's
  harmless, but one could go.
- The PLAN entry sits under "Done (not released yet)" while it says "not merged". It is accurate otherwise; move it
  on merge if that's the convention.
- The brief's "the 'more' line: tap -> keypad": the line is not a button (design 25 §1.2, "not a button"). A tap on it
  does nothing, as before r11, and a swipe up there asks as anywhere. No change needed, but say so in §1.3 if the
  coordinator expects otherwise.

## Docs

- The note's "As built" matches the code: `placeList` in `tileChanged`, the rows held during the drag with
  `flushRender`, the unlock block only inside the scroller, `alert && !ownDismiss` (see Q-3), the numbers set, and the
  PWA wording. The moved pins are listed truthfully.
- Design 25 §3/§9 and 59d §8.15 have their "superseded by r11" lines. r9 doesn't (Q-10).
- The `launcher/CLAUDE.md` glance bullet describes the scroller, the rule and the dismissal correctly, apart from Q-6's
  missing clause and Q-10's "not under the VoIP ring card".
- The PWA line ("One can be swiped away, as on Android's own lock screen. Nothing can be answered there, and it can't
  be screenshotted.") is true, the screenshot clause by the lock's own `FLAG_SECURE`. Its test pins the first two
  sentences.

## Verdict

**Merge after a fix round.** No Blocker, High or Medium. The state machine is sound: a row is never rebound under a
drag, a stale index can't dismiss another conversation, a held render reaches the screen on every normal path, and a
lock-up or stop never dismisses a row that wasn't released. The lock still weakens nothing: the dismissal adds no
path out of the lock, and the one cancel is a dismissed row's own keys.

The round should take:

- Q-1: a tap during the collapse opens the row below.
- Q-2: "Fjern" works under the ring card.
- Q-3: a new call's alert can be swallowed.
- Q-4: pin the dismiss section and the three surviving glue lines.
- Q-6 and Q-7.

Q-5, Q-8, Q-9 and Q-10 are optional or nits. After the round, the coordinator runs the note's §6 emulator checks.
