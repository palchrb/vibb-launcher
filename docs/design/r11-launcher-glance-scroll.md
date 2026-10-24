# Launcher round 11 - the lock's glance page scrolls whole, a rule for the compact player, rows swipe away (proposal)

Asked 2026-10-24 by the user, on their emulator `jellystar2` (480x854 px at 240 dpi = 320x569 dp). The screenshot shows
the big clock, the date, the alarm line, the compact music tile, one WhatsApp row and a second row cut off above the
bottom block. Launcher only. The one server line this touches is flagged in §0 (F2). The three asks:

1. "når det er flere notifications nedenfor, så burde man kunne skrolle hele siden nedover, at klokke og musikk widget og
   alt flytter seg oppover så man kan lese notifications nedover bedre" - the whole page scrolls.
2. "ved 2 notifications, så bør musikk widget gå til mini-utgaven" - the compact tile from two rows up.
3. "swipe varslene til venstre ut av skjermen for å slette de" - a row swiped sideways is dismissed.

Agreed with the user before this note: the clock scrolls away with everything else. A pinned small clock was offered
and is **rejected** (it would be a second clock look, and the status bar already shows the time). The fit stops
shrinking the clock and the tile to make room for rows. Everything here also holds when the glance is Android's face
(design 59): nothing depends on our keypad stage.

## 0. What contradicts the asks today (read before starting)

- **F1. Design 25 decided the opposite of ask 3**, and the tests pin it. §3 "What never runs from the lock" says "no
  dismiss or snooze ... rows can't be swiped away (a stranger can't hide a message from the kid)". §9 says "long-press,
  swipe and double-tap on rows do nothing". `LockGlanceGuardTest` enforces both:
  - `nothing glance cancels, snoozes or marks a notification shown` (`GlanceReader`, `GlanceStore`, `GlanceRows`);
  - `the glance panel opens nothing` (no `cancelNotification`, `setOnTouchListener`, `ItemTouchHelper`, `onSwiped`
    or `dismiss` in `GlancePanel`).

  This round reverses the decision on the user's word. §3.5 says why that is acceptable, and which pins move and which
  stay. The implementer adds a pointer line to design 25 §3 and §9 ("superseded by r11 §3").
- **F2. The PWA tells the parent the opposite.** `server/templates/device_lock.html:243` says "Nothing can be answered
  or dismissed there", and `server/src/tests/lock_glance.rs:713` pins that text. Once rows can be dismissed, the text
  is false. The fix is two lines in the same branch: the text becomes "Nothing can be answered there; a row can be
  swiped away, as on Android's own lock screen", and its test follows. There is no API or contract change. The other
  choice is to leave the line stale. **For the user to decide (Q4).**
- **F3. Design 25 §1.4: "a listener's cancel would fire its delete intent".** That was the reason the glance never
  cancels a notification when it *opens* one. For a dismissal, firing the delete intent is the point. NMS cancels a
  listener's keys with `REASON_LISTENER_CANCEL` and `sendDelete = true` (as AOSP reads; emulator check 4 confirms), just as for the shade's own swipe, so the app
  learns about it exactly as from the shade. No conflict, but say it in the KDoc.
- **F4. r9 §2 / design 25 "the page never scrolls (except the rows)"** and the whole of `glanceFit` (seven candidates,
  twice with a timer) are superseded by §1. `GlanceHeights`, `GlanceFit`, `glanceRefitDue`, `fitTilePx`/`fitClockPx`
  and `CLOCK_SMALL_SP` go.
- **F5. 59d §8.15 and JL-3**: "on the Jelly Star's modelled bar the compact tile (the user's liked look) no longer shows
  for music + 1 row". With §2 this is a rule, not a fit outcome: music + 1 row is the full tile everywhere, and music +
  2 rows is the compact tile everywhere.
- **F6. A row can't reach the screen's edge today.** The rows sit inside the root's 16 dp side padding, and every
  parent clips, so a row slid left would vanish into an edge 16 dp in from the screen's edge. "ut av skjermen" needs
  the side padding moved off the root (§1.1).
- **F7. `openAction` is shared** by the rows and the timer tile. The "Fjern" action must go on rows only (§3.4).
- **F8. `View.announceForAccessibility` is deprecated in API 36**, and CI builds with warnings as errors. Don't use it
  for "removed" (§3.4).

## 1. The whole page scrolls (ask 1)

**Behaviour.** Everything above the bottom block scrolls as one: clock, date, alarm line, the music tile, the timer tile,
the rows and the "more" line. The bottom block ("Nødsamtale" and the flashlight) and the handle stay anchored as today.
When the content fits, nothing scrolls and the page looks as it does now. When it doesn't, a drag moves the whole page
up, the clock goes away under the status bar, and a 16 dp fade shows at whichever edge has more. Every lock-up starts at
the top. Back from the keypad keeps the position, because coming back with the screen on is no event (`glanceStageStep`).

### 1.1 Layout (`view_lock_glance.xml`)

