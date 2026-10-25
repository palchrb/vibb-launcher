# Launcher round 13 - a time rule's card on the glance page, screen time left in the anchor notification (proposal)

Two approved items from PLAN.md's "Fix backlog": "A time rule shows as a card on the PIN lock" (PLAN.md:1289) and
"Screen time left in the shade" (PLAN.md:909). Item 1 is launcher only. Item 2 is launcher plus server: a new
parent-event kind and one device route, with its contract file in both directories in one commit. The policy is
unchanged. Mockup: `r13/mockup.html` in the session scratchpad. Copy it to `docs/design/r13-mockups/mockup.html` when
this note is approved. Its frames are A-E (the glance page), F-G (the shade) and H (the PWA).

## 0. Summary and the user's decisions

**Item 1 - the rule card.** While a time rule locks the phone, the lock's glance page stays exactly as it is: the
clock, the date, the alarm line, the notification rows, the music and timer tiles, the swipe up, "Nødsamtale" and the
torch. It gains **one card in the music tile's style**, directly under the date and alarm lines. The card shows the
kind's glyph (the time-rule screen's own `lock_glyph_*`), the label the time-rule screen shows ("Skole",
"Leggetid", "Skjermfri tid", or the rule's own name), and on a second line "Til 14:30", **only when that end is
certain**. The used-up budget shows the hourglass and "Skjermtiden er brukt opp", with no end. The card is information
only: no tap action, and TalkBack reads one text node. A pure model, built from the runtime's snapshot, feeds it. It
follows `TimeRulesRuntime.recheck`, which runs on the boundary alarm, a lift that arrives in a policy, the budget running
out, screen-on and a time change. It goes when the rule ends or is lifted. After the unlock, today's time-rule screen
(design 34 P6) comes up as before.

**Item 2 - the time left.** When a screen-time budget is set for today, the anchor's notification (the one Android
already forces, "Vibb One", `CommandListenerService.kt:111`) has the time left as its title: "1 t 20 min igjen i dag",
or "Skjermtiden er brukt opp i dag". It has **one action**, "Be om mer tid". The action queues a request that the next
sync posts to the server. The server raises the parent event `extra_time_request` (a kind design 35 reserved,
`35-parent-notifications.md:50`) and pushes it under its own Notify Me About switch, on by default. The inbox row opens
Device › Time at `#extra`, with the lift buttons in view. "Asked for more time at 14:02" shows there until a lift or
the end of the day. The answer is an ordinary lift, which reaches the phone in the policy as today. The notification's
text follows it. There is nothing new on the way back. Otherwise the notification stays the quiet "Vibb One": it is
never a second notification and never a status or control panel. Nothing else goes in the shade.

The user's decisions, as recorded in PLAN:

| # | Decision | Where |
|---|---|---|
| U1 | The glance page stays as it is, plus one card in the music tile's style: the kind's glyph and "Skolemodus til 14:30"; school, bedtime, custom and the used-up budget each with their own glyph and name | PLAN.md:1289 |
| U2 | The end time only when it is certain (`ClockFormat.formatUntil`, as the time-rule screen does) | PLAN.md:1293-1294 |
| U3 | For every lock reason; information only, no tap action; TalkBack reads it as text | PLAN.md:1297-1299 |
| U4 | It follows the lock's re-checks and goes when the rule ends or is lifted; unlocking still leads to the time-rule screen | PLAN.md:1294-1297 |
| U5 | No persistent launcher notification with status or controls ("helst ikke!") | PLAN.md:909 |
| U6 | With a budget set: the forced anchor notification "Vibb One" carries the time left today, optionally with one action "Be om mer tid" (a parent event + push, answered with a lift); never two notifications; nothing else in the shade | PLAN.md:910-915 |

What this note changes from PLAN's wording:
- PLAN's "the card is part of `glanceFit` (... it never scrolls)" predates r11, which removed the fit: the page
  scrolls whole and nothing is measured (`LockGlance.kt:533-541`). The card is one more fixed-size part of that page
  (§1.3).
- The wording "Skolemodus til 14:30" is §4 question 1. The default here uses the time-rule screen's label on line one
  and "Til 14:30" on line two, so the lock and the screen behind it say the same thing.

Decided in this note (the mockup shows them; veto at review):
- **The card counts toward the compact player's two** (`GLANCE_COMPACT_FROM_ROWS`, `LockGlance.kt:525`). The card
  plus one row makes the music tile compact, so the common budget-lock page (music plus one message) fits without a
  scroll (§1.3).
- **"Certain" is the phone's twin of the server's `time_state::provable_end`** (design 31 §3.10,
  `31-pwa-redesign.md:1142`). An end counts as certain only when the lock decision at that instant is "unlocked" (§1.1).
