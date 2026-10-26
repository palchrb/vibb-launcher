# Launcher round 13 - a time rule's card on the glance page, screen time left in the anchor notification (proposal, revised after `qa-r13-design.md`)

Two approved items from PLAN.md's "Fix backlog": "A time rule shows as a card on the PIN lock" (PLAN.md:1289) and
"Screen time left in the shade" (PLAN.md:909). Item 1 is launcher only. Item 2 is launcher plus server: a new
parent-event kind and one device route, with its contract file in both directories in one commit. The policy doesn't
change. Mockup: `r13/mockup.html` in the session scratchpad (copy it to `docs/design/r13-mockups/mockup.html` when this
note is approved). Frames A-E are the glance page, F-G the shade, H the PWA. The design QA's findings are folded into
the text below and listed under "QA fixes".

## 0. Summary and decisions

**Item 1 - the rule card.** While a time rule locks the phone, the lock's glance page stays exactly as it is: the
clock, the date, the alarm line, the notification rows, the music and timer tiles, the swipe up, "Nødsamtale" and the
torch. It gets **one more card in the music tile's style**, directly under the date and alarm lines. Line 1 is the
label the time-rule screen shows: the rule's own name, else "Skole", "Leggetid" or "Skjermfri tid". Line 2 is "Til
14:30", shown **only when that end is certain**. The card carries the kind's glyph (the time-rule screen's own
`lock_glyph_*`). The used-up budget shows the hourglass and "Skjermtiden er brukt opp", with no end. The card is
information only: no tap action, and TalkBack reads one text node. A pure model, fed by the snapshot that
`TimeRulesRuntime.recheck` already computes, decides what it says. So the card follows the boundary alarm, a lift
arriving in a policy, the budget running out, screen-on and a time change, and it goes when the rule ends or is lifted.
After the unlock, today's time-rule screen (design 34 P6) comes up as before. The time-rule screen's "Til" line uses
the same certain end.

**Item 2 - the time left.** When a screen-time budget is set for today, the anchor's notification (the one Android
already forces, "Vibb One", `CommandListenerService.kt:111`) gets the time left as its title: "1 t 20 min igjen i dag",
or "Skjermtiden er brukt opp i dag". It has **one action**, "Be om mer tid". The action queues a request, and the next
sync posts it. The server raises the parent event `extra_time_request` (the kind design 35 reserved,
`35-parent-notifications.md:50`) and **sends a web push** to every parent who has its **own switch under Notifications ›
Notify Me About** on. The switch is per parent and on by default (the user, 2026-10-25). The inbox row opens Device ›
Time at `#extra`, with the lift buttons in view. "Asked for more time at 14:02" shows there until a lift or the end of
the day. The answer is an ordinary lift, which reaches the phone in the policy as today. The notification's text
follows it, and nothing new comes back. Otherwise it stays the quiet "Vibb One": never a second notification, never a
status or control panel, and nothing else of ours in the shade.

The user's decisions, as recorded in PLAN:

| # | Decision | Where |
|---|---|---|
| U1 | The glance page stays as it is, plus one card in the music tile's style: the kind's glyph and "Skolemodus til 14:30"; school, bedtime, custom and the used-up budget each with their own glyph and name | PLAN.md:1289 |
| U2 | The end time only when it is certain (`ClockFormat.formatUntil`, as the time-rule screen does) | PLAN.md:1293-1294 |
| U3 | For every lock reason; information only, no tap action; TalkBack reads it as text | PLAN.md:1297-1299 |
| U4 | It follows the lock's re-checks and goes when the rule ends or is lifted; unlocking still leads to the time-rule screen | PLAN.md:1294-1297 |
| U5 | No persistent launcher notification with status or controls ("helst ikke!") | PLAN.md:909 |
| U6 | With a budget set: the forced anchor notification "Vibb One" carries the time left today, optionally with one action "Be om mer tid" (a parent event + push, answered with a lift); never two notifications; nothing else in the shade | PLAN.md:910-915 |
| U7 | The request MUST send a web push and MUST have its own switch under Notifications › Notify Me About, per parent, on by default | the user, 2026-10-25 |