```
lock_glance_root      LinearLayout vertical, insets as padding; paddingTop/Bottom 8dp, paddingStart/End 0 (was 16)
  lock_glance_scroll  ScrollView match_parent x 0dp weight 1, requiresFadingEdge vertical, fadingEdgeLength 16dp
    lock_glance_top   LinearLayout vertical, match_parent x wrap_content, center_horizontal,
                      paddingStart/End 16dp, clipChildren=false, clipToPadding=false
      clock, date, alarm, lock_media_tile, lock_media_compact, lock_timer_tile    (as today)
      lock_glance_list  marginTop 14dp (22 without a tile, set by the panel), clipChildren=false
        lock_glance_rows  (clipChildren=false), lock_glance_more              (as today)
  lock_glance_bottom  (marginTop 8dp, marginStart/End 16dp - the padding the root gave it)
```

- **One plain `ScrollView` around the top block, no RecyclerView.** It holds at most eight rows (§1.3), so recycling
  buys nothing, and the ids, the `scrollTo(0, 0)` and the swipe's `scrollTakes` all carry over unchanged.
- The scroller keeps the id `lock_glance_scroll`, so `resumeNow`'s lock-up branch is untouched (it already has
  `glance.lockGlanceScroll.scrollTo(0, 0)`) and so is `swipeNow`'s `scrollTakes`. The scroll is never `GONE`.
  `lock_glance_list` takes over the margin and the `GONE` rule, and is gone only with no row.
- The side padding moves from the root to `lock_glance_top` and the bottom block (F6). The scroller is then the
  screen's full width, and a row slid sideways is clipped only at the screen's edge. Vertically the `ScrollView` still
  clips at its own bounds, so nothing is drawn over the status bar or the bottom block.
- `placeProfile` already adds `root.paddingStart + lockGlanceTop.paddingStart`, so the clock's place doesn't move.
  `pageWidth()` subtracts the top block's paddings too, so the clock is still measured at the 288 dp content width.
- The clock's box may still reach above the content (the profile's negative margin): the scroller clips it where the
  top block clipped it, and the digits' ink stays inside, as today.

### 1.2 What is left of the fit

Nothing measures any more. The sizes are rules:

| part | rule |
|---|---|
| clock | the big clock always: 56 sp without a profile; the profile's placed 86 dp clock (design 59d step L) |
| date and alarm line | always shown (the alarm line while there is one; no fit drops them) |
| music tile | §2's rule |
| timer tile | shown while a timer runs, always full (it has no compact form) |
| rows | at most `GLANCE_MAX_ROWS` = 8, then the "more" line (§1.3) |

**No fit at all, not even for no rows, 320x569 or font scale 2.0.** With the page scrollable, an overflow means a
scroll, never a clipped or shrunk part. One rule is also more predictable for the kid than a fit that picks a different
clock or tile depending on the font.
- **320x569 dp at font scale 1.0**: everything fits up to music + 3 rows (table below).
- **Font scale 2.0, no profile**: about 157 dp of clock, about 84 dp of wrapped date and about 222 dp of full tile come
  to about 463 dp, in a 401 dp scroller. The page scrolls about 60 dp to reach the tile's lower half. The bottom block
  is measured whole and only grows when "Nødsamtale" wraps. This is accepted: every part is reachable, nothing is cut,
  and r9's compact last resort was only ever a way around not being able to scroll.

**Code** (`GlancePanel`):
- `fitNow` becomes `layoutNow`. It sets the no-profile clock's 56 sp, or runs `placeProfile`, then calls `apply`
  with the pure layout (§2) and logs.
- The layout listener keeps r9 §1's key, `available(bottom - top) != placedFor`. The bars arriving as padding still
  move the profile's clock, because `keyguardBarPx` reads the window's insets. `refitPending`'s one-frame skip stays,
  set only when a placement changed a margin or the layout changed.
- `refit()` keeps its name (`PinLockActivity.onConfigurationChanged` calls it) and runs `layoutNow`.
- `tileChanged` only shows the tile as the rule says: no `glanceRefitDue`, no refit, because a tile coming or going is
  plain layout now.
- The log line becomes "Glance layout: tile COMPACT, 2 rows, 0 more, available 457 dp (pad 56/56)" (enum name, counts
  and dp only), written when the layout or the available height changes.
- `bindAlarm` drops its `fit?.date` condition.

**What scrolls** (the model `LockGlanceLayoutTest` keeps: 1.4 x text, a 48 dp status bar, 3-button / gesture
navigation; available 457 / 481 dp; scroller = available - the 56 dp bottom block = **401 / 425 dp**; content = clock
78.4 + date 25.6 + tile (full 174.8 / compact 90, with its 18 dp margin) + list margin (14, or 22 without a tile) + n x
56 + (n - 1) x 8 + "more" 24):

| case | content | 473 dp | 497 dp |
|---|---|---|---|
| full tile alone | 278.8 | fits | fits |
| music + 1 row (full) | 348.8 | fits | fits |
| music + 2 rows (compact) | 328 | fits | fits |
| music + 3 rows (compact) | 392 | fits | fits |
| music + 4 rows | 456 | scrolls 55 | scrolls 31 |
| music + 8 rows | 712 | scrolls 311 | scrolls 287 |
| no music, 5 rows | 438 | scrolls 37 | scrolls 13 |
| the user's case: profile clock (113.7 + date 12.7), alarm 20.2, music + 2 rows | 370.6 | fits | fits |

