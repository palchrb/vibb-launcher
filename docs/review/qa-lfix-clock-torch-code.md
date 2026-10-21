# Code QA - launcher fix batch: the empty "Timer" card, the late stopwatch, air above the flashlight

- Commit: `c8634f19` on branch `lfix-clock-torch`, on top of main `65168f18` (`git diff 65168f18..lfix-clock-torch`).
- Read:
  - root and launcher `CLAUDE.md` ("The lock's glance page (design 25)", "The clock on the lock", "The music tile
    alone, the time line, the flashlight");
  - `docs/design/42-lock-clock.md` §1.4 and its implementation notes;
  - PLAN.md's two backlog entries (the empty Timer card / late stopwatch, the flashlight row), now marked done;
  - the full diff, and around it `BadgeListenerService`, `ClockReader`, `ClockLockPanel`, `GlancePanel` (`start`,
    `resumeNow`, `fitNow`), `KidInsets` and `LockGlanceLayoutTest`'s model.
- Ran:
  - launcher `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1482 tests, 0 failures, 0 errors, 0
    skipped**;
  - 12 one-change mutations against `ClockTileTest`, `LockGlanceGuardTest`, `LockGlanceTest`,
    `LockGlanceLayoutTest` and `KidPaletteTest`, each reverted. `git status` is clean.

## Findings

**1 (Medium) - at 473 dp the full music tile alone keeps the big clock by 1.2 dp, not 5.2.**
`view_lock_glance.xml:23` and `:450`. The batch takes 12 dp for the air and gives back 8 dp from the page's bottom
padding. So the content needs 12 dp more and has 8 dp more room: net, the page has 4 dp less slack everywhere.
- Every fit `LockGlanceLayoutTest` pins still holds in the model. The tightest one is the full music tile alone at
  473 dp, the Jelly Star with 3-button navigation: 455.8 dp of parts in 457 dp (`LockGlanceTest.kt:569`, 471.8 with
  the paddings). Before the change it had 5.2 dp to spare.
- At runtime, `GlancePanel.fitNow` measures the real views. The model can't promise 1.2 dp:
  - it sets every text's height to 1.4 x sp; Nunito's real line height with font padding is different;
  - at 240 dpi each measured part rounds to whole px (1.5 px per dp), and there are four parts.
- If the real sum comes out more than 1.2 dp larger, the most common locked state - music playing, no rows - falls
  to candidate 2 and shows the small clock. Avoiding exactly that was the reason for the padding trade. The next
  tightest pinned case, 3 rows + "more" with no music, has 5 dp left (it had 9).

Fix (either one):
- **(a)** 12 dp of air instead of 14 (`layout_marginTop="12dp"`). That is the low end of the 12-16 dp PLAN proposed,
  and it leaves 3.2 dp. The test totals drop by 2: 469.8, 466, 396, 460, 461.6, 517.4.
- **(b)** Keep 14 dp and add a device check to PLAN: on the Jelly Star with 3-button navigation, music playing alone
  and no notification rows, logcat must show `Glance fit: candidate 1`. Switch to (a) if it shows 2.

**2 (Low) - "no answer" reads as "nothing active": a failed lookup clears every tile.**
`BadgeListenerService.kt:157`: `getActiveNotifications(keys).orEmpty().filterNotNull()`.
- `NotificationListenerService.getActiveNotifications(keys)` doesn't throw when it can't answer. It returns null:
  when the service isn't bound (`isBound()` false), and when it catches a `RemoteException` itself.
- `.orEmpty()` turns that null into an empty list. `ClockReader.dropStale` then sees every kept key as gone and drops
  all of them.
- That is the opposite of what both KDocs say: "null when the listener can't answer" here, and "No listener or no
  answer: everything stays" on `dropStale` (`ClockReader.kt:111-112`).
- `LockGlanceGuardTest.kt:327` pins the `.orEmpty()` form.
- In practice it is rare: after an unbind the call fails with a `SecurityException`, which is caught and correctly
  gives null.

Fix: `getActiveNotifications(keys)?.filterNotNull()` (null stays null), with the test string updated to match. The
same `.orEmpty()` in the older `activeOrNull()` (sweep and recount) was there before this batch and is out of scope.

**3 (Low) - the lookup outlives a destroyed service.**
- `ClockReader.lookup` captures the `BadgeListenerService` instance (`BadgeListenerService.kt:31`). Only
  `onListenerDisconnected` clears it.
- `onDestroy` (`:44-47`) already detaches `NotificationRuleRuntime` separately. That is the class's own admission
  that a destroy can come without the disconnect.
- If it does, the process-wide `ClockReader` keeps a dead service alive. It then makes a binder call on it at every
  Fossify post and every page resume. Each call fails harmlessly (`SecurityException` -> null -> everything stays).

Fix: call `ClockReader.disconnected()` in `onDestroy` as well; it is idempotent. Or add a `detach()` that only nulls
`lookup`, and extend the wiring check in `LockGlanceGuardTest`.

**4 (Low) - three of the new reader/panel lines aren't pinned (mutations 6, 7 and 9 survive).**
- **Mutation 7.** `dropStale` without `stale.forEach { entries.remove(it) }`: it still returns true, so the stale
  tile stays and the whole reconcile does nothing. Every test still passes. `LockGlanceGuardTest` checks only the
  lookup line and the package filter.
- **Mutation 6.** `removed` written as `if (gone || dropStale())`: when the removal itself hit, the stale check never
  runs for the other keys.
- **Mutation 9.** `ClockLockPanel.show` back to `tile?.text?.takeIf { !ended }`: the doubled "Stoppeklokke" returns.
  `clockSmallText` is tested, but nothing ties the panel to it.

Fix: three more lines in the new `LockGlanceGuardTest` case:
- `body(clockReader, "dropStale")` contains `stale.forEach { entries.remove(it) }`;
- `body(clockReader, "removed")` contains `if (dropStale() || gone) changed()`, and `posted` contains
  `if (dropStale() || touched) changed()`;
- `code("ClockLockPanel.kt")` contains `clockSmallText(it, label)`.

**5 (Nit) - one more binder call on the main thread for each of Fossify's posts.**
The timer and the stopwatch update their notifications every second (design 42). Each update now asks NMS for the
kept keys, next to `recount()`'s full `activeNotifications` read in the same callback. At that rate this is fine.
If it ever matters, `onNotificationPosted` could read the active list once and pass it to both.

## The checks, with the evidence

**The cause.**
- The likely source is Android 16's autogroup summary: posted under `org.fossify.clock`, on a child's channel, with
  `FLAG_GROUP_SUMMARY`, no title and no text, and so classified as TIMER and ranked above STOPWATCH. That is a
  plausible explanation for the empty card.
- But the user's dumpsys from the time of the bug (PLAN) shows NMS held *no* other Fossify notification while the
  card was stuck. So in that capture the stuck entry was a removal `ClockReader` never applied, not a live summary.
- Of the three changes, the reconcile is the one that fixes the observed state. The summary skip and the blank skip
  are good defences: either one alone hides that card shape for good.
- The reconcile is sound. It asks NMS directly for exactly the kept keys, so a missed removal is caught at the
  stopwatch's next update (within a second) or at the page's next resume. It is correct apart from findings 2 and 4.

**Threading.**
- `NotificationListenerService` delivers its callbacks, `onListenerConnected` included, on the main looper.
- `GlancePanel.resumeNow` is on the main thread, and `ClockApp.refresh` posts `unverified()` to the main thread.
- So `entries`, `shown` and `lookup` are only touched on the main thread.
- `getActiveNotifications(keys)` is an ordinary binder call and may be made from any thread; here it runs on main.
  An incoming listener callback can't re-enter during that call, because callbacks are queued on the main looper.
- `changed()` only notifies `ClockLockPanel`, which sets `clockDirty` and asks for a coalesced render. `ClockReader`
  isn't re-entered.

**Guarding.**
- Every public entry point of `ClockReader` except `addListener`/`removeListener` runs in `guarded` (catches
  `Throwable`). The test pins that exact list of functions.
- The lookup runs inside `dropStale`, which is only called from guarded functions.
- `activeOrNull(keys)` catches `Exception`. Anything else is still caught by `ClockReader`'s guard.
- `GlancePanel.resume`/`start` are guarded as before. Nothing new can reach the lock's crash guard.

**Privacy.**
- It asks only for keys already kept, all of them `org.fossify.clock`. It filters the answer to that package again,
  and reads only `packageName` and `key` from it.
- `read` skips a summary before it touches `extras`.
- The only log line is the exception's simple class name. No key or text is logged.

**Lifecycle.**
- `attach` comes before `sweep()` (pinned). `onListenerDisconnected` -> `disconnected()` nulls `lookup` and clears.
- `ClockApp` now calls `unverified()` instead of `disconnected()`. That is right: a lost signer must not cut the
  lookup until the next connect.
- The one gap is `onDestroy` (finding 3).

**Showing and ranking.**
- `clockShown` is `filterNot { blank }.minByOrNull { clockRank }`. ENDED (0) < TIMER (1) < STOPWATCH (2) is
  unchanged. Ties keep `LinkedHashMap` order.
- `changed()` matches the entry by identity (`===`). That is right, because `minByOrNull` returns the element itself.
- A real running timer ("11:12", "1 timer er i gang"), the stopwatch ("30:55") and an ended timer ("Timer", "Tiden er
  ute") are never blank. Their `Stopp` path in `read` is untouched.
- `clockSmallText` keeps "1 timer er i gang" under "Timer", because the normalised words differ. It drops the
  stopwatch's "Stoppeklokke" and never shows an ended timer's text.

**The layout.**
- `glanceFit`'s `fixed` is measured at runtime from `lock_glance_unlock` + `lock_glance_bottom` with their margins
  (`GlancePanel.fitNow`). `available` is the root's height minus its padding. So the runtime model follows the XML by
  construction.
- The test model reads the XML too (118 dp pinned, available `569 - 48 - nav - 8 - 8`).
- The re-derived totals check out by hand: 118 + 78.4 + 25.6 + 233.8 + 16 = 471.8; 468, 398, 462, 463.6, 519.4;
  with the alarm 472.2 > 457 and 476 > 457. The slack is finding 1.

**The 8 dp bottom padding next to the system bars.**
- `KidInsets` adds the navigation inset to the XML padding. So the row now ends 8 dp above the navigation bar or
  gesture area, where it used to end 16 dp above it.
- 3-button navigation: the torch disc sits 8 dp above the bar's top edge, with the recents button under it. 8 dp is
  Material's minimum gap between touch targets. Acceptable.
- Gesture navigation: the 24 dp inset is between the disc and the screen edge, with the handle in it. Fine.

**The user's request ("litt for tett").**
- The disc used to start 2 dp under "Lås opp"; now it starts 14 dp under it. Nødsamtale's text, centred in its 48 dp,
  is about 28 dp below. That meets the request.
- Below the row there is 8 dp of padding plus the navigation inset, so the tightness hasn't simply moved down.
- The torch's off look (`bg_music_control`, `kid_core`) is unchanged and now pinned by `KidPaletteTest`. Whether the
  screenshot showed it lit can only be judged on the phone (no flash unit on the emulator).

**The unrequested bottom padding.**
- It is a reasonable trade, and PLAN states its alternative clearly: a plain +12 dp would cost the big clock at
  473 dp in three cases.
- It changes only the page's lowest margin, within the same look the user asked about.
- No need to ask before the merge. Tell the user in one line with the merge, and let the phone check owed in PLAN
  judge it, together with finding 1's fit check.

## Mutations

| # | Mutation (one change, reverted) | Result |
| --- | --- | --- |
| 1 | `read` without the `FLAG_GROUP_SUMMARY` skip | caught - `LockGlanceGuardTest` (fix 2026-10-21) |
| 2 | `clockShown` without `filterNot { it.blank }` | caught - `ClockTileTest` blank tile |
| 3 | `blank` without `text == null` | caught - `ClockTileTest` blank tile |
| 4 | `clockStale` reversed (`active - kept`) | caught - `ClockTileTest` stale keys |
| 5 | `clockSmallText` compares raw strings (no `clockWords`) | caught - `ClockTileTest` small line |
| 6 | `removed`: `if (gone \|\| dropStale())` (the check skipped when the removal hit) | **NOT caught** (finding 4) |
| 7 | `dropStale` without `stale.forEach { entries.remove(it) }` | **NOT caught** (finding 4) |
| 8 | `resumeNow` without `ClockReader.reconcile()` | caught - `LockGlanceGuardTest` |
| 9 | `ClockLockPanel.show` back to `tile?.text?.takeIf { !ended }` | **NOT caught** (finding 4) |
| 10 | the page's `paddingBottom` back to 16 dp | caught - `LockGlanceLayoutTest` fits at 473/497, alarm line |
| 11 | the bottom row's `layout_marginTop` 14 -> 10 dp | caught - `LockGlanceLayoutTest` (3 tests) |
| 12 | `read` without `if (tile.blank) return null` | caught - `LockGlanceGuardTest` |

9 caught, 3 survive (6, 7, 9).

## Verdict

**Approved with fixes.**
- The clock fix is correct, main-thread only, guarded and private. It shows and ranks the real running timer, the
  stopwatch and the ended timer with its Stopp as before.
- The batch meets the user's spacing request.
- Before the merge:
  - finding 1: (a), or (b) as an owed device check;
  - finding 2: a one-word change.
- Findings 3 and 4 are small and can go in the same fix commit. Finding 5 needs no change.
- The bottom padding trade doesn't need the user's decision first. Mention it in the merge note and confirm it on the
  phone.
