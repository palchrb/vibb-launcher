# Design 93: "Slå på nå" and Quick Actions as two tiles

Status: design, 2026-11-13. It folds in the user's two mockup rounds and the change that came in while this note was
being written: Quick Actions has **two** tiles, **Mer skjermtid** and **Regler**, and today's "Lås nå" and "Pause
regler" are now the two choices of one Regler sheet. That change is in this note from the start; there is no older
three-tile version below. **Server only** (§9): the launcher's rc.8 shows everything. Migration
`0142_time_lock_now.sql` is the next free number on `main` at `d5125eb5`; recheck it at merge.

## 1. Verdict and summary

**Buildable server-only, with one residual gap the parent should know about (§4.6, Q2).** A rule turned on now
("Slå på nå", the mockup's "Lås nå") is the server serving one more entry in `time_policy.rules`: a **copy of the chosen
rule** (its kind, name, exempt apps, calls, Kalender and silence) whose `days` hold one window on today's weekday, from
now to the end the parent chose, in the phone's local time. The launcher evaluates it like any rule
(`decideTimeLock`), shows it on the r13 card with the rule's own name and icon and "Til 18:30", ends it by itself at
the end, and pauses it under an "Alle regler" pause. Nothing in the policy's shape changes.

- **The phone offline when the lock ends**: no problem. The phone ends the window by itself; the server stops serving
  the copy at its end.
- **The phone offline for over a week**: the launcher has **no date-bound field** on a rule (`TimeRule` is weekly
  windows only, and the cached policy is kept without end). A phone that got the copy and then reaches no server for
  seven days locks again in the same window on the same weekday, and every week after, until its next sync drops the
  copy. Nothing the server can send changes that; it would take a launcher change (Q2). Until then the parent can
  lift it on the phone with the Parent PIN, as with every rule offline. The lock is stricter, never looser.
- **The zone's DST, the server's restart, two parents at once**: §3, §4.4 and §5.1. All the state is in one table, every
  decision is a single SQL statement, and the window is wall-clock time, like the launcher's.

Quick Actions becomes two tiles in one row, each with a coloured glyph, its name and a state line, and each opens a
sheet in one pattern: title + Lukk, what, how much or how long, an optional Til, one line on what happens, and one
button saying the action in plain words. The +15/+30/+1 h tiles become the Mer skjermtid sheet. The Finn tile goes
(Finn has its own tab). "Lås opp 2 t" moves to a new **Avansert** group under Innstillinger, as the row **"Åpne for
oppsett"**. While the opening runs, a row at the top of Quick Actions shows it with "Lås igjen".

Size: medium, about 1,400 lines with the tests (§12). Four steps (§13).

## 2. What exists today

### 2.1 The launcher (unchanged by this design)

- `TimeRule` (`launcher/.../timerules/TimeRules.kt:29-42`): `id`, `name`, `kind`, `callsAllowed`, `exemptApps`,
  `calendarAllowed`, `silent`, `days` (7 windows, Monday first). **No date and no expiry.**
- `spanOn` (`TimeRules.kt:85-99`): a window is local minutes on its weekday. `start < end` is that day, `start > end`
  ends the next day (so `{s, 0}` ends at midnight), and `start == end` lasts 24 h.
- `decideTimeLock` (`TimeRules.kt:172-202`): the override or the restrictions pause comes first and gives no lock.
  Otherwise the active rules a lift doesn't lift are sorted by **kind rank first (school < bedtime < custom), then
  their index in `rules[]`** (`:182-185`). `calls` holds only if every rule allows calls, and the usable apps are the
  intersection. `until` is the first rule's occurrence end.
- `RuleLifts.lifts` (`TimeRules.kt:131-133`): a lift with `ruleId == null` lifts every rule, so a copy too.
- `nextBoundary` (`TimeRules.kt:214-233`): every rule's edges over 8 days, so the copy's end arms the boundary alarm.
- The r13 card: `ruleCard` (`lock/RuleCard.kt:63`) takes the first rule's kind and name (`ruleLabel`,
  `ui/TimeLockModel.kt:37`: a blank name gives the kind's label, e.g. "Leggetid" and the moon). `lockEnd`
  (`RuleCard.kt:80-102`) shows "Til" **only if the decision at `until` is unlocked**: when another rule still locks at
  the first rule's end, the card shows no time.
- Lifts: `updateLiftRecords` (`timerules/ScreenTime.kt:131-139`) times a rule lift from first sight, bounded by
  `expiresAtMs`. A lift the server stops sending is dropped.
- The cache: `PolicyGate.kt:192` keeps `timePolicy` **without lifts** (`copy(lifts = emptyList())`). Rules are kept
  with no age limit.
- The status report: `time_state.active_rule_id` is the first rule's `id` (`TimeRulesRuntime.kt:206`), and
  `time_state.zone` is the phone's IANA zone (design 34 P13).

### 2.2 The server

- `PolicyTimeRule` and `TimeRuleRow::to_policy` (`server/src/time_rules.rs:101-115`, `:168-194`): a bedtime, and a
  name the server made, go out as `name: ""`. `served_days` (`:196-207`) serves an end of 23:59 as `end: 0`.
- `effective_rule_rows` (`time_rules.rs:955-972`): everyone's rules and the phone's own, minus `device_rule_off`, then
  its bedtime. It is the one reader for the policy, the lifts, the badge, the summary, Attention and the counts.
- `deliverable_lifts` (`time_rules.rs:987-1010`) and `build_time_policy` (`handlers/device_api.rs:613-639`).
- Lifts: `handlers/lifts.rs` has `create_lift` (`:58`, `target=rule|budget`, `rule=all|id`, 15/30/60/120 or 15/30/60),
  `end_lift` (`:166`), `done_flash` (`:219`), `LiftState` (`:232-305`) and `today` (`:319`). The table `time_lifts` is
  migration 0025 (`migrations/0025_time_rules.sql:40-52`). Both routes write a `security_events` row (`time_lift`,
  `time_lift_ended`). They make no inbox event and no push.
- The restrictions pause: `start_pause` / `end_windows` (`handlers/window_relock.rs:353`, `:299`) queue the phone's
  `start_pause` (2 h, fixed on the phone) / `end_windows` (ends the pause, the override and Play install mode).
  `DeviceStatus::paused_at` (`models.rs:570`). Attention's "Begrensninger satt på pause" lands on `#quick`
  (`attention.rs:367`). The flash groups are at `window_relock.rs:974-977`.
- The badge: `TimeReport::named_by` (`time_state.rs:106`), `rule_days` (`:147`), `lock_badge` (`:319`) and
  `provable_end` (`:362`) find the active rule **by id** among the rows they are given. The callers that read rules for
  it are `handlers/devices.rs:385` (the Devices list), `:2121` (the summary), `handlers/device_pages.rs:742` and
  `handlers/schedules.rs:1892` (Device › Time Rules).
- The summary's Quick Actions (`templates/device_detail.html:123-209`) are `.act-grid` with three columns
  (`static/style.css:2517-2529`): +15/+30/+1 h as three one-tap forms, Pause a rule… as `<details class="act-sheet">`
  (`:146-171`, two `<select>`s, no JS), Lock again (`:173-177`), "Lås opp 2 t…" as `<details class="act-sheet
  confirm">` (`:178-186`) and Finn (`:188`). Today's lifts with End now follow (`:192-207`). `.act-sheet[open]` spans
  the grid (`style.css:2962-2967`). The segmented control (`style.css:3373-3387`, design 77 D2) is radios inside
  labels, with sibling selectors only.
- `static/confirm.js`: any `[data-confirm-cancel]` closes the `<details>` it sits in and keeps the place. It is hidden
  without JS.
- `settings_log.rs:30` `NOT_SAVES`: the commands and pauses that log their own event.
- The routes are at `main.rs:1075-1080` (lifts) and `:1162-1175` (end-windows, start-pause).

## 3. Data model: migration `0142_time_lock_now.sql`

```sql
-- Design 93: a rule turned on now, for the rest of a window today ("Slå på nå").
CREATE TABLE time_lock_now (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    rule_id INTEGER REFERENCES time_rules(id) ON DELETE SET NULL,
    rule_name TEXT NOT NULL,        -- the stored name at the start, for Activity once the rule is gone
    zone TEXT NOT NULL,             -- the phone's IANA zone (time_state.zone) at the start
    local_date TEXT NOT NULL,       -- YYYY-MM-DD in that zone
    start_minute INTEGER NOT NULL CHECK (start_minute BETWEEN 0 AND 1438),
    end_minute INTEGER NOT NULL CHECK (end_minute BETWEEN 1 AND 1439),   -- 1439 = until midnight
    ends_at TEXT NOT NULL,          -- UTC, datetime() form: the end in the zone (§4.4)
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    created_by TEXT,
    changed_at TEXT,
    changed_by TEXT,
    ended_early_at TEXT,
    ended_by TEXT,
    CHECK (end_minute > start_minute)
);
CREATE INDEX idx_time_lock_now_device ON time_lock_now(device_id, ends_at);
```

- **Running** means `ended_early_at IS NULL AND ends_at > datetime('now')`. That is one SQL condition, read at every
  request, with no timer. A restart changes nothing.
- **At most one running per phone.** The insert is guarded in one statement (§5.1), so two parents can't make two.
- **The mode is the rule's, read live** when the policy is built. If the parent edits Skole's exceptions during the
  lock, the lock follows, as a scheduled Skole would. If the rule is deleted (`rule_id` NULL) or switched off for this
  phone, the copy is no longer served: the lock ends at the phone's next sync, and Activity says "Ended: rule removed".
  The decision: a rule that no longer applies to Emma can't keep locking her phone.
- Retention: `retention.rs` deletes `time_lock_now` rows like `time_lifts` (`ends_at` older than the same days).

## 4. The policy

### 4.1 The copy

`lock_now::served(db, device_id, now) -> Option<(LockNowRow, TimeRuleRow)>`: the running row, with its rule among
`effective_rule_rows`. The served entry is that rule's `to_policy()` with three changes:

- `id: -(time_lock_now.id)`. Negative ids can't collide with a rule's id, and they stay the same across a change of
  the end, so the launcher's `TimeLock.key()` and its lift records hold. The launcher's DTO is `Long`, and the server
  reads `active_rule_id` with `as_i64` (`time_state.rs:73`, `device_api.rs:1169` `Field::Int`).
- `days`: `null` on every weekday but `local_date`'s, which holds `{start_minute, end_minute}` through `served_days`.
  So 23:59 is served as `end: 0`, "until midnight". For "Ut dagen" from 16:30 that is `{990, 0}`, and the launcher's
  `start > end` puts its end at midnight.
- `name`, `kind`, `calls_allowed`, `exempt_apps`, `calendar_allowed` and `silent` are the rule's own. The bedtime and
  server names stay `""`, so the kid sees "Leggetid" and the moon, and "Skole" with the school glyph, plus "Til 18:30"
  from `lockEnd`.

`start_minute` is the local minute when the parent tapped, rounded down. A phone that syncs late gets a window that
has already begun and is still running. A phone that syncs after the end gets nothing (§4.6).

### 4.2 Where it goes in `rules[]`: the card shows the lock that ends last

The launcher sorts by kind first, then index. The server can only choose the copy's **index**:

- **The copy first** when no rule of the same kind has an occurrence today that overlaps the copy's window and ends
  after it. Then `until` is the copy's end, and at that end nothing of that kind still locks, so the card shows "Til
  18:30".
- **The copy last** otherwise. For example, Skole runs to 14:30 and the parent turns Skole on until 13:00. The
  scheduled Skole is then first, its end is 14:30, and the copy is over by then, so the card shows "Til 14:30".

The server computes this from the served days in the copy's zone (`time_state::covering_ends`, made `pub(crate)`). It
is a pure function, `lock_now::copy_first(copy, rules) -> bool`, and pinned by a vector test.

**What the server can't do: a stronger kind that ends earlier.** Say Leggetid is turned on from 12:00 to 18:00 while
the scheduled Skole runs to 14:30. Skole ranks first, so until 14:30 the card shows "Skole" with **no time**: `lockEnd`
sees Leggetid still locking at 14:30. After that it shows "Leggetid · Til 18:00". The lock itself is right
throughout (both rules, the intersection of their modes). Giving the copy Skole's kind would change its icon and its
silence (a bedtime lets alarms ring), and dropping Skole's window would loosen the lock. Neither is acceptable. The
sheet says it before the tap: "Skole gjelder allerede til 14:30" (§6.2). Q1 asks the user to accept this.

### 4.3 Pauses and the copy

- **"Alle regler"** pauses the copy with no extra work: `rule_id: null` lifts every rule on the phone.
- **A pause of the rule that is on** ("Sett Skole på pause" while Skole is on) is also served as a **twin lift** for
  the copy: `{id: -(lift.id), target: "rule", rule_id: -(lock.id), minutes, expires_at_ms}`, the same expiry.
  `lock_now::twin_lifts` adds it in `build_time_policy`. The twin is bounded by the same `expires_at_ms`, so it never
  outlives the real pause even when the phone sees it later (`updateLiftRecords` takes the earlier of the two). The
  phone reports the twin's id in `lifts_active`; `LiftState::of_running_pause` looks only at the real id, which the
  phone runs too.
- **Turning a rule on ends what would lift it at once** (§6.4): the running pauses of that rule and an "Alle regler"
  pause are ended early in the same transaction (`ended_early_at`), and a running restrictions pause or override is
  ended by queuing `end_windows`.

### 4.4 Time, the zone and DST

- **The zone** is the latest report's `time_state.zone`, parsed as a `chrono_tz::Tz`. Without one (no report yet, or
  an unreadable zone) "Slå på nå" is not offered. Its segment is disabled with the line "Venter på at telefonen sier
  hvilken tidssone den er i", and the route refuses with the same words. Pause still works. rc.8 always sends the zone.