- **The rate:** at most one open request per phone, held for 30 min by the phone. The server tells at most 3 requests
  a day per phone; the rest are dropped with one log line (§2.6).
- **The event's name is `extra_time_request`**, the kind design 35 reserved, not the brief's `time_request`.

## 1. Item 1 - the rule card on the glance page

### 1.1 The model (pure, new `lock/RuleCard.kt`, `RuleCardTest`)

```kotlin
/** The card's kind: its glyph (`lockGlyph`, TimeLockModel.kt:18) and its fallback label. */
enum class RuleCardKind { SCHOOL, BEDTIME, RULE, SCREEN_TIME }

/** What the card says: [kind], [name] the rule's own name (blank = the kind's label), [until] the certain end. */
data class RuleCard(val kind: RuleCardKind, val name: String?, val until: LocalDateTime?)

/**
 * The card for the lock in force: null unless [reason] (the enforced `mdm.lockReason()`) and [lock] (the snapshot's
 * decision) both lock - the pref is what enforcement and the time-rule screen go by, the snapshot gives the details;
 * a moment where they disagree (a re-check in flight) shows no card. The strongest rule's kind and name
 * (`lock.rules.first()`), else SCREEN_TIME with no name and no end.
 */
fun ruleCard(reason: LockReason, lock: TimeLock, end: LocalDateTime?): RuleCard?

/**
 * The lock's certain end: [TimeLock.until] (the strongest rule's occurrence end, TimeRules.kt:186) only when the
 * decision at that instant - same policy, same rule lifts, same budget use - is unlocked. Covers the same rule's next
 * day starting at its end (an all-day or back-to-back window), a second rule running past it, and the budget used up
 * (SCREEN_TIME follows the rule). Null for SCREEN_TIME (the parent may add time; midnight is no promise either).
 */
fun lockEnd(policy: TimePolicy, lock: TimeLock, lifts: RuleLifts, budget: BudgetUse?): LocalDateTime?
```

`lockEnd` calls `decideTimeLock(policy, lock.until, overrideActive = false, lifts, budget)` (`TimeRules.kt:163`).
`RuleLifts` are the lifts in force now (`activeRuleLifts(snap.records, policy.lifts, snap.clocks)`,
`ScreenTime.kt:145`). A lift that runs out before `until` is a boundary of its own (`liftExpiries`), so the re-check at
that instant computes the card again. The model can be briefly optimistic before that boundary, never after it. The
budget is today's use, which doesn't grow under a rule except through exempt apps. When the budget runs out, the
re-check computes the card again (`ScreenTimeTracker.kt:43`).

Glue (in `GlancePanel`):
`ruleCard(mdm.lockReason(), snap.lock, lockEnd(...))` from `TimeRulesRuntime.snapshot(activity,
currentPolicyDecision().policy)` (`TimeRulesRuntime.kt:141`), the call `LockActivity.render` already makes on the main
thread (`LockActivity.kt:144`). It is computed only when `mdm.lockReason() != NONE` (one pref read otherwise). Before
the first unlock, CE storage can't be read and the snapshot throws or is empty: no card, logged by class only, as
every glance fault is (`GlancePanel.guarded`).

### 1.2 What the card shows (strings, glyphs)

| Lock | Glyph (Material Symbol, already in the repo) | Line 1 | Line 2 |
|---|---|---|---|
| school | `lock_glyph_school` (`menu_book`) | the rule's name, else `lock_kind_school` "Skole" / "School" | `lock_until` "Til 14:30" / "Until 2:30 PM", only with a certain end |
| bedtime | `lock_glyph_bedtime` (`bedtime`) | the name, else `lock_kind_bedtime` "Leggetid" / "Bedtime" | as above |
| custom (or unknown kind) | `lock_glyph_dot` (the hand-drawn dot) | the name, else `lock_kind_rule` "Skjermfri tid" / "Screen-free time" | as above |
| budget used up | `lock_glyph_hourglass` (`hourglass_bottom`) | `lock_title_screen_time` "Skjermtiden er brukt opp" / "Screen time is used up" | none |

- No new glyph and no change to `scripts/material-symbols.sh` (`LOCK_GLYPHS`, `scripts/material-symbols.sh:127`).
  The label is `LockActivity.kindLabel`'s rule (`LockActivity.kt:131`). Move it to a pure `ruleLabel(kind, name)` in
  `TimeLockModel.kt` so the two screens share it.
- The time comes from `ClockFormat.formatUntil(activity, until)` (`ClockFormat.kt:171`, the phone's 12/24 h setting
  with AM/PM). The panel's existing `ClockFormat.watch` (`GlancePanel.kt:375`) re-binds it on a format change.