**Decided** (by the coordinator on the user's behalf, 2026-10-25, after the design QA; these were §4's questions and
the QA's R13-4 and R13-7):

- **D1 - the card's words (was Q1).** Two lines. Line 1 is the time-rule screen's label: the rule's own name, else
  "Skole" / "Leggetid" / "Skjermfri tid". Line 2 is "Til 14:30". The lock and the screen behind it say the same
  thing, and a parent's own rule name ("Fotballtrening") shows as typed. The user's "Skolemodus til 14:30" was the
  example of the idea, not new kind words.
- **D2 - the certain end on the time-rule screen too (was Q2).** `LockActivity.kt:177` uses the card's `lockEnd`
  instead of `lock.until`, so the two never disagree. This restores what PLAN assumed ("as on the time-rule screen").
- **D3 - "Be om mer tid" while a rule locks (was Q3).** It shows. With a budget set today, the action is there under
  school or bedtime too. The request says "Bedtime", and the parent may pause the rule, add minutes or do nothing.
- **D4 - the compact player (R13-4).** The card does **not** count toward the compact player's two notifications
  (`GLANCE_COMPACT_FROM_ROWS`, `LockGlance.kt:524-525`, the user's "ved 2 notifications"). It is treated like the timer
  tile. The card, the full tile and one row come to 430.8 dp and scroll about 30 dp (3-button) or 6 dp (gestures)
  (as built 2026-10-26, the full tile 12 dp lower: 418.8 dp, about 18 dp with 3-button, none with gestures).
  That is accepted, as r11 accepted a scroll for the glance ("no fit at all").
- **D5 - the daily cap (R13-7).** At most 3 requests a day per phone, enforced on the phone as well as the server.
  After the third accepted request of the day, the action is gone until midnight (the phone's own count, DE prefs).
  The server's cap stays as the backstop: it answers a capped request with 200 `{"accepted":false,"reason":"capped"}`.
  The phone shows "Spurt" only after `{"accepted":true}`. An old server's 404 shows nothing, and the action stays.
- **D6 - the migration number (R13-1).** `0096_extra_time_request.sql`, with the order test renamed
  `migrations_73_to_96_in_order`. 0095 is taken by `pwa-fixes-a` (`0095_push_session_epoch.sql`). Whichever branch
  merges second renumbers.

What this note changes from PLAN's wording: PLAN's "the card is part of `glanceFit` (... it never scrolls)" predates
r11, which removed the fit. The page scrolls whole and nothing is measured (`LockGlance.kt:533-541`), so the card is one
more fixed-size part of that page (§1.3). The event is named `extra_time_request`, the kind design 35 reserved, not the
brief's `time_request`.

## 1. Item 1 - the rule card on the glance page

### 1.1 The model (pure, new `lock/RuleCard.kt`, `RuleCardTest`)

```kotlin
/** The card's kind: its glyph (`lockGlyph`, TimeLockModel.kt:18) and its fallback label (`ruleLabel`, §1.2). */
enum class RuleCardKind { SCHOOL, BEDTIME, RULE, SCREEN_TIME }

/** What the card says: [kind], [name] the rule's own name (blank = the kind's label), [until] the certain end. */
data class RuleCard(val kind: RuleCardKind, val name: String?, val until: LocalDateTime?)

/**
 * The card for the lock in force: null unless [reason] (the enforced `mdm.lockReason()`) and [lock] (the snapshot's
 * decision) both lock - the pref is what enforcement and the time-rule screen go by, the snapshot gives the details;
 * a moment where they disagree shows no card. The strongest rule's kind and name (`lock.rules.first()`), else
 * SCREEN_TIME with no name and no end.
 */
fun ruleCard(reason: LockReason, lock: TimeLock, end: LocalDateTime?): RuleCard?

/**
 * The lock's certain end, or null. [TimeLock.until] (the strongest rule's occurrence end, TimeRules.kt:186) when all of:
 * - it is a single instant in [zone] (not in a DST gap or overlap: `zone.rules.getValidOffsets(until).size == 1`);
 * - the decision at `until` is unlocked, with the policy, the rule lifts in force *at* `until` (a lift counts only if
 *   its expiry, `liftExpiries`, is at or after `until`) and the budget of `until`'s own day: today's [budget] when
 *   `until.toLocalDate() == ledgerDay`, else `BudgetUse(0, budgetMinutesFor(policy, until.toLocalDate()), 0)` (a
 *   budget lift applies to the day the phone first sees it, so the next day starts with none).
 * Null for SCREEN_TIME (the parent may add time; midnight is no promise either).
 */
fun lockEnd(
    policy: TimePolicy, lock: TimeLock, liftsAtEnd: RuleLifts, budget: BudgetUse?, ledgerDay: LocalDate?, zone: ZoneId,
): LocalDateTime?
```

Calling `decideTimeLock(policy, until, overrideActive = false, liftsAtEnd, budgetAtEnd)` (`TimeRules.kt:163`) catches
several cases: the same rule's next day starting at its end (an all-day or back-to-back window), a fail-closed 7-entry
rule, an adjacent or overlapping second rule (`Span.contains` is start-inclusive, `TimeRules.kt:71`), and the budget at
the end's day. Bedtime 22:00-07:00 with today's budget used up still says "Til 07:00", since 07:00 is tomorrow and
tomorrow's budget is fresh. With tomorrow's budget 0 (`budgetMinutesFor`, `TimeRules.kt:116`), the phone locks again at
07:00 as SCREEN_TIME, so there is no end. `liftsAtEnd` is built in the glue from the snapshot's records:
`activeRuleLifts(records, policy.lifts, clocks)` (`ScreenTime.kt:145`), less every rule lift whose expiry instant is
before `until`.

What each side checks. The server's `time_state::provable_end` (design 31 §3.10, `31-pwa-redesign.md:1142`,
`server/src/time_state.rs:309`, `:326`) gives an end only when all of these hold:
- the report is fresh and in Phone Setup's zone;
- the report's time is inside one window of the active rule;
- no effective rule runs or starts at the end;
- the screen time isn't used up;
- the end is a single instant.

The phone has the full decision at hand, so `lockEnd` asks it directly at the end, with the end day's budget and the
lifts still in force then, and adds the same single-instant check. Neither side shows an end the other would refuse
for overlap, adjacency, the next day's window, the budget or DST. The server is stricter about a stale report.

Glue (in `GlancePanel`, §1.4): `ruleCard(mdm.lockReason(), snap.lock, lockEnd(...))` from a
`TimeRulesRuntime.Snapshot` (`TimeRulesRuntime.kt:130`). It runs only when `mdm.lockReason() != NONE`, so in the
common case it costs one pref read.

### 1.2 What the card shows (strings, glyphs)

| Lock | Glyph (Material Symbol, already in the repo) | Line 1 | Line 2 |
|---|---|---|---|
| school | `lock_glyph_school` (`menu_book`) | the rule's name, else `lock_kind_school` "Skole" / "School" | `lock_until` "Til 14:30" / "Until 2:30 PM", only with a certain end |
| bedtime | `lock_glyph_bedtime` (`bedtime`) | the name, else `lock_kind_bedtime` "Leggetid" / "Bedtime" | as above |
| custom (or unknown kind) | `lock_glyph_dot` (the hand-drawn dot) | the name, else `lock_kind_rule` "Skjermfri tid" / "Screen-free time" | as above |
| budget used up | `lock_glyph_hourglass` (`hourglass_bottom`) | `lock_title_screen_time` "Skjermtiden er brukt opp" / "Screen time is used up" | none |

- No new glyph, and no change to `scripts/material-symbols.sh` (`LOCK_GLYPHS`, `scripts/material-symbols.sh:127`).
- **The label** is `LockActivity.kindLabel`'s rule (`LockActivity.kt:131`), moved to a pure
  `ruleLabel(kind: String?, name: String): RuleLabel` in `TimeLockModel.kt`. It returns `RuleLabel.Named(name)` or
  `RuleLabel.Kind(RuleCardKind)`, never a string from resources. `LockActivity` and `GlancePanel` resolve a `Kind`
  with `getString`, so both screens share one rule.
- The time is `ClockFormat.formatUntil(activity, until)` (`ClockFormat.kt:171`; the phone's 12/24 h setting, with
  AM/PM). The panel's existing `ClockFormat.watch` (`GlancePanel.kt:375`) re-binds it on a format change.
- **One new string**: `glance_rule_card_description` "%1$s, til %2$s" / "%1$s, until %2$s". With no end the
  description is line 1 alone. It goes in both `values` and `values-nb` (`TranslationsTest`).
- A long rule name takes one line and ends in "…". The description keeps the whole name.

### 1.3 Layout and geometry (`view_lock_glance.xml`, `LockGlance.glanceLayout`)

**Where:** a new `lock_rule_card` in `lock_glance_top`, right after `lock_glance_alarm` (`view_lock_glance.xml:77`) and
before `lock_media_tile` (`view_lock_glance.xml:94`). The order becomes: clock, date, alarm line, **rule card**, full
music tile, compact music tile, timer tile, the list. The card says why the phone is locked, so it reads first. The
tiles under it are what still works.

**Geometry**, next to the compact music tile (`view_lock_glance.xml:253`):

| | Compact music tile | Rule card |
|---|---|---|
| Margin above | 18 dp | 18 dp |
| Background | `bg_glance_compact_tile` (#1B1B28, 20 dp corners) | the same drawable |
| Padding | 10 dp | 12 dp |
| Leading | 52 dp art, 28 dp glyph | 40 dp disc `bg_lock_kind_disc` (the accent at 14 %, the time-rule screen's), a 22 dp glyph tinted `kid_accent` (padding 9 dp) |
| Text | item 15 sp ExtraBold `kid_ink`, entry 13 sp Bold `kid_core_dim` | line 1 15 sp ExtraBold `kid_ink`, line 2 13 sp Bold `kid_core_dim`, 12 dp from the disc, one line each |
| Trailing | 48 dp peach play/pause | nothing |
| Height | 72 dp (90 with margin) | 12 + max(40, 21 + 18.2) + 12 = **64 dp (82 with margin)** in the test's 1.4 x model |

**XML:** a horizontal `LinearLayout`, `match_parent`, `gravity="center_vertical"`, `visibility="gone"`,
`importantForAccessibility="yes"`, `screenReaderFocusable="true"` (a TalkBack stop without a keyboard focus stop),
**never `clickable`**. The disc and both `TextView`s are `importantForAccessibility="no"`. The panel sets
`contentDescription`. Line 2 goes `GONE` with no end, and line 1 centres against the disc. It is not
`setAccessibilityDataSensitive`: the label is the parent's own, not a third party's content.

**The layout rule** (`LockGlance.kt:531-551`): `GlanceLayout` gains `rule: Boolean`, and the signature becomes
`glanceLayout(tile, rowCount, timer, rule)`. **The music tile's size is unchanged** (D4): full with 0 or 1 bound rows,
compact from `GLANCE_COMPACT_FROM_ROWS`. Only rows count; the card doesn't, and neither does the timer tile. The rows
and "more" are unchanged. `placeList` (`GlancePanel.kt:1519`) puts the list 14 dp under a tile, the timer **or the
card**, and 22 dp under the date only when none of them is there.

What scrolls (r11 §1.2's model: the scroller is 401 dp with 3-button navigation, 425 dp with gestures; clock + date =
104 dp):

| Content | Height | 401 / 425 |
|---|---|---|
| the card alone (frames C, E) | 186 | fits |
| the card + 1 row (frame A) | 256 | fits |
| the card + 3 rows | 384 | fits |
| the card + 4 rows | 448 | scrolls 47 / 23 |
| the card + full music, 0 rows (frame B) | 360.8 | fits |
| the card + **full** music + 1 row (frame D) | 430.8 | scrolls 29.8 / 5.8 (accepted, D4) |
| the card + compact music + 2 rows | 410 | scrolls 9 / fits |
| frame D + the alarm line | 451 | scrolls 50 / 26 |

As built 2026-10-26 (branch `launcher-glance-c`): the full tile is 162.8 dp (12 dp lower), so the card + full music is
348.8, frame D 418.8 (scrolls 17.8 / fits) and frame D + the alarm line 439 (scrolls 38 / 14); the rest is unchanged.

At font scale 2.0 the page scrolls as r11 decided; nothing shrinks.

**Touch:** the card is no control. A swipe up that starts on it unlocks, as one from a row does, and a tap does
nothing. It stays out of `swipeControls` (`GlancePanel.kt:985`), and `LockMediaGuardTest`'s touchables
(`LockGlanceTest.kt:591`) are unchanged because it isn't clickable. It isn't dismissable (r11 §3.5's list gains it).

### 1.4 Re-render: when the card comes, changes and goes

- **One snapshot per re-check, handed to the listeners.** `TimeRulesRuntime.recheck` (`TimeRulesRuntime.kt:226`)
  computes the snapshot once. Today `reevaluateLockReasonFromCache` (`MdmSyncWorker.kt:1200`) builds its own through
  `currentLock`; it now takes that snapshot as a parameter. After the reason and key are written, `recheck` calls
  `TimeRulesRuntime` lock listeners (`addLockListener` / `removeLockListener`, main thread) with the snapshot. **The
  listeners run before `MissedCallNotifier.relearn`** (`TimeRulesRuntime.kt:242`). That way the panel's `changed`, which
  `relearn` may fire, renders with the new card already kept, and the page is laid out once, not twice.
- **This one hook covers every source:**
  - the boundary alarm (`TimeRuleAlarm.kt:64`), so a rule's end or start lands at its minute;
  - every accepted policy (`MdmSyncWorker.kt:203-207`): a parent's lift or "End now" from the PWA comes this way (nudge,
    SSE, sync, `recheck`);
  - the budget running out (`ScreenTimeTracker.kt:43`);
  - a time or zone change, boot, screen-on and user-present.
- **Why not the existing preference listener.** `LockActivity` already listens to `lockReason`/`lockKey`
  (`LockActivity.kt:53-58`), but `TimeLock.key()` (`TimeRules.kt:305`) carries neither `budgetExhausted` nor `until`.
  A budget that also runs out under a rule, or a second rule that moves the end, would change the card without
  touching the key.
- **GlancePanel:**
  - It registers the listener in `start()` (`GlancePanel.kt:364`) and removes it in `stop()` (`GlancePanel.kt:447`).
  - The listener computes the model from the snapshot it is handed. `resumeNow()` (`GlancePanel.kt:394`) computes one
    snapshot of its own, the time-rule screen's call (`TimeRulesRuntime.snapshot(activity,
    currentPolicyDecision().policy)`, `LockActivity.kt:144`), only when `lockReason != NONE`.
  - The panel keeps the result (`ruleCard: RuleCard?`). When the model changed it binds the card and runs `renderNow`,
    which places the list and the tiles by the new `glanceLayout`. `renderNow` (`GlancePanel.kt:1217`) binds only from
    the kept model, so a notification's re-render never computes a snapshot.
  - **The card's computation has its own `try/catch` inside `resumeNow`**, as the rows have inside `renderNow`.
    `guarded` (`GlancePanel.kt:1541`) wraps the whole entry point, so without it a throw would skip the stage decision
    and the render. A fault means no card, logged by its class only.
  - A boundary that passes while the screen is off is picked up at the next `start`/`resumeNow`.
- **The page doesn't jump.** A card coming or going while the kid looks is plain layout in the scroller, like a tile
  coming or going (r11 §1.2). There is no `backToTop`: the user's back-to-top rule (`glanceBackToTop`,
  `LockGlance.kt:268`) is about the face coming back, not about content changing.
- **The time-rule screen** gets the shared label (D1) and `lockEnd` for its "Til" line (D2, `LockActivity.kt:177`),
  computed from its own snapshot with the same glue helper. Nothing else changes there.

### 1.5 Modes

- **Own mode** (our keypad) and **android mode** (design 59: the glance is the face over Android's PIN pad, 59d step
  B/J) show the same page with the same card. Under Android's pad (the ASK stage) the page stays drawn, card included,
  as today. The card adds no path, no action and no state change, so neither face is weakened (`LockGlanceGuardTest`
  unchanged).
- **Before the first unlock:** the glance never runs. `PinLockActivity` isn't `directBootAware`; only
  `BootCoverActivity`, in `:bootcover`, is. So there is nothing to show and nothing to handle.
- **Kiosk off** (design 32's PIN lock without lock task): the same page and the same card.
- **The pause or the offline override:** `decideTimeLock` is unlocked and the reason is NONE, so there is no card.

### 1.6 Tests (lean)

- **`RuleCardTest`** (new, pure).
  - `ruleCard`: each reason gives its kind and name; NONE gives null; the pref and the snapshot disagreeing gives null.
  - `lockEnd`:
    - a plain window gives its end;
    - null for each of these: the same rule's next day starting at the end (all-day every day, and a 22:00-07:00 window
      followed by a 07:00 start); two overlapping rules; adjacent rules (14:30 end, 14:30 start); today's budget used up
      under a daytime rule; SCREEN_TIME;
    - **bedtime 22:00-07:00 with today's budget used up gives 07:00**, and **tomorrow's budget 0 gives null** (R13-2);
    - **an end in the spring-forward gap gives null** (Europe/Oslo, the last Sunday of March, 02:30);
    - a lifted second rule running past the end: its lift expiring after the end gives the end, and **expiring before
      the end gives null** (R13-6).
  - `ruleLabel`: name vs. kind.
- **`LockGlanceTest`**: `glanceLayout` with `rule` - the compact threshold still counts rows alone, and nothing else
  moves.
- **`LockGlanceLayoutTest`** (`LockGlanceTest.kt:671`):
  - `parts.rule = withMargins("lock_rule_card") == 82f`;
  - the card's attributes: not clickable, `importantForAccessibility="yes"`, `screenReaderFocusable="true"`, the disc
    40 dp, `bg_glance_compact_tile`;
  - §1.3's table rows in "what scrolls at 473 and 497 dp" (`LockGlanceTest.kt:892`), with `rowsTop` under the card;
  - the order test (`LockGlanceTest.kt:1065`), with the card after the alarm line.
- **`TranslationsTest`** picks up the one new string.

### 1.7 Emulator checks (320x569 dp, nb and en, `docs/testing/emulator.md`; screenshots through the console because of `FLAG_SECURE`)

1. A school rule over now (PWA › Time › Own Rules). Lock: "Skole / Til HH:MM" with the book glyph, under the date
   (frame A).
2. Music playing and the budget used up. With one message row: the hourglass card, the full tile and the row, scrolling
   about 30 dp with 3-button navigation (frame D). With no row: frame B's layout.
3. From the PWA, Pause a Rule for 15 min with the glance on screen: the card goes within seconds. "End now" brings it
   back.
4. A rule ending 2 minutes from now, screen on: the card goes at the minute (the alarm). With the screen off it is gone
   at the next screen-on.
5. Two overlapping rules, and a rule whose next day starts at its end: no "Til" line on the card or on the time-rule
   screen. Bedtime with today's budget used up: "Til 07:00" on both.
6. Font scale 2.0: the page scrolls and nothing is clipped. With the 12 h format: "Til 2:30 PM".
7. TalkBack: one stop, "Skole, til 14:30", no action announced. The swipe and the handle's "Lås opp" still unlock.
8. Android mode (Android's PIN set) and own mode: the same card. After the unlock, the time-rule screen as before.
9. A rule that starts while the used-up budget already locks (SCREEN_TIME, then SCHOOL), with the glance on screen:
   the hourglass card turns into the book card at the minute (the stricter reason check plus the listener).
10. A lifted second rule whose pause ends before the first rule's end: no "Til" on the card or on the time-rule screen.
    With the pause running past the end: "Til HH:MM" on both.
11. Switch Vibb's language nb <-> en with the glance up, then lock: the card's label and "Til"/"Until" follow.
12. A rule with an exempt messaging app and 5 minutes of budget left: no "Til" line (Q-6). Use the app for 6 minutes:
    the phone locks as SCREEN_TIME at the budget's end, as the card no longer promised otherwise.

## 2. Item 2 - screen time left in the anchor notification

**Fixed by the user (U6, U7):**
- one notification, the anchor's;
- the time left only while a budget is set today;
- one action, "Be om mer tid";
- the request **sends a web push**;
- the request has **its own switch under Notifications › Notify Me About, per parent, on by default**.

### 2.1 Which notification

Design 19b's "Fewer persistent notifications" (PLAN.md:2145, `launcher/CLAUDE.md:97-114`) left one standing
notification: the anchor `CommandListenerService`'s foreground notification. It reads "Vibb One"
(`notification_listener_title`), has no body, uses the MIN channel `launcher:command_listener` and id 1002
(`Notifications.kt:18-19`). It is built in `CommandListenerService.kt:161` and posted at `CommandListenerService.kt:111`.
`KidVpnService`'s notification shows only while the filter is starting or down. This item changes the anchor
notification's content only. The id, channel and icon stay, so it can never become a second notification.

### 2.2 What it says (pure, new `timerules/TimeLeftNotice.kt`, `TimeLeftNoticeTest`)

```kotlin
sealed interface TimeLeftText { data class Left(val minutes: Int) : TimeLeftText; data object UsedUp : TimeLeftText }
/** The request's part: the action, the asked line (as text, never a button), or nothing. */
sealed interface AskPart { data object Action : AskPart; data class Asked(val atMs: Long) : AskPart; data object None : AskPart }
data class TimeLeftNotice(val text: TimeLeftText?, val ask: AskPart)   // (null, None) = plain "Vibb One"

fun timeLeftNotice(budget: BudgetUse?, windowOpen: Boolean, request: RequestState): TimeLeftNotice
fun leftMinutes(remainingMs: Long): Int = ceil(remainingMs / 60 000)
```

`RequestState` is `requestState(...)`'s answer (§2.4): `IDLE`, `PENDING`, `ASKED(atMs)` or `CAPPED`.

| State | Title | Below the title |
|---|---|---|
| No budget today (`budget?.effectiveMinutes == null`: no budget at all, or none on today's weekday) | "Vibb One" (as today) | nothing |
| The pause or the offline override (`windowOpen`) | "Vibb One" | nothing |
| A budget, time left | `anchor_time_left` "%1$s igjen i dag" / "%1$s left today", with `durationText(leftMinutes, …)` (`TimeLockModel.kt:30`): "1 t 20 min igjen i dag", "45 min igjen i dag", "2 t igjen i dag" | the action "Be om mer tid" / "Ask for more time" (`IDLE`) |
| A budget, used up (`remainingMs == 0`; an unreadable ledger counts as used up) | `anchor_time_used_up` "Skjermtiden er brukt opp i dag" / "Screen time is used up today" | as above |
| A request being sent (`PENDING`) | the same title | nothing (no action, so no second tap) |
| A request taken (`ASKED`) | the same title | the content text `anchor_asked_at` "Spurt kl. %1$s" / "Asked at %1$s" (`ClockFormat.formatTime`), plain text, **no action** |
| The day's three used (`CAPPED`) | the same title | nothing, until midnight |

- **Rounding up:** "1 min igjen i dag" shows until the last second, and "0 min" never shows. The budget lock and the
  "brukt opp" title arrive together.
- **A rule that locks** (school, bedtime) changes neither the title nor the request part (D3).
- **The title is the time line.** The header already says "Vibb One" (the app name), so a "Vibb One" title over a body
  would say it twice. Without a budget the title is "Vibb One", as today. The asked line is the only body there ever
  is.
- **"Spurt" is text, never a button** (R13-8). A notification action can't be disabled, and a tappable "Spurt" that
  does nothing would be read by TalkBack as a button. So while a request is pending, taken or capped, the notification
  has no action at all.
- **The builder** moves from `CommandListenerService.buildNotification` to `AnchorNotice.build(context, notice)` (new
  glue, `server/AnchorNotice.kt`): `NotificationCompat.Builder` as today, `setOnlyAlertOnce(true)`, `setSilent(true)`,
  `setShowWhen(false)`, `setOngoing(true)`, the same small icon and channel. The action is
  `NotificationCompat.Action.Builder(…).setAuthenticationRequired(true)` (core 1.8+; the repo has 1.17.0).

### 2.3 When it updates (cadence)

`AnchorNotice.refresh(context, snapshot)` posts `notify(1002, …)` **only while the anchor runs**: a new public
`CommandListenerService.isRunning`, since `running` is private (`CommandListenerService.kt:328`). It posts nothing when
the notice and the app's locale equal the last posted ones. The cache key includes the locale, so a change of Vibb's
per-app language re-posts. The "last posted" field resets in `onCreate`, because a new instance posts its own.

- **From `TimeRulesRuntime.recheck`, every time, counting or not** (R13-3). The refresh runs right after
  `reevaluateLockReasonFromCache`, with the same snapshot (§1.4) and its `budget`. `recheck` runs on:
  - an accepted policy: a budget lift (more minutes, `applyBudgetLifts`), a rule pause, a changed budget;
  - midnight (a boundary while a budget is set);
  - the pause starting or ending;
  - screen-on, user-present, the boundary alarm and the budget running out.

  So the time-rule screen, Settings (where the pause starts), the PIN lock and the screen-off states are all covered
  without the screen-time counter. `ScreenTimeTracker.update`'s early return when nothing counts
  (`ScreenTimeTracker.kt:90`) no longer matters for the notification.
- **The minute tick, from `ScreenTimeTracker.update`** (`ScreenTimeTracker.kt:82`), only in its counting branch: after
  it computes `remaining`, it refreshes with that budget. While a budget runs, **the checkpoint's delay becomes the next
  minute flip**: the pure `checkpointDelayMs(remainingMs)` = `((remainingMs - 1) mod 60 000) + 1 + 200 ms` while
  counting with time left, else the old 60 s (`CHECKPOINT_MS`, `ScreenTimeTracker.kt:19`). The shown minute changes
  within 0.2 s of the real one. It ticks only while screen time counts, which is exactly when the number moves, and
  never while the screen is off. The ledger is still written about once a minute, as today.
- **The request's 30 minutes are a stored instant** (R13-8), `openUntilMs` in the record (§2.4), re-read at every
  refresh. There is no in-memory handler. So that "Spurt" also ends while nothing else happens,
  `TimeRulesRuntime.nextBoundary` (`TimeRulesRuntime.kt:196`) adds `openUntilMs` as one more extra instant, next to the
  lift expiries. The existing boundary alarm then fires `recheck`, and so the refresh, at the expiry. The alarm is
  re-armed after every re-check, so this survives process death and a reboot.
- **The tap and the server's answer** refresh directly (§2.4).
- **`onCreate`'s rule:** `startForeground(COMMAND_LISTENER_NOTIFICATION_ID, plain)` stays literally first and
  unconditional, with the plain "Vibb One" notification (launcher/CLAUDE.md's FGS rule). Reading CE prefs comes after
  it: `AnchorNotice.refresh` runs next, with a fresh snapshot (any fault leaves the plain notification).

### 2.4 The action, the record, the cap and the upload

- **The PendingIntent:** `PendingIntent.getBroadcast(context, 0, Intent(context, TimeAskReceiver::class.java)
  .setAction(ACTION_ASK_TIME), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`. It names its receiver explicitly
  (launcher/CLAUDE.md's lesson about implicit internal broadcasts), and the manifest entry is `exported="false"`, as for
  `InstallModeReceiver` and `WindowRelockReceiver`. `setAuthenticationRequired(true)` means a tap from Android's
  keyguard asks for the unlock first, so nothing on a lock changes state without the code. Our own PIN lock has no shade
  while LOCKED in either kiosk mode (`featuresWhileLocked`, `server/LockTaskHelpers.kt:119-133`). While unlocked, the
  kiosk's features always include NOTIFICATIONS (`server/src/handlers/devices.rs:697-702`).
- **The record** lives in DE prefs `time_request` (nothing personal: an id, instants, counts; one `commit()`, under one
  lock):
  - `TimeRequestRecord(id: String /* 32 hex, SecureRandom */, askedAtMs: Long, openUntilMs: Long /* askedAtMs + 30
    min */, day: String /* the ledger's day */, liftIdAtAsk: Long /* the largest lift id in the policy then */, state:
    PENDING | ASKED)`;
  - `TimeRequestCount(day: String, accepted: Int)`, the phone's own count (D5).
- **Pure `requestState(record, count, nowMs, day, maxLiftId)`** gives one of:
  - `CAPPED` when today's `accepted >= 3` (`TIME_REQUESTS_A_DAY`);
  - `IDLE` when there is no record, or it is from another day, or `nowMs >= openUntilMs`, or a lift newer than
    `liftIdAtAsk` has arrived (lift ids only grow on the server, `ScreenTime.kt:22`; any new lift, minutes or a rule
    pause, counts as the answer);
  - otherwise `PENDING` or `ASKED(askedAtMs)`.
- **Pure `askStep(enrolled, budgetToday, windowOpen, state)`** gives `ASK` only when the phone is enrolled, has a budget
  today, is outside the pause and the state is `IDLE`; otherwise `IGNORE`. On `ASK` the receiver writes a new `PENDING`
  record, refreshes (the action is gone at once), and calls `SyncRunner.request(context, "time_request")`. On `IGNORE`
  it does nothing: one debug log line, no toast, no sound. The action isn't shown in those states anyway, so this is
  only a guard against a stale notification.
- **The upload** (new `timerules/TimeRequests.kt`; `upload(context, api)` runs in `performMdmSync` right after
  `PowerEvents.upload`, `MdmSyncWorker.kt:332`, after the status report and behind the sync mutex). A `PENDING` record
  younger than `openUntilMs` is posted, and the pure `timeRequestAnswer(code, body)` decides what happens:

  | Answer | The phone |
  |---|---|
  | 200 `{"accepted":true}` | `ASKED`; today's count + 1; "Spurt kl. 14:02" |
  | 200 `{"accepted":false,"reason":"capped"}` | the record dropped; today's count set to 3 (the server's cap wins); the action gone until midnight |
  | 200 `{"accepted":false,"reason":"stale"}` (its day is over on the server) | the record dropped; the action back |
  | 404 (an old server) | the record dropped; nothing shown; the action stays (D5) |
  | another 4xx except 401, 408, 429; a 200 that doesn't parse | the record dropped; the action back |
  | 401, 408, 429, 5xx, no answer | kept `PENDING`, retried with the next sync, until `openUntilMs` (then `IDLE`: the action back, nothing sent) |

  `SyncRunner.request` starts the sync at the tap, so with the network up the parent's push arrives within seconds. A
  reconnecting SSE stream syncs too (`sse_open`, `CommandListenerService.kt:243`), so a tap made offline goes out when
  the phone is back online within the 30 minutes.

### 2.5 The API (both directories in one commit)

`POST /api/devices/time-request` is `handlers::device_api::time_request`, in `device_routes.rs` behind
`require_device_token` like `/api/devices/power` (`device_routes.rs:83`), with a body limit `TIME_REQUEST_BODY_LIMIT` of
1 KiB. The request body:

```json
{"id": "0123456789abcdef0123456789abcdef", "asked_at_ms": 1793034120000}
```

- **The answers:** 200 with `{"accepted":true}`, or `{"accepted":false,"reason":"capped"}`, or
  `{"accepted":false,"reason":"stale"}`. A body that doesn't parse, or an id that isn't 16-64 lowercase hex, gets 400.
- **The contract file** `server/testdata/time_request.json` holds both sides: `{"request": {…}, "answers":
  [{"accepted":true}, {"accepted":false,"reason":"capped"}, {"accepted":false,"reason":"stale"}]}`. It is
  byte-identical in `launcher/app/src/test/resources/`.
  - The launcher's `TimeRequestsTest` encodes exactly `request` from a `TimeRequestBody` (`server/dto/TimeRequest.kt`),
    and decodes each answer into `TimeRequestAnswer` with `timeRequestAnswer`'s verdict.
  - The server's `time_request::the_contract_answers` posts `request` (its instant moved to a minute ago) and produces
    every answer row: the first post, a fourth of the day, and one asked yesterday.
  - Both compare the copies.
- **The paths:**
  - `CLAUDE.md`: add the file to the shared-files paragraph and to both lists of watched files in "CI and releases"
    (`launcher.yml`'s and `server-ci.yml`'s build-job lists).
  - The path regexes in `.github/workflows/launcher.yml:76` (`server/testdata/time_request.json`) and
    `.github/workflows/server-ci.yml:53` (`launcher/app/src/test/resources/time_request.json`).
  - The comment block above `server-ci.yml`'s regex.
- **The policy doesn't change.** `policy_json_keys_snapshot` and `PolicyResponseCompatTest` don't move, and nothing new
  comes back to the phone in the policy. The parent's answer is a lift in `time_policy.lifts`, which already arrives.
- There is no status capability; the server never needs to know whether a phone can ask.

### 2.6 The server: the event, the push and its switch, the rate

- **The kind** (`parent_events.rs:36`): `Kind::ExtraTimeRequest`, wire `extra_time_request`, after `LowBattery` in
  `Kind::ALL` (`parent_events.rs:54`).
  - Not urgent: urgency normal, TTL 3600 s (later it's old news; the inbox keeps it).
  - `Kind`'s doc comment (`parent_events.rs:33-34`) no longer calls it reserved.
- **The push and its switch (U7):** `switch()` = `("push_extra_time", "Asks for more time")`
  (`parent_events.rs:113`).
  - Notify Me About lists it from `Kind::switches()` (`notifications.rs:503-521`, `:577-600`; template loop
    `notifications_settings.html:110`), with no template change.
  - The push queue reads the same switch per parent (`push.rs:336-347`).
  - Every parent gets the push unless they turned the switch off.
- **The migration** (D6): `0096_extra_time_request.sql` = `ALTER TABLE notification_prefs ADD COLUMN push_extra_time
  INTEGER NOT NULL DEFAULT 1;`. On by default for every parent, existing ones included; no row also means on
  (migration 0088).
  - The order test becomes `migrations_73_to_96_in_order`, with its doc comment: "the extra-time request 0096 (was
    `migrations_73_to_95_in_order`)". 0095 is `pwa-fixes-a`'s `0095_push_session_epoch.sql`.
  - Whichever of the two branches merges second renumbers its file and the test's name.
- **Key and params, as design 64** (`zone_crossing`/`told`, `parent_events.rs:968`, `:1006`): a pure
  `parent_events::extra_time_request(device_id, phone, id, at, state, push) -> NewEvent`.
  - Key: the phone's `id`, so a retried POST gives one row.
  - `title` and `summary` are stored empty. `params` is `ExtraTimeParams { phone, at, state, left_minutes }`, with
    `state` one of `"left"`, `"used_up"`, `"rule"`, `"unknown"`.
  - The state comes from the server's own latest report for the phone (`device_status.time_state_json` /
    `lock_reason`; the sync posts its status report before the uploads, so it is fresh), never from text in the request.
- **The words** (`told`; English, like every event; told when read, in the Phone Setup zone):
  - Title: `fit_name(phone, 60, "{name} asks for more screen time")`.
  - Summary: "{At} · {state}", where state is "1 h 20 min left", "Screen time used up", "School" / "Bedtime" / "A time
    rule" (`lock_reason`'s kind word, no rule name), or empty for `unknown`. Examples: "17:21 · Screen time used up",
    "Yesterday 21:40 · Bedtime".
  - Activity's `without_own_clock` (`activity.rs:123-143`) strips a leading clock **case-insensitively**, so the day
    after, "Yesterday 21:40 · …" against `when` "yesterday 21:40" doesn't show the clock twice. A test pins it.
- **Target:** `/devices/{id}/time#extra`.
- **The rate (the server's backstop for D5).** In the handler's `BEGIN IMMEDIATE` transaction (`begin_with`, as
  `zones.rs:539`), before `raise`, count the phone's `extra_time_request` rows that are from the server zone's today and
  have `dedup_key != id`. Compare the day bounds through `datetime(happened_at)`, as `devices.rs:1410` does, since
  `happened_at` is RFC 3339 with `T…Z` and `created_at` is `datetime('now')`. At `EXTRA_TIME_REQUESTS_A_DAY` = 3 the
  request isn't stored: one `tracing::info!` line, then 200 `{"accepted":false,"reason":"capped"}`. A retry of the third
  request (same id) is taken, not counted as a fourth. The device route's per-device limits (`limits`) cover a flood.
- **Push only while it's news:**
  - `Push::Now` when `asked_at` is under 30 min old at intake, else `Push::No` (inbox and Activity only).
  - A request asked before the server zone's today isn't stored: 200 `{"accepted":false,"reason":"stale"}`.
  - A future `asked_at` (a phone clock ahead) counts as the server's now.
- **Retention:** `EVENT_DAYS` (90), as for every kind. It shows in the inbox, the bell and design 48's Activity with no
  extra code (`told` runs in the inbox, in Activity via `inbox_row`, and in the push, `push.rs:498`).
- **Tests:** `src/tests/time_request.rs`, registered in the tests' `mod.rs`.

### 2.7 The PWA

- **The push and the inbox row** open `/devices/{id}/time#extra`: the Extra Time Today group with +15 min, +30 min and
  +1 h (`device_time.html:37`), with Pause a Rule right below it.
- **"Asked for more time at 17:21":** a `.group` with one `.row.row-note` (`data-asked`) at the top of `#extra`, above
  the buttons. It shows while a request is open on the server. Open means the phone's newest `extra_time_request` row
  is from today (server zone), and no `time_lifts` row for the phone has a `created_at` after that event's `created_at`.
  Both timestamps are on the server's clock, compared through `datetime()`, and any lift counts. It is computed in
  `render_device_time` (`schedules.rs:1279`) by `parent_events::open_extra_time_request(db, id, &zone, now) ->
  Option<DateTime<Utc>>`.
- **Saves never jump to the top:** the lift forms already return to their group (`back=time` → `#extra` / `#pause`,
  `lifts.rs:41-52`). After a lift the page lands on `#extra`, with its flash and without the "Asked" row.
- Nothing on the summary page, and no new setting beyond the Notify Me About switch.

### 2.8 Tests (lean)

- **Launcher `TimeLeftNoticeTest`** (step 3, pure): `timeLeftNotice`'s table - no budget today, the pause, time left,
  used up, an unreadable ledger, and each `RequestState` - and `leftMinutes` (1 ms → 1, 60 000 → 1, 60 001 → 2).
- **Launcher `TimeRequestsTest`** (pure):
  - step 2 creates it with the contract part: `request` encodes to exactly the file's, each answer decodes, and the
    copies are identical;
  - step 3 adds `requestState` (the day, `openUntilMs`, a newer lift, the cap), `askStep`, and `timeRequestAnswer` for
    every row of §2.4's table.
- **Launcher `ScreenTimeTest`** (existing): `checkpointDelayMs` (61 000 → 1 200; 60 000 → 60 200; no budget → 60 000),
  next to `screenTimeCounts`.
- **Server:**
  - `parent_events::tests`: the words for each `state`; a long name cut to 60; `without_own_clock` with "Yesterday …".
  - `src/tests/time_request.rs`:
    - the contract's answers;
    - the first request gives one row and a push for the parent with `push_extra_time` on, none for the one with it off;
    - the same id twice gives one row;
    - a retried POST of the third request is taken, not counted as a fourth;
    - the fourth of the day gives no row and `capped`;
    - asked 31 min ago: a row, no push;
    - asked yesterday: no row, `stale`;
    - a bad id: 400;
    - the Time page shows "Asked for more time at …" in `#extra`. After `POST /devices/{id}/lifts` (budget +15) it is
      gone and the redirect is `#extra`. A lift created before the request leaves the row;
    - the contract copies are identical;
    - Notify Me About lists "Asks for more time".
  - `migrations_73_to_96_in_order`.

### 2.9 Emulator checks

1. Own Daily Limit set to 3 min today. The shade shows one Vibb notification, "3 min igjen i dag". With Home in front
   it steps down at each minute; with the screen off or the PIN lock up it doesn't move. `adb shell dumpsys
   notification --noredact | grep me.vibb.launcher` shows one record, id 1002.
2. Used up: the title is "Skjermtiden er brukt opp i dag" and the time-rule screen is up. The shade is reachable there.
   Check whether the action shows without expanding the notification; if the silent section collapses it, the kid
   expands it. Note what Android 15 and 16 do.
3. Tap "Be om mer tid": the action goes. Within seconds the line "Spurt kl. HH:MM" shows (text, not a button). The
   PWA's inbox has "… asks for more screen time / HH:MM · Screen time used up", and a web push arrives
   (`docs/testing/emulator.md`'s Web Push check). With Notify Me About › "Asks for more time" off for that parent: the
   inbox row, but no push.
4. In the PWA, open the event: it lands on Time › Extra Time Today with "Asked for more time at HH:MM". Tap +15 min:
   the page stays at `#extra` and the "Asked" row is gone. On the phone: "15 min igjen i dag" and "Be om mer tid" are
   back. Do it once with school locking (the time-rule screen in front, nothing counting): the title and the action
   still update (R13-3).
5. Three requests in a day (wait out the 30 min, or answer each with a lift): after the third, the action is gone until
   midnight. A fourth forced through a debug build gets `capped` from the server and no event.
6. Airplane mode, tap, back online within 30 min: the event arrives. After 30 min offline nothing is sent and the
   action is back; the boundary alarm ends "pending" with the screen on the time-rule screen too.
7. An old server (0.48.0): tap, then nothing shows and the action stays. No limit for today (an empty weekday): plain
   "Vibb One". The 2-hour pause: plain "Vibb One".
8. Android mode: if the notification shows on Android's lock screen at all (a MIN-channel silent notification normally
   doesn't), its action asks for the unlock first.
9. Tap offline, add +15 min from the PWA, then go online: the phone must not post the old request, and Device › Time
   must not show "Asked" (Q-3).
10. Reboot while "Spurt kl. HH:MM" shows: after the unlock it is still there, and it goes at the 30 minutes with the
    screen left on the time-rule screen.
11. Three accepted requests, then move the clock past midnight (`adb shell cmd alarm set-time` or a zone change): the
    action is back at 00:00 with no tap.
12. Phone Setup's zone unset, the phone in Europe/Oslo: a request between the phone's midnight and UTC midnight after
    three in the evening is accepted - the server counts the phone's own day (Q-2).
13. `adb shell dumpsys notification --noredact | grep me.vibb.launcher` after the pause starts and ends and after the
    offline override: still exactly one record, id 1002, plain during the window.
14. TalkBack on the shade: the action reads "Be om mer tid, button"; "Spurt kl. HH:MM" reads as text, with no action
    announced.
15. Switch Vibb's language with the shade open: the title follows within a minute while counting.

## 3. Implementation steps

| Step | What | Size |
|---|---|---|
| 1 (launcher) | Item 1: `lock/RuleCard.kt` + `RuleCardTest`; `ruleLabel` in `TimeLockModel.kt` (used by `LockActivity` too); `LockActivity.kt:177` on `lockEnd` (D2); `lock_rule_card` in `view_lock_glance.xml`; `glanceLayout(…, rule)` (the compact rule unchanged); `recheck` computes one snapshot, passes it to `reevaluateLockReasonFromCache` and the lock listeners (before `relearn`); `GlancePanel` (model, bind, listener, `placeList`, the try/catch in `resumeNow`); the string; `LockGlanceTest`/`LockGlanceLayoutTest`; launcher/CLAUDE.md's glance paragraph. Emulator §1.7. | S-M |
| 2 (both directories, one commit for the contract) | Server: `Kind::ExtraTimeRequest` (and its doc comment), `0096_extra_time_request.sql`, `extra_time_request` + `told`, Activity's case-insensitive strip, `handlers::device_api::time_request` with the cap, the news rule and the three answers, the Time page's "Asked" row, `src/tests/time_request.rs` (in `mod.rs`), `migrations_73_to_96_in_order`, `docs/notes/server/notifications.md` and `time-rules.md`. Launcher: `server/dto/TimeRequest.kt` (body + answer), `MdmApi.sendTimeRequest`, the contract copy, `TimeRequestsTest`'s contract part. `server/testdata/time_request.json` + its copy; the CI path regexes and `server-ci.yml`'s comment; `CLAUDE.md`'s shared-files paragraph and both "CI and releases" lists. Server release (0.50.0) before the launcher with step 3. | S-M |
| 3 (launcher) | Item 2: `timerules/TimeLeftNotice.kt` + `TimeLeftNoticeTest`; `server/AnchorNotice.kt` (builder, refresh, the locale-keyed cache, reset in `onCreate`); `CommandListenerService.isRunning`, `startForeground` plain first then the refresh; `TimeAskReceiver` (manifest, not exported); `timerules/TimeRequests.kt` (DE record and count, `requestState`, `askStep`, the upload and `timeRequestAnswer`) + the rest of `TimeRequestsTest`; `openUntilMs` in `TimeRulesRuntime.nextBoundary`; the refresh in `recheck`; `ScreenTimeTracker`'s checkpoint delay and its refresh (+ `ScreenTimeTest`); the strings; launcher/CLAUDE.md's "Notifications in the shade" paragraph. Emulator §2.9. | M |

Each step follows the usual process: design QA (done: `qa-r13-design.md`), implementation, code QA, then all of the
fix round. Step 1 is independent of steps 2 and 3 and may run in parallel in its own worktree. Steps 2 and 3 are in
order. PLAN's two backlog entries are marked done with their branches.

Not in scope:
- a "Be om mer tid" button on the time-rule screen or the glance;
- requests without a budget (for example during bedtime with no daily limit);
- the summary page's Quick actions showing the request;
- Norwegian PWA words;
- any change to the music app.

## 4. Open questions for the user

None. The three questions of the first draft and the QA's two (R13-4, R13-7) are decided (§0, D1-D6).

## QA fixes (`qa-r13-design.md`)

- R13-1: the migration is `0096_extra_time_request.sql` and the order test `migrations_73_to_96_in_order` (its doc
  comment named). 0095 is `pwa-fixes-a`'s, and whichever branch merges second renumbers (D6; §2.6, §3).
- R13-2: `lockEnd` checks the end with the budget of the end's own day (today's use only when the end is today), and
  refuses an end that isn't a single instant in the zone. "Twin" is replaced by what each side checks. Three test cases
  added: bedtime with today used up gives 07:00, tomorrow's budget 0 gives null, the spring-forward gap gives null
  (§1.1, §1.6).
- R13-3: the notification refreshes from `TimeRulesRuntime.recheck` after the lock reason is re-evaluated, with its
  snapshot, counting or not. The checkpoint keeps only the minute tick. The time-rule screen, Settings and screen-off are
  covered this way (§2.3; emulator check 4).
- R13-4: decided (D4). The card doesn't count toward the compact player; the user's two-notification rule stands, like
  for the timer tile. §1.3's table now has 430.8 dp scrolling 29.8 / 5.8 for frame D, and the mockup's frame D and its
  caption show the full tile.
- R13-5: `recheck` computes one snapshot and hands it to `reevaluateLockReasonFromCache` and the listeners. The
  listeners run before `relearn` (one layout). Why the preference listener isn't enough is spelled out. The card's
  computation gets its own try/catch in `resumeNow`. "The glance never runs before the first unlock" replaces the
  throwing-snapshot wording (§1.4, §1.5).
- R13-6: a rule lift counts in `lockEnd` only if its expiry is at or after the end; one test case added (§1.1, §1.6).
- R13-7: decided (D5). The phone keeps its own count of accepted requests (DE prefs) and hides the action after three
  until midnight. The server's cap answers 200 `{"accepted":false,"reason":"capped"}`. "Spurt" shows only after
  `{"accepted":true}`, and an old server's 404 shows nothing. The answers are in `time_request.json` (§2.4-§2.6).
- R13-8: "Spurt" is the content text "Spurt kl. 14:02", never a button, and there is no action while a request is
  pending, taken or capped. The 30 minutes are a stored instant (`openUntilMs`), re-read at every refresh, with no
  in-memory handler. It is one more boundary for the existing alarm, so it ends on time across process death (§2.2,
  §2.3; mockup frame G).
- R13-9: the tests are split. `TimeRequestsTest` (the contract part in step 2, the rest in step 3) and
  `TimeLeftNoticeTest` (the table, `leftMinutes`); `checkpointDelayMs` is in `ScreenTimeTest`. The server drops the
  log-line assertion and gains "a lift before the request leaves the row" and "a retried third request isn't a fourth"
  (§2.8, §3).
- R13-10: step 2 updates both of `CLAUDE.md`'s "CI and releases" lists and `server-ci.yml`'s comment block, besides the
  shared-files paragraph (§2.5, §3).
- R13-11: `startForeground` with the plain notification stays literally first, then the refresh. `isRunning` is
  exposed. The last-posted cache resets in `onCreate` and is keyed with the locale (§2.3).
- R13-12: `NotificationCompat.Action.Builder(…).setAuthenticationRequired(true)`; emulator check 8 reads "if it shows
  there" (§2.2, §2.9).
- R13-13: the day bounds go through `datetime()`; Activity's clock strip is case-insensitive, with a test; `Kind`'s doc
  comment is updated; the handler is `handlers::device_api::time_request`; `src/tests/time_request.rs` is in `mod.rs`
  (§2.5-§2.8).
- R13-14: `ruleLabel` returns the name or the kind (resolved by the screens), never a string from resources; the card is
  `screenReaderFocusable="true"` (§1.2, §1.3).

## Implementation status (2026-10-25, branch `r13-rule-card`, code-QA'd, fix round done, awaiting merge)

> **Status 2026-10-26 (main 8d8ba426): merged.** The launcher half merged as `3eb0c025` (branch `r13-rule-card`,
> 2026-10-25; in `launcher-v0.37.0-rc.2` and later) and the music half as `85cc9dc7` (`music-r13`) with its fix
> `9061a8e9` (`music-r13-fix`; `music-v0.5.0-rc.6`); the server half (the new parent-event kind and the time-request
> route) is in `server-v0.50.0`. Emulator run: `docs/testing/2026-10-25-r13-run.md` (the card, the notification with a
> budget, the action and a lift's answer pass). **Owed** (E1): the compact/scroll cases with music and rows, the
> budget used up (set a 5-minute limit), the cap of three, the 30-minute expiry, the new kind's web push; the Jelly
> Star. The header line above is the state when the section was written.

Built in three steps, each its own commit on `r13-rule-card` (from `main` at 77089563):

1. **Step 1, launcher** - the rule card: `lock/RuleCard.kt` (`ruleCard`, `lockEnd`, `ruleLiftsAt`, `lockEndOf`,
   `ruleCardOf`, `RuleCardKind.glyph`), `ruleLabel`/`RuleLabel` in `ui/TimeLockModel.kt` (the time-rule screen's title
   and "Til" line use them, D1/D2; `LockActivity.labelText`/`kindLabelRes`), `liftExpiry` factored out of
   `liftExpiries`, `lock_rule_card` in `view_lock_glance.xml`, `glanceLayout(.., rule)`, the lock listeners and the
   one snapshot in `TimeRulesRuntime.recheck` (`reevaluateLockReasonFromCache(context, snapshot)`), `GlancePanel`'s
   model, listener, `bindRuleCard`, `placeList` and the `try/catch` in `resumeNow`, `glance_rule_card_description`
   (nb + en). Tests: `RuleCardTest` (7), `LockGlanceTest`'s rule-card layout test, `LockGlanceLayoutTest` (the 82 dp
   part, the card's attributes, §1.3's table at 473/497 dp, the order).
2. **Step 2, both directories** - `POST /api/devices/time-request` (`handlers::device_api::time_request`), the kind
   `extra_time_request` (key + params, `told`), the switch `push_extra_time` (migration
   `0096_extra_time_request.sql`), the cap, the news rule, `stale`, Device › Time's `data-asked` row
   (`parent_events::open_extra_time_request`), Activity's case-insensitive clock strip, the contract
   `server/testdata/time_request.json` + its launcher copy, `dto/TimeRequest.kt`, `MdmApi.sendTimeRequest`,
   `TimeRequestsTest`'s contract part, both CLAUDE.md lists and the workflows' path filters. Tests:
   `tests::time_request` (7; 9 after the fix round), `parent_events::tests` (2 new, the words table extended), `activity::tests` (1 new),
   `migrations_73_to_96_in_order`.
3. **Step 3, launcher** - `timerules/TimeLeftNotice.kt`, `server/AnchorNotice.kt`, `timerules/TimeAskReceiver.kt`
   (manifest, not exported), `timerules/TimeRequests.kt` (+ `AnchorRefresh`), `CommandListenerService.isRunning` and
   its `onCreate` (plain first, then reset and refresh), the refresh in `recheck`, `openUntilMs` in `nextBoundary`,
   `checkpointDelayMs` and the minute tick in `ScreenTimeTracker.update`, the upload after `PowerEvents.upload`, the
   strings (nb + en). Tests: `TimeLeftNoticeTest`, the rest of `TimeRequestsTest`, `ScreenTimeTest`'s
   `checkpointDelayMs`, `QuietNotificationsTest` (the builder moved to `AnchorNotice`).

Test counts at the end: launcher 1742 unit tests (1733 after steps 1-2), server 1298, all green; no jstests touched (no static
JS changed); clippy's 13 known warnings, no new one.

**Deviations, as built:**
- **D6 / the migration**: `0096_extra_time_request.sql`. Built with a gap at 0095 while `pwa-fixes-a` held it; since
  the fix round's merge of `main` 0095 is there and `migrations_73_to_96_in_order` lists both - no gap, no renumbering.
- **`ExtraTimeParams` has one more field than §2.6 names**: `rule` (the report's `lock_reason`, `SCHOOL`/`BEDTIME`/
  `RULE`) next to `state: "rule"`, since the words "School"/"Bedtime"/"A time rule" need the kind; never the rule's
  name. `left_minutes` and `rule` are left out when unused.
- **`requestState`'s order**: an open record of today (Pending/Asked) comes before Capped, so "Spurt kl. HH:MM" shows
  after the day's third accepted request too; Capped follows once it is answered or its 30 minutes end (§2.4 lists
  Capped first; read as the states, not their order).
- **The Time page's "after a lift"**: a lift whose `created_at` is at or after the request's counts (both
  `datetime('now')`, one-second resolution): a lift in the same second as the request counts as its answer.
- **Two more refresh points than §2.3 names**: the pause's start (`RestrictionsPause.reapply`) and the override's
  (`OfflineOverride.activate`) call `AnchorRefresh.request` - neither runs `recheck`, and the notice must go plain at
  once. Since the fix round (Q-5) the tracker's minute tick hands its own `BudgetUse`, policy and ledger day through
  (`AnchorNotice.refresh(context, budget, policy, day, now)`), as §2.3 said - no snapshot of its own.
- **`ruleCard` also needs the same reason** in the pref and the snapshot (not only both locking): a moment where they
  disagree on the kind shows no card.

**Fix round** (`docs/review/qa-r13-rule-card-code.md`, "Fix round"): `main` merged (0095 on it, no renumbering);
Q-1 the server part ships as 0.50.0 (`server/Cargo.toml` bumped on this branch); Q-2 the cap and "stale" count the
phone's own day - the zone its report names, else Phone Setup's (`device_api::extra_time_day_zone`); Q-3 the upload
drops a request a lift answered meanwhile (`uploadStep`); Q-4 the tap arms the boundary alarm; Q-5 the minute tick
builds no snapshot; Q-6 `lockEnd` refuses an end the budget can break first under a rule with usable apps; Q-7/S4 the
switch is on for a parent saved before 0096; Q-8 the server tests pass at any hour (`steady_zone`, the day from the
zone); L11 and L15 pinned. Test counts after it: launcher 1780, server 1311 (with main merged), all green; clippy 13 known warnings.

**Owed (not run here):** the emulator checks §1.7 (1-12) and §2.9 (1-15), the server's web-push check for the new kind
(§2.9 item 3), Android 15/16's silent-section collapse (§2.9 item 2), and the Jelly Star. Release order:
`server-v0.50.0` before the first `launcher-v*` tag with step 3.