### 1.3 The cap: eight rows

`GLANCE_MAX_ROWS` goes from 3 to **8**, and "+ N varsler til" counts the rest.
- **Why a cap at all:** with scrolling, every row could show, but a wall of 30 conversations is no glance and would
  pull the kid into the lock. Eight is two to three screens at 320 dp: enough to read through, small enough that the
  views are inflated once without recycling.
- This answers r9 §7's open question 3 ("list every conversation") with "up to eight". **For the user to confirm
  (Q2).**
- `glanceWakes` and `GlanceStore` keep working on all rows; only the panel's binding caps.
- A dismissal (§3) pulls the ninth row in, and the "more" count drops.

### 1.4 The unlock swipe on the scrolling page

**Today's rule, with no new code.** `glanceSwipeBlocked(onControl, scrollTakes)` with `scrollTakes = hit(scroll, ev) &&
(canScroll(1) || canScroll(-1))`, decided at the DOWN, now covers the whole top block, because the scroller does.
- A swipe from the handle, the bottom inset or the bottom row outside its two controls starts outside the scroller and
  always unlocks.
- A vertical swipe elsewhere unlocks when the page can't scroll either way, and scrolls it when it can.
- **At the top with content below**, a swipe up scrolls. That is the point of ask 1.
- **At the very bottom end**, a swipe up does *not* become the unlock. It is the scroller's overscroll (the edge glow,
  nothing else). This keeps one rule, decided at the DOWN, and the handle is always there. Turning an overscroll into
  an unlock would make a hard read through the rows unlock by accident.

**What `LockGlanceGuardTest` / `LockMediaGuardTest` keep pinning, unchanged:**
- a swipe and a row still only lead to the unlock (`GlanceStageEvent.Unlock` is `swipeNow`'s only `step`; a row's tap
  is `step(GlanceStageEvent.Row(..))`);
- nothing opens before the code;
- the DOWN's guard (`covered || !started || leaving || !glancePageShown(state.stage)`);
- the recognised swipe cancels the views;
- the controls that keep their touch are the layout's touchables less the root and the timer tile;
- the handle is never clickable, and its TalkBack action asks.

The scroller weakens nothing: it only moves what was already on the page.

**TalkBack.** The scroller is one scrollable node, with Android's scroll actions and two-finger scrolling. Moving focus
to a part below the fold scrolls it into view. The root still has no node, the rows stay data-sensitive, and the handle
keeps "Lås opp".

## 2. The compact player from two rows up (ask 2)

**A rule, not a fit.** The tile is **full with 0 or 1 rows and compact from 2 rows up**.
- Counted on the rows the page binds: `min(n, GLANCE_MAX_ROWS)`. With a cap of 8 that is simply n >= 2. The "more" line
  is no row.
- A missed-call row counts like any other.
- No tile without a session (`glanceTile`).
- No hysteresis: rows change rarely, and each change is a deliberate re-render.
- A dismissal from 2 rows to 1 brings the full tile back in the commit's render (§3.3). That is expected.

Pure, in `LockGlance.kt`:

```kotlin
const val GLANCE_MAX_ROWS = 8
const val GLANCE_COMPACT_FROM_ROWS = 2
data class GlanceLayout(val tile: GlanceTileSize, val rows: Int, val more: Int, val timer: Boolean)
fun glanceLayout(tile: Boolean, rowCount: Int, timer: Boolean): GlanceLayout
// rows = min(max(n, 0), 8); more = n - rows; tile = NONE without a session, FULL when rows < 2, else COMPACT
```

- `GlancePanel.apply(layout)` replaces `apply(fit)`. It shows the tile by `layout.tile`, the timer by `layout.timer`,
  the list when `rows > 0` (margin 22 dp without a tile or timer, else 14), the rows `i < rows` and the "more" line when
  `more > 0`.
- `shownTile()` is `glanceLayout(tileUp, rows.size, clockUp).tile`. `shownClock()` is `clockUp`.
- **With 59d §8.15 (F5):** the "compact no longer for music + 1 row" note becomes this rule's first line. With the
  profile clock and the alarm line, music + 1 row is full and music + 2 is compact, on every bar and navigation mode.

## 3. A row swiped away is dismissed (ask 3)

### 3.1 Behaviour

- A horizontal drag on a row moves it with the finger (`translationX`) and fades it:
  `alpha = glanceDismissAlpha(dx, width)` = 1 - |dx| / width, clamped to 0..1.
- **Both directions work**, as in Android's own shade. A right swipe that sprang back would feel broken, and left was
  only the user's example.
- At the lift, the row is dismissed when it moved past **40 % of its width**, or on a fling of at least 500 dp/s in the
  same direction after at least 16 dp. Otherwise it springs back to 0 and alpha 1 in 150 ms (decelerating).
- A dismissed row slides off the screen's edge and finishes fading in 150 ms. Then it collapses: its height and the
  8 dp gap above it (for the first row, below it) go to 0 in 150 ms, so the list closes the gap. Then the dismissal is
  committed (§3.3).
- With "Remove animations" on (`ValueAnimator.areAnimatorsEnabled()` false), there is no animation: the commit
  follows at once.
- A tap still opens the keypad with the row's target, because a tap never passes the slop.
- Only rows, and only on the GLANCE stage (not under Android's pad, not while leaving or covered), can be dismissed. The
  music tile, the timer tile, the alarm line and the "more" line can't.

### 3.2 Touch arbitration (in `GlancePanel.swipeNow`, which already sees every touch first)

**The first movement past the touch slop decides, and the decision holds for the gesture, as Android's shade does.** The
slop is `ViewConfiguration.scaledTouchSlop`, the same one the `ScrollView` uses, so the two always agree. Horizontal
means |dx| > |dy| at that moment; a tie is vertical.

Pure:

```kotlin
enum class GlanceAxis { NONE, HORIZONTAL, VERTICAL }
fun glanceTouchAxis(dxDp: Float, dyDp: Float, slopDp: Float): GlanceAxis