- **The lengths are wall-clock time, like the launcher's windows.** "2 t" from 16:30 is "til 18:30" on the phone's
  clock. The server computes the end as a local time (`now_local + h`), never as an instant plus hours. That keeps the
  served window and `ends_at` the same on the two nights a year when the clock moves: from 01:30 on the autumn night,
  "2 t" ends at the first 03:30, which is 3 h later. That is the accepted cost, and it falls only in the night hours.
- **`ends_at`** comes from `(local_date, end_minute)` in the zone with the launcher's rule: a time in a gap moves later
  by the gap, and a time in an overlap takes the earlier offset (`java.time`'s `atZone`; `lock_now::instant_of`, unit
  vectors for Europe/Oslo's two change days). 1439 is the next day's 00:00.
- **Never past midnight.** 1, 2 and 3 t are offered only when they end by 23:59 local (`disabled` otherwise). "Ut dagen"
  is always offered. Til must be later than the current local minute and at most 23:59, and 00:00 is read as "Ut
  dagen", as in the rule editor.
- **A zone change while it runs** (the kid travels) moves the window with the phone's clock, as for every rule. The
  server stops serving at `ends_at`, computed in the old zone, and the phone picks that up at its next sync. This is
  rare, and the lock is never lost early by more than the zone difference.

### 4.5 Building it

`build_time_policy` (`device_api.rs:613`) gets the rows from `lock_now::served_rule_rows(db, device, now)`: the
effective rows, plus the copy as a `TimeRuleRow` at the index from §4.2. Its lifts are `deliverable_lifts` plus
`twin_lifts`. The **badge** readers (`devices.rs:385`, `:2121`, `device_pages.rs:742`, `schedules.rs:1892`) read
`served_rule_rows` too, so `named_by` names the copy ("Skole") and `provable_end` can show "until 18:30". Every other
reader (lifts, Attention, counts, the Settings lists) keeps `effective_rule_rows`. The copy is not a rule of the
phone, and "3 rules" stays 3.

### 4.6 The phone offline

| Case | What happens |
|---|---|
| Offline when the parent starts it | Served at the next sync if that is before `ends_at`, from that moment until the end. The state line says "Venter på telefonen". |
| Offline when it ends | The phone ends it by itself (its own window). The server stops serving it. |
| Ended early or shortened while offline | The phone keeps the longer window until it syncs. The state line says "Avsluttet · venter på telefonen" or "Ny tid sendt · venter på telefonen". The Parent PIN lifts it on the phone. |
| **Offline 7 days or more** | **The cached copy locks again in the same window on the same weekday, and every week, until a sync.** `TimeRule` has no date, and the server's later policy can't reach the phone. A lift can't help: it is timed from first sight, and the cache drops lifts (`PolicyGate.kt:192`). |

The last row is the gap the brief asked about. **No field the launcher already honours can bound a rule to a date.**
The only shapes `spanOn` knows are weekly windows. A wrong-size `days` locks *always* (fail closed). Lifts are
relative and not cached. How it fails matters: the phone locks with the rule's own mode, so calls and exempt apps
follow the rule, and emergency calls always work. It happens only on a phone that has reached no server for a week,
the Parent PIN lifts it, and any sync clears it. Q2 asks whether to queue a small launcher change: a `date` on
`TimeRule`, which an older launcher ignores and a newer one honours.

## 5. Routes and handlers

New module `server/src/lock_now.rs` (pure parts: the window, `instant_of`, `copy_first`, `twin_lifts`, the state
lines; and the reads) and `handlers/rules_now.rs`. Each route needs full sign-in, is listed in `settings_log::NOT_SAVES`
(it logs its own event), and returns to `/devices/{id}?done=<code>#quick`. A refusal adds `&open=rules`, so the sheet
renders open with its reason (§6.5).

| Route | Form | Does |
|---|---|---|
| `POST /devices/{id}/rules-now` | `mode=on\|pause`, `rule` (an id, or `all` for pause), `on_len=60\|120\|180\|day`, `til=HH:MM` (wins when not empty), `pause_minutes=15\|30\|60\|120` | `pause`: the shared core of `create_lift` (`lifts::create_rule_lift`, the same checks, row, event and nudge). `on`: §5.1. |
| `POST /devices/{id}/lock-now/{lock_id}` | `add=30\|60\|day` or `til=HH:MM`, `seen=<ends_at as shown>` | Extends or shortens the running lock (§5.2). |
| `POST /devices/{id}/lock-now/{lock_id}/end` | | "Avslutt nå" (§5.3). |

The existing `/lifts` (the Mer skjermtid sheet posts `target=budget` there), `/lifts/{id}/end`, `/start-pause` and
`/end-windows` stay. An older open tab's forms keep working. One action per form, chosen by `mode` in the handler,
because a hidden second submit button would still be the form's default for Enter in the Til field.

### 5.1 Turning a rule on

1. The phone exists, its zone reads (§4.4), and the rule is among `effective_rule_rows`. Otherwise:
   `done=on_no_zone` / `gone`.
2. The window: `start = floor(now_local)`, and the end from `til` or `on_len`. A refused window gives `til_past`,
   `til_invalid` or `past_midnight`, with nothing written.
3. One transaction:
   - `INSERT INTO time_lock_now (...) SELECT ... WHERE NOT EXISTS (SELECT 1 FROM time_lock_now WHERE device_id = ? AND
     ended_early_at IS NULL AND ends_at > datetime('now'))`. If 0 rows were written, answer `done=on_busy` ("Emma har
     allerede Skole på til 18:30") and write nothing more. SQLite serialises the writers, so of two parents tapping at
     once exactly one wins.
   - `UPDATE time_lifts SET ended_early_at = datetime('now') WHERE device_id = ? AND target = 'rule' AND (rule_id IS
     NULL OR rule_id = ?) AND ended_early_at IS NULL AND expires_at > datetime('now')`: the pauses that would lift it
     end now.
4. If the latest report shows the restrictions pause, the override or Play install mode (the condition behind today's
   Lock again, `RelockCard::lock_again`), queue `end_windows` (`window_relock::queue_own_command`). On a launcher
   without `window_relock_v1` that can't be done; the sheet says so before the tap (§6.2).
5. Security log `time_lock_now`: "phone 3: Skole on 16:30-18:30" (`security::Detail::words(..).rule(..)`). Nudge.
   `done=on`.

### 5.2 Extend or shorten

`UPDATE time_lock_now SET end_minute = ?, ends_at = ?, changed_at = datetime('now'), changed_by = ? WHERE id = ? AND
device_id = ? AND ended_early_at IS NULL AND ends_at > datetime('now') AND ends_at = ?seen`. If 0 rows change, the
handler reads the row again: a lock that is over gives `over`, one changed by someone else gives `changed_elsewhere`
("Endret av en annen forelder: nå til 19:00"), and an unknown id is a 404. The new end follows §4.4: later than now,
never past midnight, and `add=day` means 23:59. Logged as `time_lock_now_changed`, then a nudge. `done=on_changed`.

### 5.3 End now

`UPDATE ... SET ended_early_at = datetime('now'), ended_by = ? WHERE ... AND ended_early_at IS NULL AND ends_at >
datetime('now')`. If 0 rows change: `over` for a known row, 404 for an unknown one. Logged as `time_lock_now_ended`,
then a nudge. `done=on_ended`.

## 6. Templates and CSS

### 6.1 Quick Actions (`#quick`)

From top to bottom:

1. **While the phone is open for setup** (the latest report's `paused_at`, or Play install mode) there is one row
   first, `#setup-open`: "Åpnet for oppsett til 17:10" (en "Open for setup until 17:10") with the sub-line "Apper,
   Innstillinger og Play Butikk er åpne", and the row action **Lås igjen** (a form to `/end-windows`). The time is
   the answer time of the newest `start_pause` + 2 h when that started it (`window_relock::setup_until`). Otherwise
   there is no time ("Åpnet for oppsett", which is the Parent PIN's pause). Play install mode reads "Play Butikk åpen
   for installering". An older launcher keeps today's `pause_ends_old` line and no action. This row replaces the
   `.quick-note` and the Lock again tile. Attention's "Begrensninger satt på pause" goes on landing on `#quick`, and
   now finds it first.
2. The notes that stay: `report_age` and `asked`.
3. **`.qa-grid`**: two equal columns in one row, `grid-template-columns: repeat(2, minmax(0, 1fr))`. Each tile is
   `<details class="qa-sheet-wrap" id="more-time|rules">` whose `<summary class="qa">` holds a `.tile` glyph (Mer
   skjermtid indigo with a clock-plus, Regler graphite with a lock, the mockup's `G_TIME`/`G_LOCK`), `.qa-label` and
   `.qa-sub` (the state line, §7). There is no "…" on any tile. `.qa-sheet-wrap[open]` spans both columns, and the
   tile shows `.qa-chosen` while open. The Regler tile is not rendered when `effective_rule_rows` is empty.
4. **Today** (`lifts::today`) as now, plus the day's on-locks (§8): the running one with Avslutt nå.

The bell: the phone's page is a sub-page (`band::sub`), so it has no bell. Tab pages keep it last, top right. Nothing
here touches the band.

### 6.2 The Regler sheet (no JS needed)

The sheet is a `<div class="group qa-sheet">`. Its head is `.row.qa-head`: the tile, "Regler" and **Lukk**, a
`<button type="button" data-confirm-cancel hidden>`. `confirm.js` already closes any `<details>` it sits in. Without JS
it is hidden, and a tap on the tile closes the sheet. Then, top to bottom:

**A. What runs** (only when something does), each in its own form, before the main form:

- An on-lock: "Skole på til 18:30", with the sub-line "Startet 16:30 · på telefonen" (or "venter på telefonen"). Then
  `.row-stacked` "Forleng eller forkort" with `.segmented` `add` (+30 min, +1 t, Ut dagen; a segment that would pass
  midnight is `disabled`), a Til row (`<input type="time" name="til">`, empty, with the current end as `placeholder`),
  the hidden `seen`, and the button "Lagre ny tid". Then a separate form with **Avslutt nå** (`.qa-danger`).
- Each running pause: "Leggetid på pause til 21:00" with **Avslutt nå** (today's `/lifts/{id}/end`).

**B. The main form** (`/rules-now`):

1. **Regel**: a list of radio rows, `name="rule"`, the phone's rules in `effective_rule_rows` order, but with the first
   school rule first and checked. Bedtime comes last, as "Leggetid". **"Alle regler"** is the last row, inside a
   `.when-pause` wrapper.
2. **Hva**: two radios, `mode=on` (id `m-on`) and `mode=pause` (`m-pause`). They are **direct children of the form,
   placed before every panel** and visually hidden. Their faces are `<label for>`s in a `.segmented` ("Slå på nå",
   "Sett på pause"). `m-on` is checked, unless an on-lock runs: then it is `disabled`, `m-pause` is checked, and a line
   says "Avslutt Skole først for å slå på en annen regel". The same applies with no zone (§4.4).
3. **Hvor lenge**: two panels. `.when-on` holds `on_len` (1 t, 2 t, 3 t, Ut dagen; past-midnight ones `disabled`;
   2 t checked, else the longest enabled, else Ut dagen) and a Til row ("I dag, aldri over midnatt"). `.when-pause`
   holds `pause_minutes` (15 min, 30 min, 1 t, 2 t; 1 t checked).
4. **The line on what happens**, one `.row-note` per mode. For on: the rule's mode in words ("Som i Skole: anrop med
   kontakter og Meldinger virker. Nødanrop virker alltid."), built from the rule (`lock_now::mode_line`). Then, when
   they apply, `.qa-warn` lines: "Telefonen er åpnet for oppsett til 17:10. Det avsluttes når du slår på." / "Skole er
   på pause til 17:00. Pausen avsluttes." / "Skole gjelder allerede til 14:30." (§4.2) / an older launcher: "Åpningen
   kan ikke avsluttes herfra; regelen begynner når den er over." For pause: "Regelen kommer tilbake av seg selv, eller
   med Avslutt nå."
5. **One button**, `type="submit"`, in each panel: "Slå på" in `.when-on` and "Sett på pause" in `.when-pause`.

**How the panels switch without JS** (the coordinator's question): sibling selectors on the hoisted radios. There is no
`:has()`, so this works on Chrome 80 and iOS 14:

```css
.qa-mode { position: absolute; opacity: 0; width: 1px; height: 1px; pointer-events: none; }
.when-on, .when-pause { display: none; }
#m-on:checked ~ .when-on, #m-pause:checked ~ .when-pause { display: block; }
#m-on:checked ~ .qa-hva .face-on, #m-pause:checked ~ .qa-hva .face-pause { background: var(--surface); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); }
#m-on:focus-visible ~ .qa-hva .face-on, #m-pause:focus-visible ~ .qa-hva .face-pause { outline: 2px solid var(--tint); outline-offset: -2px; }
```

The faces copy `.segment-face`'s look; only "checked" moves to the hoisted selectors. A panel's inputs keep their own
names, so the server reads only the chosen mode's fields. A hidden panel's checked radio is ignored. "Alle regler"
picked and then Slå på chosen is refused as `on_needs_rule`, and the sheet opens with "Velg én regel for Slå på".

**With JS**, `static/rules-sheet.js` (ES5, deferred, about 80 lines, `jstest/rules-sheet.test.js`):

- It writes each button as the full sentence: "Slå på Skole til 18:30" / "Sett Skole på pause i 1 t" / "Sett alle
  regler på pause i 1 t". The end is computed from the server-rendered phone-local now (`data-now-minute`) and the
  choice. The words come from `partials/js_words.html`.
- A tap on a length segment fills Til. Typing in Til unchecks the length segments.
- Switching to Slå på while "Alle regler" is checked checks the first rule.

Without JS the generic buttons stay, and the server's flash after the tap says the full sentence ("Skole er slått på
til 18:30").

### 6.3 The Mer skjermtid sheet

Head "Mer skjermtid i dag" with Lukk. `.row-stacked` "Hvor mye" with `.segmented` `minutes` (15 min, 30 min checked,
1 t). The note "Kommer i tillegg til dagens grense. Brukt i dag: 1 t 12 min av 2 t." (from `time_state::screen_time`;
without a current report, only the first sentence). The button "Gi ekstra tid", which JS turns into "Gi 30 min ekstra".
It posts `target=budget` to `/lifts`, unchanged.

### 6.4 Interaction rules

- One on-lock per phone. The rule can't be switched while it runs: end it, then turn another on.
- Turning a rule on ends the restrictions pause or override (`end_windows`), the running pauses of that rule, and an
  "Alle regler" pause, in the same tap. The sheet says which before the tap.
- A pause started later lifts the on-lock as it lifts the rule: "Alle regler" through `rule_id: null`, the same rule
  through its twin (§4.3). When the pause ends inside the window, the lock is back.
- "Åpne for oppsett" started while an on-lock runs lifts it until the opening is over (the launcher's override comes
  first), as for every rule. Its sheet says so ("Skole på til 18:30 står på pause så lenge").
- An on-lock and a scheduled rule both lock (the intersection of their modes). The card's order is §4.2.
- Every restriction stays liftable: Avslutt nå, an "Alle regler" pause, Åpne for oppsett, and on the phone the Parent
  PIN.

### 6.5 Where forms return (the page must never jump to the top)

Every form posts to `#quick`, and `scroll-restore.js` keeps the place, as for every form on the summary. A refusal
redirects with `&open=rules` (or `more-time`), the server renders that `<details open>`, and its flash is the sheet's
first row (`role="status"`, `flash-error`). A success closes the sheet, and the flash goes in Today's group as now.
`done_flash` gets the new codes: `on`, `on_changed`, `on_ended`, `on_busy`, `changed_elsewhere`, `on_no_zone`,
`on_needs_rule`, `til_past`, `til_invalid`, `past_midnight`; `over` and `gone` are reused.
`tests::time_pages::every_form_on_the_time_pages_returns_to_its_group` takes the new forms.

### 6.6 Innstillinger › Avansert › Åpne for oppsett

A new group follows the Innstillinger group on the summary, with the header **Avansert** (`#setup`). It holds one row,
`<details class="act-sheet row-sheet" id="setup-sheet">` whose summary is a `.row` with the label "Åpne for oppsett",
a chevron, and, while open for setup, the value "til 17:10". It opens the same sheet pattern: head "Åpne for oppsett"
+ Lukk; the note "Apper, Innstillinger og Play Butikk åpnes på telefonen til Emma i 2 t. Den låses igjen av seg selv,
eller med Lås igjen." (plus the on-lock line in §6.4 when one runs); and the button "Åpne i 2 t" (`.qa-danger`). It
posts to `/start-pause`. The 2 h stays fixed, so there is no length choice and no launcher change. It is shown only
for a `window_relock_v1` phone with no pause running, as today. `window_relock::flash("start_pause_sent")` moves to
the group `setup`, and the "Sent…" line shows in the group. `auto-reload.js` behaves as today. The About and Advanced
rows below it stay as they are (Q3 is about the double "Avansert").

### 6.7 CSS

`style.css` gets `.qa-grid`, `.qa`, `.qa-label`, `.qa-sub`, `.qa-chosen`, `.qa-on`, `.qa-sheet`, `.qa-head`,
`.qa-warn` and `.qa-danger`, from the approved mockup (`build.py`'s style block), plus §6.2's mode rules and
`.row-sheet`. The tokens are the existing ones (light and dark). `.act-grid`, `.act` and `.act-sheet` stay while
another page uses them (`grep` at build); otherwise they go.

## 7. The state lines

All are in the reader's language, and the times are the phone's local clock as stored. A phone whose zone differs
from Phone Setup's adds "(telefonens tid)".

| Tile | Line, in priority order |
|---|---|
| Mer skjermtid | "48 min igjen" (budget + extra − used, from a report of today); "Brukt opp i dag"; "Ingen grense i dag" (unlimited); nothing without a current report. |
| Regler | An on-lock: "Skole på til 18:30" (and "· venter" until a report after its start or change shows it). Else the latest-ending running pause: "Leggetid på pause til 21:00" / "Alle regler på pause til 17:00". Else nothing. `.qa-on` while something runs. |
| Avansert row | "til 17:10" while open for setup (§6.1). |

On-lock states (`lock_now::State`, like `LiftState`): **Waiting** (no report after `created_at`/`changed_at`),
**OnPhone** (a report after it shows `active_rule_id == -id`, or the twin or "all" pause it explains), **Over**, and
**EndedEarly(at)**. After End now there is "Avsluttet · venter på telefonen" until a report shows no copy.

## 8. History and the inbox

Today's lifts make security events and Activity rows, and no inbox event or push. This design does the same:

- `security_events`: `time_lock_now`, `time_lock_now_changed` and `time_lock_now_ended`, worded
  `security.event.*`/`security.detail.lock_now.*`, with the rule as a rule parameter (`Detail::rule`).
- Activity (`handlers/activity.rs`, beside `lift_row`): every on-lock that is no longer running is an
  `ActivityRow::LockNow`, ordered by `created_at` within the week and the 10-row cap. It reads "Skole slått på 16:30-18:30"
  (en "Skole on 16:30-18:30"), with the value "Mamma · Sluttet 18:30" / "Avsluttet 17:10" / "Regelen ble fjernet".
- The summary's Today lists today's on-locks the same way, and the running one with Avslutt nå.
- No new push. No inbox event.

## 9. What doesn't change

- **The launcher.** No new key, no capability, no new behaviour. rc.8 shows the copy as a rule, the card with name,
  icon and Til, and the pause.
- **The policy's shape.** A copy is a `rules[]` entry with today's keys, and a twin is a `lifts[]` entry.
  `policy_json_keys_snapshot` (`server/src/tests/device_api.rs:315`) and the launcher's `PolicyResponseCompatTest` stay
  as they are; no contract file changes. Only the values are new (a negative `id` and `rule_id`), and the DTOs already
  take a `Long`.
- The kid's "Be om mer tid" and its answers, the daily limit, the rule editor and the Settings lists.

## 10. i18n

Every new word goes in both `en.json` and `nb.json` (`i18n::tests` keeps them equal). About 45 keys under
`device.summary.qa.*`, `device.summary.rules_now.*`, `device.summary.setup.*`, `device.summary.flash.*`, `events` and
`security.*`. The nb words are the user's: Regler, Slå på nå, Sett på pause, Hvor lenge, Til, Ut dagen, Forleng eller
forkort, Lagre ny tid, Avslutt nå, Mer skjermtid, Hvor mye, Åpne for oppsett, Avansert, Lås igjen. The en words:
Rules, Turn on now, Pause, How long, Until, Rest of the day, Extend or shorten, Save new time, End now, More screen
time, How much, Open for setup, Advanced, Lock again. The button sentences take parameters ("Slå på {rule} til {at}" /
"Turn on {rule} until {at}", "Sett {rule} på pause i {duration}" / "Pause {rule} for {duration}"). The JS gets its
words through `partials/js_words.html`. `tests::i18n_pages::nb_pages_have_no_english` reads the summary with a running
on-lock.

## 11. Risks

- **The week-offline relock** (§4.6): stricter than wanted, on the same weekday's hours, until a sync. The Parent PIN
  lifts it. The parent isn't told on the phone page today; the "last seen" line is the hint. Q2 is the real fix.
- **The card without a time** while a stronger scheduled rule ends first (§4.2). The lock is right; the sheet warns.
- **A copy the phone has but the server ended** (End now offline) lasts to the window's end. It is liftable on the
  phone, and the PWA says "venter på telefonen".
- **`time_state.zone` wrong or stale** (a zone change after the last report): the window lands at the wrong wall time
  by the zone difference. The same is true of every rule's "until" in the PWA today.
- **A negative id in a place that assumes positive ids.** Checked: the launcher's lift matching, `key()`, the report's
  `active_rule_id`, the server's `TimeReport` and the intake `Field::Int`. Code QA should grep both sides again for
  `id > 0`.
- **The two-mode sheet without JS** reads well but says only "Slå på" on the button. The flash after it says the full
  sentence.

## 12. Tests (lean)

- `lock_now::tests` (pure): the window from (now, zone, choice) — 1/2/3 t, Ut dagen, Til, 00:00 as Ut dagen, Til ≤ now,
  past midnight refused, `disabled` lengths; `instant_of` on Europe/Oslo's spring and autumn days (the gap moves later,
  the overlap takes the earlier offset) against hand-written instants; `copy_first` vectors (same kind ending
  earlier or later, another kind); the served entry (`id`, one weekday, `end: 0` for 23:59, `name: ""` for a bedtime)
  and `twin_lifts`.
- `tests::lock_now` (one file, the seeded family): start → the policy has the copy with Skole's mode, a running Skole
  and an "all" pause are ended, and `end_windows` is queued when the report shows a pause; a second start is
  `on_busy` with nothing written; a change with a stale `seen` is `changed_elsewhere`; End now → no copy, and a second
  End now → `over`; `ends_at` set into the past → no copy; the rule switched off → no copy; a pause of Skole → its
  twin in `lifts`; the summary's badge names "Skole" and shows "until 18:30"; the tile is hidden without rules; the
  Regler sheet renders open with the reason after a refusal; `policy_json_keys_snapshot` unchanged (it runs anyway).
- `jstest/rules-sheet.test.js`: the button sentences, Til from a segment, and the "Alle regler" switch.
- Existing tests to update: the summary's Quick Actions (`tests::step10`, `summary::*`) and the forms-return table.

## 13. Steps

1. **The model and the policy** (migration 0142, `lock_now.rs` pure parts and reads, `served_rule_rows`,
   `build_time_policy`, the badge readers, retention) with `lock_now::tests`. Nothing is visible yet; the policy is
   unchanged while the table is empty.
2. **The routes** (`rules_now.rs`, `create_rule_lift` taken out of `create_lift`, `NOT_SAVES`, security events) with
   `tests::lock_now`'s route cases.
3. **The page** (Quick Actions as two tiles and their sheets, the open-for-setup row, Innstillinger › Avansert,
   `rules-sheet.js`, CSS, the words, Activity and Today) with the page tests and the jstest. A browser pass on jelly36
   size, light and dark, with JS and without.
4. **The notes**: `docs/notes/server/time-rules.md` ("Slå på nå"), `pwa.md` (the summary's Quick Actions), and
   `server/CLAUDE.md` only if its index needs a line.

## 14. Open questions

- **Q1** (§4.2): while a stronger scheduled rule runs (Skole to 14:30) and a weaker rule is turned on past it (Leggetid
  to 18:00), the kid's card shows "Skole" with no time until 14:30, then "Leggetid · Til 18:00". The lock is right;
  only the card's time is missing for those hours, and the sheet warns before the tap. Accept?
- **Q2** (§4.6): a phone that gets the copy and then reaches no server for 7 days locks again in that window each
  week until it syncs (the Parent PIN lifts it). Accept, or queue a small launcher change (a `date` on `TimeRule`)?
  Older launchers would still have the gap until they update.
- **Q3** (§6.6): the new group's header "Avansert" sits just above the existing "Avansert" page row. Keep both, or
  name the page row something else (for example "Mer om telefonen")?