- One new string: `glance_rule_card_description`, "%1$s, til %2$s" / "%1$s, until %2$s". With no end, the
  description is line 1 alone. Both `values` and `values-nb` (`TranslationsTest`).
- A long rule name takes one line and ends in "…"; the description keeps the whole name.

### 1.3 Layout and geometry (`view_lock_glance.xml`, `LockGlance.glanceLayout`)

**Where:** a new `lock_rule_card` in `lock_glance_top` right after `lock_glance_alarm`
(`view_lock_glance.xml:77`) and before `lock_media_tile` (`view_lock_glance.xml:94`). The order becomes: clock, date,
alarm line, **rule card**, full music tile, compact music tile, timer tile, the list. The card says why the phone is
locked, so it reads first. The tiles under it are what still works.

**Geometry**, next to the compact music tile (`view_lock_glance.xml:253`):

| | Compact music tile | Rule card |
|---|---|---|
| Margin above | 18 dp | 18 dp |
| Background | `bg_glance_compact_tile` (#1B1B28, 20 dp corners) | the same drawable |
| Padding | 10 dp | 12 dp |
| Leading element | 52 dp art, 28 dp glyph | 40 dp disc `bg_lock_kind_disc` (accent at 14 %, the time-rule screen's), 22 dp glyph tinted `kid_accent` (padding 9 dp) |
| Text | item 15 sp ExtraBold `kid_ink`, entry 13 sp Bold `kid_core_dim` | line 1 15 sp ExtraBold `kid_ink`, line 2 13 sp Bold `kid_core_dim`, 12 dp from the disc, one line each |
| Trailing | 48 dp peach play/pause | nothing |
| Height | 72 dp (90 with margin) | 12 + max(40, 21 + 18.2) + 12 = **64 dp (82 with margin)** in the test's 1.4 x model |

XML: horizontal `LinearLayout`, `match_parent`, `gravity="center_vertical"`, `visibility="gone"`,
`importantForAccessibility="yes"`, `focusable="true"` (a TalkBack stop), **never `clickable`**. The disc and the two
`TextView`s are `importantForAccessibility="no"`. The panel sets `contentDescription`. Line 2 goes `GONE` with no end,
and line 1 is centred against the disc. Not `setAccessibilityDataSensitive`: the label is the parent's own and no
third party's content.

**The layout rule** (`LockGlance.kt:531-551`): `GlanceLayout` gains `rule: Boolean`, and `glanceLayout(tile, rowCount,
timer, rule)` decides the music tile as **compact when `rows + (rule ? 1 : 0) >= GLANCE_COMPACT_FROM_ROWS`**, full
otherwise. Rows and "more" are unchanged. `placeList` (`GlancePanel.kt:1519`) gives the list 14 dp under a tile, the
timer **or the card**. 22 dp only with none of them.

What scrolls (the r11 §1.2 model; the scroller is 401 dp with 3-button navigation, 425 dp with gestures; clock + date =
104 dp):

| Content | Height | 401 / 425 |
|---|---|---|
| the card alone | 186 | fits |
| the card + 3 rows | 384 | fits |
| the card + 4 rows | 448 | scrolls 47 / 23 |
| the card + full music, 0 rows (frame B) | 360.8 | fits |
| the card + **compact** music + 1 row (frame D) | 346 | fits (full would be 430.8: scrolls 30 / 6) |
| the card + compact music + 2 rows | 410 | scrolls 9 / fits |
| frame D + the alarm line | 366.2 | fits |

At font scale 2.0 the page scrolls as r11 decided. Nothing shrinks.

**Touch:** the card is no control. A swipe up that starts on it unlocks as one from a row does, and a tap does nothing.
It stays out of `swipeControls` (`GlancePanel.kt:985`), and `LockMediaGuardTest`'s touchables
(`LockGlanceTest.kt:591`) are unchanged, because the card isn't clickable. It isn't dismissable (r11 §3.5's list gains
it).

### 1.4 Re-render: when the card comes, changes and goes

- **A listener on the runtime:** `TimeRulesRuntime.addLockListener` / `removeLockListener` (main thread). The
  listeners are called at the end of every `recheck` (`TimeRulesRuntime.kt:226`), after `reevaluateLockReasonFromCache`
  has written the reason. One hook covers all of these:
  - the boundary alarm (`TimeRuleAlarm.kt:64`, `TimeRuleReceiver`), so the rule's end or start lands at its minute;
  - every accepted policy (`MdmSyncWorker.kt:203-207`). A parent's lift or "End now" from the PWA arrives this way:
    nudge, SSE, sync, `recheck`;
  - the budget running out (`ScreenTimeTracker.kt:43`);
  - a time or zone change, boot, screen-on and user-present.
- **GlancePanel:** registers the listener in `start()` (`GlancePanel.kt:364`) and removes it in `stop()`
  (`GlancePanel.kt:447`). It computes the card model in `resumeNow()` (`GlancePanel.kt:394`) and in the listener, and
  keeps it (`ruleCard: RuleCard?`). When the model changed, it binds the card and runs the layout (`renderNow`, which
  places the list and the tiles by the new `glanceLayout`). `renderNow` (`GlancePanel.kt:1217`) binds the card from the
  kept model only, so a notification's re-render never recomputes a snapshot. A boundary that passes while the screen
  is off is picked up at the next `start`/`resumeNow`.
- **The page doesn't jump:** a card coming or going while the kid looks is ordinary layout in the scroller, as a tile
  coming or going is (r11 §1.2). No `backToTop`: the user's back-to-top rule (`glanceBackToTop`, `LockGlance.kt:268`)
  covers the face coming back, not content changing.
- **The time-rule screen** is unchanged except for §4 question 1 and question 2 (the shared label; `lockEnd` for its
  "Til" line, `LockActivity.kt:177`).

### 1.5 Modes

- **Own mode** (our keypad) and **android mode** (design 59: the glance is the face over Android's PIN pad, 59d step
  B/J) show the same page with the same card. Under Android's pad (ASK stage) the page stays drawn, card included, as
  today. The card adds no path, no action and no state change, so neither face is weakened (`LockGlanceGuardTest`
  unchanged).
- **Before the first unlock** (android mode's boot cover, design 59d step M): CE storage can't be read, so there is no
  card. The time rules aren't evaluated there anyway (`TimeRulesRuntime` is only used after the first unlock).
- **Kiosk off** (design 32's PIN lock without lock task): the same page, the same card.
- **The pause or the offline override:** `decideTimeLock` is unlocked, the reason is NONE, and there is no card.

### 1.6 Tests (lean)

- `RuleCardTest` (new, pure). `ruleCard`: each reason, its kind and name; NONE gives null; the pref and the snapshot
  disagreeing gives null. `lockEnd`: a plain window gives its end; the same rule's next day starting at the end
  (all-day every day, and a 22:00-07:00 window followed by a 07:00 start) gives null; two overlapping rules give null;
  adjacent rules (14:30 end, 14:30 start) give null; the budget used up under a rule gives null; SCREEN_TIME gives null;
  a lifted second rule running past the end gives the end. Also `ruleLabel` (name vs. kind label).
- `LockGlanceTest`: `glanceLayout` with `rule` (the compact threshold counts the card; nothing else moves).
- `LockGlanceLayoutTest` (`LockGlanceTest.kt:671`): `parts.rule = withMargins("lock_rule_card") == 82f`; the card's
  attributes (not clickable, `importantForAccessibility="yes"`, the disc 40 dp, `bg_glance_compact_tile`); §1.3's table
  rows in "what scrolls at 473 and 497 dp" (`LockGlanceTest.kt:892`), using `rowsTop` under the card; the order test
  (`LockGlanceTest.kt:1065`) with the card after the alarm line.
- `TranslationsTest` gets the one new string automatically.

### 1.7 Emulator checks (320x569 dp, nb and en, `docs/testing/emulator.md`; screenshots through the console because of `FLAG_SECURE`)

1. A school rule over now (PWA › Time › Own Rules). Lock: "Skole / Til HH:MM" with the book, under the date (frame A).
2. Music playing and the budget used up: the hourglass card, a compact tile with one message row (frame D), and the
   full tile with no row (frame B's layout).
3. From the PWA, Pause a Rule 15 min with the glance on screen: the card goes within seconds. "End now" brings it back.
4. A rule ending 2 minutes from now, with the screen on: the card goes at the minute (the alarm). With the screen
   off: gone at the next screen-on.
5. Two overlapping rules, and a rule whose next day starts at its end: no "Til" line, on the card or (with §4 Q2) the
   time-rule screen.
6. Font scale 2.0: the page scrolls and nothing is clipped. 12 h format: "Til 2:30 PM".
7. TalkBack: one stop, "Skole, til 14:30", no action announced. The swipe and the handle's "Lås opp" still unlock.
8. Android mode (Android's PIN set) and own mode: the same card. After the unlock, the time-rule screen as before.

## 2. Item 2 - screen time left in the anchor notification

### 2.1 Which notification

Design 19b's "Fewer persistent notifications" (PLAN.md:2145, `launcher/CLAUDE.md:97-114`) left one standing
notification: the anchor `CommandListenerService`'s foreground notification. It reads "Vibb One"
(`notification_listener_title`), has no body, sits on the MIN channel `launcher:command_listener`, and has id 1002
(`Notifications.kt:18-19`; built in `CommandListenerService.kt:161`, posted at `CommandListenerService.kt:111`).
`KidVpnService`'s shows only while the filter is starting or down. This item changes only the anchor's content. The
id, the channel and the icon stay, so it can never become a second notification.

### 2.2 What it says (pure, new `timerules/TimeLeftNotice.kt`, `TimeLeftNoticeTest`)

```kotlin
sealed interface TimeLeftText { data class Left(val minutes: Int) : TimeLeftText; data object UsedUp : TimeLeftText }
enum class AskAction { ASK, ASKED }
data class TimeLeftNotice(val text: TimeLeftText?, val action: AskAction?)   // (null, null) = plain "Vibb One"

fun timeLeftNotice(budget: BudgetUse?, windowOpen: Boolean, requestOpen: Boolean): TimeLeftNotice
fun leftMinutes(remainingMs: Long): Int = ceil(remainingMs / 60 000)
```

| State | Title | Action |
|---|---|---|
| No budget today (`budget?.effectiveMinutes == null`: no budget at all, or none on today's weekday) | "Vibb One" (as today) | none |
| The pause or the offline override (`windowOpen`) | "Vibb One" | none |
| A budget, time left | `anchor_time_left` "%1$s igjen i dag" / "%1$s left today", with `durationText(leftMinutes, …)` (`TimeLockModel.kt:30`): "1 t 20 min igjen i dag", "45 min igjen i dag", "2 t igjen i dag" | "Be om mer tid" / "Ask for more time" |
| A budget, used up (`remainingMs == 0`; an unreadable ledger counts as used up) | `anchor_time_used_up` "Skjermtiden er brukt opp i dag" / "Screen time is used up today" | as above |
| An open request (§2.4) | the same title | "Spurt" / "Asked": a tap does nothing |

- **Rounding up:** "1 min igjen i dag" shows until the last second, and "0 min" never shows. The budget lock and the
  title "brukt opp" arrive together.
- **A rule that locks** (school, bedtime) doesn't change the title or the action (§4 question 3). The budget is still
  true, and the parent decides on the Time page what to answer: extra minutes or a rule pause.
- **The title is the time line, with no body.** The header already says "Vibb One" (the app name), so a "Vibb One"
  title over a body would say it twice. Without a budget the title is "Vibb One", as today.
- The builder moves from `CommandListenerService.buildNotification` to `AnchorNotice.build(context, notice)` (new glue,
  `server/AnchorNotice.kt`): `setOnlyAlertOnce(true)`, `setSilent(true)`, `setShowWhen(false)`, `setOngoing(true)`,
  the same small icon and channel. `onCreate`'s first `startForeground` still runs first and unconditionally, with the
  notice computed then (a failure gives the plain one; launcher/CLAUDE.md's FGS rule).

### 2.3 When it updates (cadence)

`AnchorNotice.refresh(context, budget)` posts `notify(1002, …)` **only while the anchor runs**
(`CommandListenerService.running != null`). It posts nothing when the computed `TimeLeftNotice` equals the last one
posted (one field in `AnchorNotice`). Callers:

- **`ScreenTimeTracker.update`** (`ScreenTimeTracker.kt:82`), at its end, with the `BudgetUse` it just computed. It runs
  at screen on and off, at our activities' resume and pause, at every lock re-check, and at its checkpoint. **The
  checkpoint's delay becomes the next minute flip** while a budget runs: the pure `checkpointDelayMs(remainingMs)` =
  `((remainingMs - 1) mod 60 000) + 1 + 200 ms` while counting with time left, else the old 60 s (`CHECKPOINT_MS`,
  `ScreenTimeTracker.kt:19`). So the shown minute changes within 0.2 s of the real one. It ticks only while screen time
  counts (screen on, unlocked, not on a free screen), which is exactly when the number moves. There is no tick while
  the screen is off, on the PIN lock, or on the time-rule screen, and no extra timer. The ledger is still written at
  most about once a minute, as today.
- **`TimeRulesRuntime.recheck`**: through `ScreenTimeTracker.update`, its first line, so an accepted policy (a budget
  lift: more minutes, `applyBudgetLifts`), a changed budget, midnight and the pause starting or ending update the title.
- **The request's own changes** (§2.4): the tap, a lift that answers it (it arrives with a policy, so `recheck`), and the
  30 min running out. A one-shot main-thread `Handler` posted at the expiry, so "Spurt" doesn't outlive its 30 min
  while nothing counts (the time-rule screen is a free screen).

### 2.4 The action and its debounce

- **The PendingIntent:** `PendingIntent.getBroadcast(context, 0, Intent(context, TimeAskReceiver::class.java)
  .setAction(ACTION_ASK_TIME), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`. It names the receiver explicitly
  (launcher/CLAUDE.md's lesson about implicit internal broadcasts). The manifest entry is `exported="false"`.
  `Notification.Action.Builder(...).setAuthenticationRequired(true)` (API 31; minSdk is 34), so a tap from Android's
  keyguard asks for the unlock first. Nothing on a lock changes state without the code. Our own PIN lock has the status
  bar off while LOCKED (`LockTaskChrome`), so the shade isn't there at all.
- **The record** (CE prefs `time_request`, one `commit()`): `TimeRequestRecord(id: String /* 32 hex, SecureRandom */,
  askedAtMs: Long, day: String /* the ledger's day */, liftIdAtAsk: Long /* the largest lift id in the policy then */,
  sent: Boolean)`.
- **Pure `requestOpen(record, nowMs, day, maxLiftId)`**: open while the record is today's, younger than 30 min
  (`TIME_REQUEST_OPEN_MS`), and no lift newer than `liftIdAtAsk` has arrived. Lift ids only grow on the server
  (`ScreenTime.kt:22`). Any new lift (minutes or a rule pause) counts as the answer.
- **Pure `askStep(enrolled, budgetToday, windowOpen, open)`** gives `ASK` or `IGNORE`. The receiver asks only when
  enrolled, with a budget today, outside the pause, and with no request open. On `ASK` it writes a new record, calls
  `AnchorNotice.refresh` (the action reads "Spurt" at once) and `SyncRunner.request(context, "time_request")`. On
  `IGNORE` it does nothing: no log beyond one debug line, no toast, no sound.
- **The upload** (new `timerules/TimeRequests.kt`, `upload(context, api)` in `performMdmSync` right after
  `PowerEvents.upload`, `MdmSyncWorker.kt:332`; behind the sync mutex): an unsent record is posted. It is marked sent on
  a 2xx, and also on any 4xx except 401, 408 and 429, where the pure `timeRequestLeavesQueue(code)` mirrors
  `pinEventsLeaveQueue` (`ParentPinEvents.kt:48`) but drops on 404 too: an old server will never take it, and the
  request has a 30-minute life. A record older than 30 min is never sent; it lapses and the kid may ask again. The sync
  runs after `SyncRunner.request`, so with the network up the parent's push arrives within seconds.

### 2.5 The API (both directories in one commit)

`POST /api/devices/time-request` (in `device_routes.rs`, behind `require_device_token` like `/api/devices/power`,
`device_routes.rs:83`; body limit `TIME_REQUEST_BODY_LIMIT` = 1 KiB):

```json
{"id": "0123456789abcdef0123456789abcdef", "asked_at_ms": 1793034120000}
```

- The answer is 204 for taken, dropped as over the day's limit, or old news, so the phone never retries a drop. It is
  400 for a body that doesn't parse or an id that isn't 16-64 lowercase hex.
- **The contract file** is `server/testdata/time_request.json`, byte-identical in
  `launcher/app/src/test/resources/`. The launcher's `TimeLeftNoticeTest` encodes exactly it from a `TimeRequestBody`
  (`server/dto/TimeRequest.kt`). The server's `time_request::the_contract_body_raises_the_event` posts it, with its
  instant moved to a minute ago. Both compare the copies. Add it to `CLAUDE.md`'s shared-files paragraph, to
  `launcher.yml`'s path regex (`server/testdata/…time_request…`, `.github/workflows/launcher.yml:76`) and to
  `server-ci.yml`'s (`launcher/app/src/test/resources/time_request.json`, `.github/workflows/server-ci.yml:53`).
- **The policy is unchanged.** `policy_json_keys_snapshot` and `PolicyResponseCompatTest` don't move. Nothing new
  comes back to the phone: the parent's answer is a lift in `time_policy.lifts`, which already arrives.
- No status capability. The server never needs to know whether a phone can ask.

### 2.6 The server: the event, the rate, the push

- **The kind** (`parent_events.rs:36`): `Kind::ExtraTimeRequest`, wire `extra_time_request`, after `LowBattery` in
  `Kind::ALL` (`parent_events.rs:54`). Not urgent, urgency normal, TTL 3600 s (later it's old news; the inbox keeps it).
  `switch()` = `("push_extra_time", "Asks for more time")` (`parent_events.rs:113`). Notify Me About lists it from
  `Kind::switches()`, with no template change.
- **The migration:** `0095_extra_time_request.sql` is `ALTER TABLE notification_prefs ADD COLUMN push_extra_time INTEGER
  NOT NULL DEFAULT 1;`. On by default for every parent, existing ones included. `migrations_73_to_94_in_order`
  (`server/src/tests/music_spotify.rs:188`) becomes `…_to_95_…`.
- **Key and params, as design 64** (`zone_crossing`/`told`, `parent_events.rs:968`, `:1006`): a pure
  `parent_events::extra_time_request(device_id, phone, id, at, state, push) -> NewEvent`. The key is the phone's `id`,
  so a retried POST gives one row. `title`/`summary` are stored empty, and `params` holds `ExtraTimeParams { phone,
  at, state, left_minutes }`, where `state` is `"left"`, `"used_up"`, `"rule"` or `"unknown"`. It is taken from the
  server's own latest report for the phone (`device_status.time_state_json` / `lock_reason`; the sync posts its status
  report before the uploads, so it is fresh), never from text in the request.
- **The words** (`told`, English like every event, at read time, in the Phone Setup zone): title `fit_name(phone, 60,
  "{name} asks for more screen time")`. Summary "{At} · {state}", with state "1 h 20 min left", "Screen time used up",
  "School" / "Bedtime" / "A time rule" (`lock_reason`'s kind word, no rule name), or nothing for `unknown`. Examples:
  "17:21 · Screen time used up", "Yesterday 21:40 · Bedtime".
- **Target** `/devices/{id}/time#extra`.
- **The rate (the kid can't spam):** in the handler's `BEGIN IMMEDIATE` transaction, before `raise`, count the phone's
  `extra_time_request` rows with `happened_at` in the server zone's today and `dedup_key != id`. At
  `EXTRA_TIME_REQUESTS_A_DAY` = 3 the request is not stored: one `tracing::info!(device_id, "extra-time request
  dropped: 3 today already")`, then 204. The phone holds one request open per 30 min, so 3 a day is reached only by
  asking again and again across hours. The device route's existing per-device limits (`limits`) cover a flood.
- **Push only while it's news:** `Push::Now` when `asked_at` is under 30 min old at intake, else `Push::No` (inbox and
  Activity only). A request from before the server zone's today is dropped with a log line (its day is over). A future
  `asked_at` (a phone clock ahead) counts as the server's now.
- **Retention:** `EVENT_DAYS` (90), as every kind. It shows in the inbox, the bell and design 48's Activity with no
  extra code.

### 2.7 The PWA

- **The push and the inbox row** open `/devices/{id}/time#extra`: the Extra Time Today group with +15 min, +30 min
  and +1 h (`device_time.html:37`). Pause a Rule follows it directly.
- **"Asked for more time at 17:21"**: a `.group` with one `.row.row-note` (`data-asked`) at the top of `#extra`, above
  the buttons. It shows while a request is open on the server: the phone's newest `extra_time_request` row with
  `happened_at` in today (server zone), and no `time_lifts` row for the phone with `created_at` after that event's
  `created_at`. Both are server clocks; any lift counts, minutes or a rule pause. It is computed in
  `render_device_time` (`schedules.rs:1279`) by a small `parent_events::open_extra_time_request(db, id, &zone, now) ->
  Option<DateTime<Utc>>`.
- **Saves never jump to the top:** the lift forms already return to their group (`back=time` → `#extra` / `#pause`,
  `lifts.rs:41-52`). After a lift the page lands on `#extra`, with its flash and the "Asked" row gone.
- Nothing on the summary page, and no new setting beyond the Notify Me About switch.

### 2.8 Tests (lean)

- Launcher `TimeLeftNoticeTest` (new, pure):
  - `timeLeftNotice`'s table: no budget today, the pause, time left, used up, an unreadable ledger;
  - `leftMinutes`: 1 ms gives 1, 60 000 gives 1, 60 001 gives 2;
  - `checkpointDelayMs` (61 000 gives 1 200, 60 000 gives 60 200, no budget gives 60 000);
  - `requestOpen`: the day, the 30 min, a newer lift;
  - `askStep`;
  - `timeRequestLeavesQueue`;
  - the contract body encodes to exactly `time_request.json`, and the copies are identical.

  The strings go through `TranslationsTest`.
- Server:
  - `parent_events::tests`: the words for each `state`, and a long name cut to 60.
  - `src/tests/time_request.rs`:
    - the contract body gives one row and a push for the parent with `push_extra_time` on, none for the one with it off;
    - the same id twice gives one row;
    - the 4th of the day: no row, 204, the log line;
    - asked 31 min ago: a row, no push;
    - asked yesterday: no row;
    - a bad id gives 400;
    - the Time page shows "Asked for more time at …" in `#extra`, and after `POST /devices/{id}/lifts` (budget +15) it
      is gone and the redirect is `#extra`;
    - the contract copies are identical;
    - Notify Me About lists "Asks for more time".
  - `migrations_73_to_95_in_order`.

### 2.9 Emulator checks

1. Own Daily Limit 3 min today. The shade shows one Vibb notification, "3 min igjen i dag". With Home in front it
   steps down at each minute. With the screen off or the PIN lock up it doesn't move. `adb shell dumpsys notification
   --noredact | grep me.vibb.launcher` shows one record, id 1002.
2. Used up: the title is "Skjermtiden er brukt opp i dag" and the time-rule screen is up. The shade is reachable there
   (kiosk's notifications feature as the server sets it). Check whether the action shows without expanding. If the
   silent section collapses it, the kid expands it; note what Android 15/16 does.
3. Tap "Be om mer tid": the PWA's inbox has "… asks for more screen time / HH:MM · Screen time used up" within
   seconds, plus a push (`docs/testing/emulator.md`'s Web Push check). The action reads "Spurt", and a second tap does
   nothing.
4. In the PWA, open the event: it lands on Time › Extra Time Today with "Asked for more time at HH:MM". Tap +15 min:
   the page stays at `#extra` and the "Asked" row is gone. On the phone, "15 min igjen i dag" and "Be om mer tid" are
   back.
5. Asking four times in a day (waiting out the 30 min, or `adb shell date` in a debug build): three events; the server
   log has one "dropped" line.
6. Airplane mode, tap, back online within 30 min: the event arrives. After 30 min offline: nothing is sent, and the
   action is back.
7. No limit for today (an empty weekday): plain "Vibb One". The 2-hour pause: plain "Vibb One".
8. Android mode with Android's lock screen showing notifications: the action asks for the unlock first.

## 3. Implementation steps

| Step | What | Size |
|---|---|---|
| 1 (launcher) | Item 1: `lock/RuleCard.kt` + `RuleCardTest`; `ruleLabel` in `TimeLockModel.kt`; `lock_rule_card` in `view_lock_glance.xml`; `glanceLayout(…, rule)`; `TimeRulesRuntime` lock listeners; `GlancePanel` (model, bind, listener, `placeList`); the string; `LockGlanceTest`/`LockGlanceLayoutTest` updates; launcher/CLAUDE.md's glance paragraph. With §4 Q2 "yes": `LockActivity.kt:177` uses `lockEnd`. Emulator §1.7. | S-M |
| 2 (both directories, one commit for the contract) | Server: `Kind::ExtraTimeRequest`, migration 0095, `extra_time_request` + `told`, `POST /api/devices/time-request` with the rate and the news rule, the Time page's "Asked" row, `src/tests/time_request.rs`, `docs/notes/server/notifications.md` and `pwa.md`. Launcher: `server/dto/TimeRequest.kt`, `MdmApi.sendTimeRequest`, the contract copy and its encode test. `server/testdata/time_request.json` + copy, CI path regexes, CLAUDE.md's shared-files paragraph. Server release (0.49.0) before the launcher with step 3. | S-M |
| 3 (launcher) | Item 2: `timerules/TimeLeftNotice.kt` + `TimeLeftNoticeTest`; `server/AnchorNotice.kt` (builder, refresh, the expiry handler); `TimeAskReceiver` (manifest, not exported); `timerules/TimeRequests.kt` (record, upload in `performMdmSync`); `ScreenTimeTracker`'s checkpoint delay and refresh; the strings; launcher/CLAUDE.md's "Notifications in the shade" paragraph. Emulator §2.9. | M |

Each step goes through the usual process: design QA on this note, implementation, code QA, then all of the fix round.
Step 1 is independent of 2 and 3 and can run in parallel with them in its own worktree. Steps 2 and 3 are in order.
PLAN's two backlog entries are marked done with their branches.

Not in scope: a "Be om mer tid" button on the time-rule screen or the glance; requests without a budget (for example
during bedtime with no daily limit); the summary page's Quick actions showing the request; Norwegian PWA words; any
change to the music app.

## 4. Open questions for the user

1. **The card's words.** Line one is the time-rule screen's label: the rule's own name, else "Skole" / "Leggetid" /
   "Skjermfri tid". Line two is "Til 14:30" (frames A-C). The alternative is the one-line "Skolemodus til 14:30" from
   your example, with new kind words ("Skolemodus", "Leggetidsmodus", …) that override the rule's name.
   **Recommended default: the time-rule screen's label, on two lines.** The lock and the screen behind it then say the
   same thing, and a parent's own rule name ("Fotballtrening") shows as typed.
2. **The certain end on the time-rule screen too.** Today it shows "Til 14:30" from the strongest rule's end even when
   the phone stays locked at 14:30 (a second rule, the next day's window, the budget used up). Should it use the card's
   `lockEnd`, a one-line change at `LockActivity.kt:177`, so the two never disagree? **Recommended default: yes.**
3. **"Be om mer tid" while a rule locks.** With a budget set, school or bedtime may lock while time is left. Show the
   action anyway (the request then says "Bedtime" and the parent may pause the rule or add minutes), or hide it while
   a rule locks and keep it for the budget alone? **Recommended default: show it.** The parent decides on the Time
   page, and a no is just no lift.