enum class GlanceDismiss { FOLLOW, DISMISS, BACK }
fun glanceDismissStep(dxDp: Float, vxDps: Float, widthDp: Float, lifted: Boolean): GlanceDismiss
// not lifted -> FOLLOW; lifted: |dx| >= 0.4 x width, or (|vx| >= 500, same sign as dx, |dx| >= 16) -> DISMISS; else BACK

fun glanceDismissAlpha(dxDp: Float, widthDp: Float): Float
```

| DOWN on | first move past the slop | what happens |
|---|---|---|
| a row (GLANCE stage) | horizontal | The panel takes the gesture: on that event `CANCEL_VIEWS` (the row's press ends, the scroll gets a cancel), then `CONSUME`. The row follows, and at the lift `glanceDismissStep` decides. No unlock and no scroll for this gesture. |
| a row | vertical | Today's: the page scrolls if it can, else the unlock at a third of the page or on a fling. |
| a row | none (lifted within the slop) | The tap: the keypad with the row's target. |
| a control (music buttons, Stopp, the flashlight, Nødsamtale) | any | The control's. Never an unlock or a dismissal. |
| the rest of the scroller (clock, date, tile body, timer tile, "more" line, gaps) | horizontal | Nothing. The unlock is blocked for this gesture. |
| the same | vertical | Scroll if it can, else the unlock. |
| the bottom row outside its controls, the handle, the bottom inset | vertical | The unlock, always. |
| the same | horizontal | Nothing. |
| any, while a row is being dragged | a second finger, `ACTION_CANCEL` (a system gesture, the screen going off), or the VoIP card covering | The row springs back. Nothing is dismissed or asked. |

- **State in `swipeNow`.** `swipeTaken` becomes a mode (`NONE`, `UNLOCK`, `DISMISS`). The DOWN also records which bound
  row view it hit (`dismissRow`), only on the GLANCE stage with no dismissal running.
- **The pin that stays:** the existing `VelocityTracker` gives `xVelocity` at the lift, and `step(GlanceStageEvent.Unlock)`
  stays `swipeNow`'s only `step`. No `setOnTouchListener` on a row.
- **Ownership:** the activity's `dispatchTouchEvent` stays the only feed, and the existing `CANCEL_VIEWS` branch serves
  both gestures.

### 3.3 The commit: what is cancelled

`commitDismiss(row)` runs once, after the out animation, on the row object bound when the kid saw it. `bindRows` is
deferred while a dismissal animates (`dismissing != null`): a render then binds everything but the rows and the layout,
and remembers to render once more at the commit. So a post that arrives meanwhile never swaps the row's content under
the finger.

- **A message row.** `GlanceReader.dismiss(row.conversation, upToPostTimeMs = row.postTimeMs): Boolean`, the one cancel
  in the glance's code:
  - no listener connected (`service?.get() == null`): returns `false`;
  - else it takes `GlanceStore.keysOf(conversation, upTo)`, every kept key of that conversation posted up to the row's
    newest. A newer post that came after the release is not cancelled; it shows as its own row.
  - It calls `listener.cancelNotifications(keys.toTypedArray())` (clearable keys only: `glanceCandidate` keeps no
    other), then `GlanceStore.hide(conversation, upTo)`.
  - `hide` is the same in-memory mark as `markOpened`: hidden until a newer post. So the row stays gone even when a
    lifetime-extended notification ignores the cancel, or the removal callback comes late. The removal itself then
    arrives as an ordinary `onNotificationRemoved`.
  - An exception returns `false` (guarded; class only in the log). No keys left (the app removed it meanwhile) returns
    `true`.
  - The app hears it through its delete intent, as from the shade (F3). The message itself stays in the app (Element X
    and WhatsApp keep it unread); only the notification goes.
- **A contact's missed-call row** (`number != null`).
  - Our notification 1006 is one notification for every contact, so one contact can't be cancelled alone.
    `MissedCallNotifier.dismissContact(context, number, upToMs = row.timeMs)` runs on the notifier's thread. It commits
    a **dismissed mark** for that number (new CE prefs `missed_call_dismissed`: number -> ms, `commit()`, pruned to the
    14-day log window at each write), then recomputes. That pass may update or clear but never posts anew, and it
    counts against the rules themselves, as `relearn` does, so a no-calls time rule can't empty the notification.
  - The notice leaves the contact out from then on (pure `noticeMarks(seen, dismissed)` = per number the later mark,
    passed to `missedCallNotice` in place of the seen marks). The next missed call, Telecom's boot re-send and a restart
    don't bring it back.
  - The phone book's badges keep counting by the seen marks alone (`missedCallSummaries` unchanged). So the phone book
    still shows "Pappa 1". **For the user to confirm (Q3).** The other choice is `markSeen`, the contact sheet's own
    mark, which also clears the badge.
  - When the last contact goes, the recompute clears ours and marks the shown range read, the Telecom reset included,
    as the shade's swipe of ours does (`NoticeStep.Clear`).
  - This needs no listener: the row never springs back for want of one.
- **The plain missed-call row** (`number == null`: fail-closed, or unmanaged with our dialer role):
  `MissedCallNotifier.dismiss(context)`. It cancels ours and marks the shown range read, exactly the phone book's
  opening.
- The missed-call paths are asynchronous. Until `MissedCallNotifier.current` next changes, the panel leaves that row
  out (pure `withoutDismissed(rows, number, plain)`, held with the `current` it was dismissed under and compared by
  `===`), so the row doesn't flash back for a frame.
- **Spring-back on failure.** A `false` from `GlanceReader.dismiss` renders again: the row is rebound in place and comes
  back. Before animating out, the release already checks `GlanceReader.canDismiss`. If no listener is connected, the
  row springs back instead and nothing changes (`Glance: a row sprang back - no listener`). Nothing is lost either way:
  the notification is still in the shade.
- **Every `bindRows` resets each row view**: `translationX` 0, alpha 1, height `WRAP_CONTENT`, its margin, any running
  animator ended. The views are reused by index, so after the commit view i shows row i+1. These are the same pixels
  the collapse left on screen.
- **Logs**: "Glance: a row dismissed (message)" / "(missed call)" / "(missed calls)" - constant words. No package, key,
  name or number.

### 3.4 TalkBack

TalkBack's touch exploration takes the drag, so every row gets a custom action, "Fjern" / "Remove":
- the string `glance_dismiss_action` in both files;
- the id `glance_action_dismiss` in `ids_glance.xml`;
- set in a row-only delegate (`rowActions(view, clickLabel, row)`) that also carries today's click label. The timer
  tile keeps `openAction` (F7).

`performAccessibilityAction` for it commits at once, without the animation. It returns `false` when the commit can't
run (no listener for a message row). Focus stays on the view, which now reads the next row. No
`announceForAccessibility` (F8).

### 3.5 Why this is acceptable on a lock, and what doesn't change

- **The first thing on the lock that removes information without the PIN.** The music controls and the flashlight
  change state, but take nothing away. Design 25 kept rows undismissable so a stranger holding the phone can't hide a
  message from the kid. It is acceptable now for four reasons:
  - Android's own lock screen lets anyone swipe a notification away.
  - The rows are only the parent's ticked apps' conversations and our own missed calls.
  - A dismissal removes the notification, never the record: the message stays unread in its app, and the phone book's
    missed-call badge stays (Q3).
  - The user, who is the parent, asked for it.
- **Nothing else becomes dismissable**: not the music tile, the timer tile, the alarm line, the "more" line or the
  clock. There is still no notification action, remote input, snooze, mark-as-read of an app's message,
  `setNotificationsShown`, or content intent before the code.
- **The parent sees nothing new.** The server never saw the notifications. There is no status field, event or
  capability. The glance's cancels don't go through `NotificationRuleRuntime` (its `notification_cancels` report is the
  nag rule's alone). The PWA's description changes only if F2 is taken.
- **The pins that move** (`LockGlanceGuardTest`):
  - `nothing glance cancels...` becomes: no `cancelNotification(`, `cancelAllNotifications`, `snoozeNotification`,
    `setNotificationsShown` or `migrateNotificationFilter` in the glance's files; exactly one `cancelNotifications(`,
    in `GlanceReader.dismiss`, whose keys come from `GlanceStore.keysOf`.
  - The reader's public functions gain `dismiss` (guarded), and `readerIdentifiers` gains `cancelNotifications` and
    `toTypedArray`.
  - `the glance panel opens nothing` keeps every forbidden call but `dismiss`. The panel calls `GlanceReader.dismiss(`,
    `MissedCallNotifier.dismissContact(` and `MissedCallNotifier.dismiss(` exactly once each, all inside
    `commitDismiss`. `setOnTouchListener`, `ItemTouchHelper`, `SwipeDismiss` and `onSwiped` stay banned, and
    `GlanceSwipe`'s set stays `{GlanceSwipe.UNLOCK}`.
  - `allowedInterpolations` swaps the fit's (`${next.candidate}`, `${next.rows}`, `${next.more}`,
    `${next.visibleRows}`) for the layout's (`${layout.tile}`, `${layout.rows}`, `${layout.more}`).
  - The notifier pin `relearnOnly = !mayClear` becomes `relearnOnly = !mayClear || ownDismiss`.

## 4. Tests that move

- **`LockGlanceTest`**:
  - Go: the fit's tests (candidates in order, less room, the fade row R9C-1, `scrollShown`, the timer's seven) and
    `a session callback refits ...` (`glanceRefitDue` goes).
  - New:
    - `glanceLayout` (0 / 1 rows full, 2 and 8 compact, no session NONE, 9 -> 8 + 1 more, 20 -> 8 + 12, the timer kept,
      a negative count);
    - `glanceTouchAxis` (within the slop NONE, the tie vertical, each axis);
    - `glanceDismissStep` (39 / 40 %, both directions, a fling under 16 dp, a fling against the drag, not lifted);
    - `glanceDismissAlpha`;
    - `withoutDismissed`.
- **`MissedCallNoticeTest`**:
  - `noticeMarks`: a dismissed contact leaves the notice while `missedCallSummaries` with the seen marks still counts
    it;
  - a call after the mark shows again;
  - the prune at 14 days.
- **`LockGlanceLayoutTest`**:
  - The parts' model keeps its numbers (tile 174.8, compact 90, bottom 56, timer 114, row 56, "more" 24) in a local
    model with no `GlanceHeights`; the fade's 8 dp goes.
  - `the expected fits ...` (both, with the profile) and `the alarm line costs ...` become one `what scrolls at 473 and
    497 dp` over §1.2's table.
  - `the fit keys on the available height` becomes `the layout keys on the available height`: `available(`, the lock-up's
    `scrollTo(0, 0)`, the log line on a new layout or height.
  - `the order`: the root's children are `[lock_glance_scroll, lock_glance_bottom]`; the scroll is 0dp + weight 1 and
    holds `lock_glance_top` (wrap_content), whose last child is `lock_glance_list`; the 16 dp fade; the root's
    side padding 0, the top's 16 with `clipChildren`/`clipToPadding` false, the list and rows `clipChildren` false, the
    bottom block's 16 dp margins; the panel's `GONE` rule on `lockGlanceList`.
  - `nothing is squeezed` reads the content width from the top block's padding (288 dp at 320 as before).
- **`LockMediaGuardTest`**: unchanged. No new touchable in the XML; `swipeNow`'s one `step` and the `CANCEL_VIEWS`
  after the unlock's post still hold.
- **`LockFontScaleTest`**: the top block's `center_horizontal` stays.
- **`TranslationsTest`**: the new string.

## 5. Edge cases

- **320x569 dp**: §1.2's table. Music + 3 rows fits at 1.0. Four and more scroll.
- **Font scale 2.0**: the page scrolls. The tile stays full with 0 or 1 rows. The bottom block grows only when
  "Nødsamtale" wraps.
- **0 rows**: no list, the full tile, nothing scrolls at 1.0.
- **1 row**: the full tile.
- **2 rows**: the compact tile.
- **8 rows**: compact, a long scroll, no "more" line.
- **9 or more**: the "more" line counts the rest. A dismissal pulls the next one in.
- **No tile**: the rows 22 dp under the date (14 under the timer tile). §2's rule doesn't apply.
- **The alarm line**: always under the date while there is an alarm. 20.2 dp in the model.
- **The timer tile**: always full, above the rows. A running timer doesn't change §2.
- **The lock-up (`resumeNow`)**:
  - `scrollTo(0, 0)` as today;
  - a drag in progress springs back at once, no animation;
  - a dismissal already released commits at once (`animator.end()`).
  
  `stop()` does the same, so a screen-off mid-drag dismisses nothing. The system's `ACTION_CANCEL` at the screen-off
  springs it back first anyway.
- **The listener unbound** (no access, or between a disconnect and a reconnect): message rows spring back, missed-call
  rows work. A cancel that silently doesn't land (NLS returns quietly when unbound) leaves the notification in the shade
  and the row hidden on the lock until a newer post. Nothing is lost.
- **A newer message in the conversation during the drag**: not cancelled, and shows as the row again after the commit.
- **A group summary** left in the shade after its children were cancelled: the same as the shade's own swipe of each
  child. A device check (6), not handled.
- **Under Android's pad (ASK)**, while leaving, or under the VoIP card: no dismissal starts. A card arriving mid-drag
  springs the row back.

## 6. Emulator checks (for the implementer)

Run on `jelly36` (480x854 at 240 dpi = 320x569 dp, the clock profile), and for the centred clock on a no-profile AVD at
320x569 dp (or `jelly36` at `wm density 260`, which drops the profile). Use 3-button and gesture navigation, and take
the screenshots through the emulator console (FLAG_SECURE, `docs/testing/emulator.md` §6c).

1. **Scrolling**:
   - 0, 1, 2, 3, 4, 8 and 10 rows, with and without music, with an alarm, with a running timer: what fits doesn't
     move, and four or more rows scroll the clock away under the status bar;
   - the fade only at an edge with more;
   - "Nødsamtale" and the flashlight never move;
   - the next lock-up starts at the top, and Back from the keypad keeps the position.
2. **The unlock**:
   - with nothing to scroll, a swipe up on the clock unlocks;
   - with a scroll, it scrolls, and only the handle, the inset and the bottom row's empty sides unlock;
   - at the bottom end, a swipe up only glows;
   - a swipe from the handle always asks (own mode: our keypad; as the face: Android's pad).
3. **The rule**: 1 row gives the full tile, 2 the compact one; dismissing the second row brings the full tile back.
4. **Dismissal**:
   - left and right on an Element X row and a WhatsApp row: under 40 % it springs back, past it or with a quick fling
     it slides off the screen's edge and the list closes;
   - the notification is gone from the shade after the unlock, and the message is still unread in the app;
   - a diagonal start goes the way of its first movement;
   - a tap still opens the keypad with the hint;
   - a slow drag that ends vertical never dismisses.
5. **Missed calls**:
   - two contacts missed: dismissing Pappa's row leaves Mamma's;
   - the phone book still shows Pappa's badge;
   - a reboot (Telecom's re-send) and a new call from Mamma don't bring Pappa's row back;
   - a new call from Pappa does;
   - dismissing the last one clears the shade's notification;
   - the plain row (calls fail-closed) clears ours.
6. **The listener**:
   - with `adb shell cmd notification disallow_listener ...`, a message row springs back and a missed-call row still
     goes;
   - a WhatsApp group: whether its summary stays in the shade.
7. **Interruptions**:
   - power off mid-drag, then on: the row is back in place;
   - power off right after the lift: it is gone;
   - a VoIP ring mid-drag springs it back;
   - "Remove animations": no animation, the same results.
8. **TalkBack**: the page reads clock -> date -> tile -> rows -> "more" and scrolls along; a row's actions show "Fjern",
   which removes it; the handle's "Lås opp" asks; font scale 1.0 / 1.3 / 2.0.

## 7. Not in scope

- A pinned small clock (rejected, see the intro).
- RecyclerView or recycled rows, and every conversation without a cap.
- Scrolling to a row that arrives while the kid has scrolled (r9 §6, still out).
- Dismissing anything but a row.
- Dismissing from the keypad stage or under Android's pad.
- An undo, long-press menus, or a per-app "may be dismissed" setting.
- A report to the parent of what was dismissed.
- Cleaning up an app's group summary.
- Turning the bottom-end overscroll into an unlock.
- Home, the music card, the keypad.
- Any API or policy change (F2's text is the one possible server edit).

## 8. Decisions for the user (defaults in bold)

1. **The whole page scrolls with no fit left**: no compact last resort at font scale 2.0; the page scrolls instead.
2. **Eight rows at most**, then "+ N varsler til". The alternative is every conversation.
3. **A dismissed missed call leaves the lock and the shade, but the phone book keeps its badge**. The alternative is
   that it counts as seen and the badge goes too.
4. **The PWA's "Nothing can be answered or dismissed there" line is fixed in the same branch** (two lines in
   `server/`). The alternative is to leave it stale.

Also settled here, on lesser grounds (say if wrong): both swipe directions dismiss, the threshold is 40 % or a fling,
and a bottom-end swipe up stays an overscroll.

## 9. Size

One implementer, one branch, roughly 900-1100 changed lines, about half of them tests:
- `GlancePanel` (the fit removed, `layoutNow`, the dismiss state and animations in `swipeNow`, `commitDismiss`, the row
  actions): +300 / -170;
- `LockGlance.kt` (the fit out, `glanceLayout`, the axis, the dismiss step and alpha, `withoutDismissed`): +90 / -110;
- the layout's re-nesting;
- `GlanceReader.dismiss` and `GlanceStore.keysOf`/`hide`: about 40;
- `MissedCallNotifier.dismissContact` with the dismissed marks, plus `noticeMarks` in `MissedCallNotice.kt`: about 60;
- a string in two files and an id;
- the tests in §4.

Without the server lines in F2, it is launcher-only. One code QA round.

## As built (2026-10-24, branch `r11-glance`)

The user's §8 decisions (2026-10-24): (1) no fit at all, the page scrolls, font scale 2.0 included; (2) at most eight
rows, then "+ N varsler til"; (3) a dismissed missed call keeps the phone book's badge; (4) the PWA's line is fixed in
this branch. The note's own picks stand (both directions, 40 % or a fling, the bottom-end overscroll, the first move
past the slop decides, full tile with 0-1 rows, "Fjern", the spring-back without a listener). One commit per section:
§1 the scroller, §2 the tile rule, §3 the dismiss, then the PWA line and these docs.

**Deviations from the note** (each small, said in its commit):
- §1.2 `tileChanged` also sets the list's 14/22 dp margin (`placeList`) when a tile comes or goes - the margin under
  the tile is part of its coming and going. Nothing is refitted.
- §3.3 the rows are held back (`dismissBusy`) during the drag itself too, not only while the dismissal animates, so a
  post can't swap the row under the finger before the lift. A render held back runs at the commit or the spring-back's
  end (`flushRender`, also at the next DOWN).
- §3.2 a sideways first movement blocks the unlock only when the touch began inside the scroller. Outside it (the
  bottom row, the handle, the inset) the unlock's own rule (`glanceSwipeStep`: mostly vertical at every moment) is
  unchanged, so a horizontal gesture there still does nothing and the handle's swipe up always asks.
- §3.3 a dismissal's update of our missed-call notification never alerts (`step.alert && !ownDismiss`).
- §3.3 `withoutDismissed(rows, numbers: Set<String>, plain)` takes a set: two contacts dismissed before the notifier
  answers stay out together.
- F2: the PWA says "One can be swiped away, as on Android's own lock screen. Nothing can be answered there, and it
  can't be screenshotted." - the user's brief (a notification can be swiped away, as on Android's own, nothing can be
  answered) rather than the note's "a row can be swiped away".

**The pins that moved** (`LockGlanceLayoutTest`, `LockGlanceTest`, `LockGlanceGuardTest`):
- the fit's totals at 473/497 dp (the full tile alone 350.8, music + 1 row 420.8, music + 2 rows 462.4 / 484.8, music
  + 3 rows 406.4, no music + 4 rows 406, with the profile music + 1 full / + 2-3 candidate 3) -> §1.2's table: the
  content 278.8 / 348.8 / 328 (compact) / 392 / 456 / 712 / 438 dp against the scroller's 401 / 425 dp (the
  available 457 / 481 less the 56 dp bottom block); music + 4 scrolls 55 / 31 dp, + 8 311 / 287, no music + 5 37 / 13;
  the user's case (profile clock, alarm, music + 2, compact) 370.6 dp fits;
- the rows' scroll under candidate 3, 130.6 / 154.6 dp -> the whole page's scroller, 401 / 425 dp; the fade's 8 dp
  model part is gone (the fade is the scroller's 16 dp at an edge with more);
- `GLANCE_MAX_ROWS` 3 -> 8; `GLANCE_COMPACT_FROM_ROWS` = 2 (new);
- the root's side padding 16 -> 0 dp, `lock_glance_top`'s 0 -> 16 dp, the bottom block's side margins 0 -> 16 dp (the
  content width stays 288 dp at 320);
- unchanged: the full tile 174.8, compact 90, bottom block 56, timer 114, row 56, "more" 24, alarm line 20.2, clock
  78.4 and date 25.6 dp (the profile's 113.7 / 12.7);
- `LockGlanceGuardTest`: "nothing glance cancels" -> exactly one `cancelNotifications(`, in `GlanceReader.dismiss`, of
  `GlanceStore.keysOf`'s keys; the panel's ban on `dismiss` -> `GlanceReader.dismiss(`, `MissedCallNotifier.dismissContact(`
  and `MissedCallNotifier.dismiss(` once each inside `commitDismiss`; the log line's interpolations `${layout.tile}`,
  `${layout.rows}`, `${layout.more}`; the notifier's `relearnOnly = !mayClear || ownDismiss`.

Not emulator-checked yet: §6 (the coordinator runs it after the code QA).

### Fix round (qa-r11-glance-code Q-1..Q-10) and the user's two additions (2026-10-24)

- **Q-8, narrowed**: a sideways first move blocks the unlock for the gesture only when it starts on a row the drag
  couldn't take (`if (dismissRow != null) swipeBlocked = true`). On the clock, the date, the tiles and the gaps the
  unlock's own rule (`glanceSwipeStep`, mostly vertical at every moment) stands, as before r11: a lazy diagonal swipe
  up whose first 8 dp lean sideways still unlocks when the page can't scroll. §3.2's table rows "the rest of the
  scroller | horizontal" and "the bottom row, the handle | horizontal" now both read "nothing; the unlock's own rule".
- **Q-6, what a dismissal changes**: the row's notification(s), and for a contact's missed-call row its dismissed mark;
  and, for the plain row or the last contact, the call log's unread missed calls are marked read (every unread missed
  row up to the pass's newest, Telecom's count reset), as the shade's swipe of ours does. The phone book's badges don't
  depend on the `new` flag, so "Pappa 1" stays.
- **Q-1**: the rows are never rebound while a finger is down (`rowsHeld`), so a tap during the 300 ms slide/collapse
  clicks the row it pressed; a held render runs at the gesture's end. **Q-2**: "Fjern" is refused under the VoIP ring
  card, and the page is hidden from accessibility there (`covered`). **Q-3**: no `!ownDismiss` - a dismissal's pass
  alerts only for a genuinely new call, as every pass. **Q-5**: a missed-call row stays out until its own pass answered
  (a `done` callback). **Q-7**: a dismissal whose animation can't start commits at once. **Q-9**: a DOWN ends a
  spring-back; the row follows its travel past the slop. **Q-4/Q-10**: pins and leftovers (see the commit).
- **(A) Back at the top at every return** (the user: "må alltid komme til utgangspunktet igjen"): not only at a
  lock-up - also a step back onto the glance from our keypad or Android's pad (`glanceBackToTop`), a resume that is no
  lock-up (back from a call, the emergency dialer, an alarm), and the VoIP ring card going. A drag settles first. This
  replaces §1's "Back from the keypad keeps the position".
- **(B) The "more" line is tappable** (the user: a way to see the rest): a tap leads to the keypad as a row without a
  target does (`GlanceStageEvent.Row(null)`: the plain prompt, Home after the code; nothing captured), a 48 dp target
  (24 before; `LockGlanceLayoutTest`'s "more" part 24 -> 48 dp, only in the 9+ rows cases), "Lås opp" for TalkBack.
  It is still no row: never dismissable. This replaces design 25 §1.2's "not a button".
