# Design 93: "Slå på nå" and Quick Actions as three tiles

Status: design, revision 2, 2026-11-13. It folds in the user's mockup rounds (revision 3 approved) and the user's
change of 2026-11-13: Quick Actions has **three** tiles, **Skjermtid**, **Pause regler** and **Slå på regel**. The two
rule tiles open **one** Regler sheet, with its Hva segment preselected by the tile ("Sett på pause" / "Slå på nå"),
and the segment can still be switched in the sheet. **Server only** (§9): the launcher's rc.8 shows everything.
Migration `0142_time_lock_now.sql` is the next free number on `main` at `58bf42e3` (no local or remote branch has a
0142 either); recheck it at merge.

**Revision 2 (design QA, 2026-11-13)**:
- Three tiles (the user's change): Skjermtid (was Mer skjermtid), Pause regler, Slå på regel; their state lines (§7).
- The sheets are no longer `<details>` inside the tile grid: an open `<details>` spans the grid, which puts the other
  tiles under the sheet (the mockup keeps the tiles in their row), and two tiles can't open one `<details>`. Each tile is
  now a link (`?open=rules&mode=on#quick`) that the server answers with the sheet open below the grid, and that
  `quick-sheets.js` opens in place without a reload (§6.1, §6.2). `auto-reload.js` holds while a sheet is open.
- Regel is the mockup's `.pick` select with "Alle regler" first, not a list of radio rows; the old nested
  `.when-pause` row could never show (a `~` selector reaches only siblings). Every `.when-*` panel is a direct child of
  the form (§6.2). `:focus-visible` replaced by the page's `:focus` / `:focus:not(:focus-visible)` pair (Chrome 80).
- Matched to the mockup: the tile glyphs and colours, "48 min igjen i dag", the "Noe annet" head, the open-for-setup
  row above the Quick Actions header, its sub-line, and the button "Åpne for oppsett i 2 t" (§6).
- §5.1: the report doesn't show the Parent PIN's offline override (only `restrictions_paused` and Play install mode);
  what that means is now said. §6.6: the Avansert row while the phone is open (it said both "hidden" and "til 17:10").
- §4.2: an occurrence from yesterday that runs past midnight counts too. §4.4: a length ending at exactly midnight is
  offered; the autumn hour's repeat. §4.6: an extension the phone hasn't got. §4.1, §4.3: the launcher's own negative
  ids and `maxLiftId` checked. §4.5: `devices.rs:2121`'s read serves more than the badge.
- i18n: the en words for the tiles, the state lines and the warnings (§10). Open question Q4 added (one on-lock at a
  time against the mockup's "Noe annet").

## 1. Verdict and summary

**Buildable server-only, with one residual gap the parent should know about (§4.6, Q2).** A rule turned on now
("Slå på nå", first asked for as "Lås nå") is the server serving one more entry in `time_policy.rules`: a **copy of the chosen
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

Quick Actions becomes three tiles in one row, **Skjermtid**, **Pause regler** and **Slå på regel**, each with a
coloured glyph, its name and a state line. Each opens a sheet below the row in one pattern: title + Lukk, what, how
much or how long, an optional Til, one line on what happens, and one button saying the action in plain words. The
+15/+30/+1 h tiles become the Skjermtid sheet. Pause regler and Slå på regel open the same Regler sheet, preselected
to their own choice. The Finn tile goes (Finn has its own tab). "Lås opp 2 t" moves to a new **Avansert** group under
Innstillinger, as the row **"Åpne for oppsett"**. While the opening runs, a row right under the phone's header shows it
with "Lås igjen".

Size: medium, about 1,450 lines with the tests (§12). Four steps (§13).

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
  `DeviceStatus::paused_at` (`models.rs:570`). The report shows the pause (`restrictions_paused`) and Play install
  mode, not the Parent PIN's offline override (only `offline_override_used`). Attention's "Begrensninger satt på pause" lands on `#quick`
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
  the end, so the launcher's `TimeLock.key()` (`TimeRules.kt:315`, the rules' ids: a changed id would re-apply the
  suspension) and its lift records hold. The launcher's DTO is `Long`, and the server reads `active_rule_id` with
  `as_i64` (`time_state.rs:73`; the intake's `Field::Int`/`Ints`, `device_api.rs:1218-1221`, take any `i64`). The
  launcher's own negative ids (`legacyTimePolicy`'s -1 and -2, `TimeRules.kt:250`) exist only for a policy without
  `time_policy`, so they never meet a copy.
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

- **The copy first** when no rule of the same kind has an occurrence that overlaps the copy's window and ends after
  it. An occurrence from yesterday that runs past midnight counts (Leggetid until 07:00 while the copy runs from
  06:30). Then `until` is the copy's end, and at that end nothing of that kind still locks, so the card shows "Til
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
  phone runs too. A twin is served only beside its real pause, so the policy's largest lift id, which the kid's "Be
  om mer tid" reads (`maxLiftId`, `TimeRequests.kt:171`), is unchanged by it.
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
  "2 t" ends at 03:30, which is 3 h later. That is the accepted cost, and it falls only in the night hours. An end
  inside the repeated hour (01:30 + "1 t" = 02:30) is the first 02:30 for the server; the phone, which compares wall
  times, is inside the window again during the repeat until its next sync drops the copy, exactly as for a scheduled
  rule today, and its card shows no Til there (`lockEnd`'s single-offset check).
- **`ends_at`** comes from `(local_date, end_minute)` in the zone with the launcher's rule: a time in a gap moves later
  by the gap, and a time in an overlap takes the earlier offset (`java.time`'s `atZone`; `lock_now::instant_of`, unit
  vectors for Europe/Oslo's two change days). 1439 is the next day's 00:00.
- **Never past midnight.** 1, 2 and 3 t are offered only when they end by midnight local (`disabled` otherwise); one
  ending at exactly 24:00 is stored as `end_minute` 1439, like "Ut dagen". "Ut dagen" is always offered, except in the
  day's last minute (23:59), when nothing can start and the Slå på segment is disabled with "Dagen er snart over". Til
  must be later than the current local minute and at most 23:59, and 00:00 is read as "Ut dagen", as in the rule
  editor.
- **A zone change while it runs** (the kid travels) moves the window with the phone's clock, as for every rule. The
  server stops serving at `ends_at`, computed in the old zone, and the phone picks that up at its next sync. This is
  rare, and the lock is never lost early by more than the zone difference.

### 4.5 Building it

`build_time_policy` (`device_api.rs:613`) gets the rows from `lock_now::served_rule_rows(db, device, now)`: the
effective rows, plus the copy as a `TimeRuleRow` at the index from §4.2. Its lifts are `deliverable_lifts` plus
`twin_lifts`. The **badge** readers (`devices.rs:385`, `:2121`, `device_pages.rs:742`, `schedules.rs:1892`) read
`served_rule_rows` too, so `named_by` names the copy ("Skole") and `provable_end` can show "until 18:30". The read at
`devices.rs:2121` also feeds the Time row, the rule list for the Regler sheet and the calls-off Attention row: there
only the badge's `rule_days`/`named_by` take the served rows, the rest keep the effective ones. Every other reader
(lifts, Attention, counts, the Settings lists) keeps `effective_rule_rows`. The copy is not a rule of the phone, and
"3 rules" stays 3.

### 4.6 The phone offline

| Case | What happens |
|---|---|
| Offline when the parent starts it | Served at the next sync if that is before `ends_at`, from that moment until the end. The state line says "Venter på telefonen". |
| Offline when it ends | The phone ends it by itself (its own window). The server stops serving it. |
| Ended early or shortened while offline | The phone keeps the longer window until it syncs. The state line says "Avsluttet · venter på telefonen" or "Ny tid sendt · venter på telefonen". The Parent PIN lifts it on the phone. |
| Extended while offline | The phone ends at the old, earlier end and locks again from its next sync until the new end. The state line says "Ny tid sendt · venter på telefonen". |
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
(it logs its own event), and returns to `/devices/{id}?done=<code>#quick`. A refusal adds `&open=rules&mode=<the
mode posted>&rule=<the rule posted>`, so the sheet renders open as it was, with its reason (§6.5).

| Route | Form | Does |
|---|---|---|
| `POST /devices/{id}/rules-now` | `mode=on\|pause`, `rule` (an id, or `all` for pause), `on_len=60\|120\|180\|day`, `til=HH:MM` (wins when not empty), `pause_minutes=15\|30\|60\|120` | `pause`: the shared core of `create_lift` (`lifts::create_rule_lift`, the same checks, row, event and nudge). `on`: §5.1. |
| `POST /devices/{id}/lock-now/{lock_id}` | `add=30\|60\|day` or `til=HH:MM`, `seen=<ends_at as shown>` | Extends or shortens the running lock (§5.2). |
| `POST /devices/{id}/lock-now/{lock_id}/end` | | "Avslutt nå" (§5.3). |

The existing `/lifts` (the Skjermtid sheet posts `target=budget` there), `/lifts/{id}/end`, `/start-pause` and
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
4. If the latest report shows the restrictions pause or Play install mode (the condition behind today's Lock again,
   `RelockCard::lock_again`: `paused_at` or `install_mode_until_ms`), queue `end_windows`
   (`window_relock::queue_own_command`, after the commit). On a launcher without `window_relock_v1` that can't be
   done; the sheet says so before the tap (§6.2). **The Parent PIN's offline override is not in the report** (the
   phone sends only `restrictions_paused` and `offline_override_used`), so the server can't see it. `end_windows`
   would end it too, but it is not queued blind: on a phone with nothing open it still runs a relock check (`WindowRelock.lockAgain`), whose findings can
   then show on the summary for a week. An override typed on the phone keeps the new lock off until it runs out, as it
   does for every rule; the parent who typed it is at the phone.
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

1. **While the phone is open for setup** (the latest report's `paused_at`, or Play install mode) there is one row,
   `#setup-open`, inside `<section id="quick">` but **before its header**, so it sits right under the phone's header
   (the mockup's 6b) and Attention's "Begrensninger satt på pause", which lands on `#quick`, finds it first. It reads
   "Åpnet for oppsett til 17:10" (en "Open for setup until 17:10") with the sub-line "Apper og Play Butikk er åpne"
   (en "Apps and Play Store are open"), a graphite unlock tile, and the row action **Lås igjen** (a form to
   `/end-windows`). The time is the answer time of the newest `start_pause` + 2 h when that started it
   (`window_relock::setup_until`, new). Otherwise there is no time ("Åpnet for oppsett", the Settings switch's pause
   behind the Parent PIN). Play install mode reads "Play Butikk åpen for installering". An older launcher keeps today's
   `pause_ends_old` line and no action. This row replaces the `.quick-note` and the Lock again tile. The header's status
   line says the same ("Åpnet for oppsett til 17:10"), as the mockup shows.
2. The header "Hurtighandlinger", then the notes that stay: `report_age` and `asked`.
3. **`.qa-grid`**: three equal columns in one row, `grid-template-columns: repeat(3, minmax(0, 1fr))`. Each tile is an
   `<a class="qa">` with a `.tile` glyph, `.qa-label` and `.qa-sub` (the state line, §7). There is no "…" on any tile.
   - **Skjermtid**: teal, the mockup's `G_TIME` (a plus in a circle); `href="/devices/{id}?open=time#quick"`.
   - **Pause regler**: indigo, `G_PAUSE`; `href="/devices/{id}?open=rules&mode=pause#quick"`.
   - **Slå på regel**: indigo, `G_LOCK`; `href="/devices/{id}?open=rules&mode=on#quick"`.

   Both rule tiles are not rendered when `effective_rule_rows` is empty; Skjermtid then stands alone in the first
   column. The tile whose sheet is open has `.qa-chosen` and `aria-expanded="true"`. A label wraps at phone width
   (`overflow-wrap: anywhere`), so "Slå på regel" and "Pause regler" fit in a third of 288 px.
4. **The open sheet**, below the grid and outside it: the Skjermtid sheet (§6.3) or the Regler sheet (§6.2). The
   server renders both sheets (for a phone with rules) and gives every one but the `open=` one the `hidden` attribute.
5. **Today** (`lifts::today`) as now, plus the day's on-locks (§8): the running one with Avslutt nå.

**Opening and closing a sheet.** Without JS a tile is a plain link: the page comes back with that sheet open and the
chosen segment checked, and `#quick` keeps the place (`scroll-restore.js` keeps `#quick` where it was on the screen,
so the tiles don't move). Lukk is a link to `/devices/{id}#quick`. With JS, `static/quick-sheets.js` (ES5, deferred)
takes the click on a tile and on Lukk: it shows or hides the sheet in place (the `hidden` attribute), checks the
tile's mode, moves `.qa-chosen` and `aria-expanded`, and moves the focus to the sheet's head (or back to the tile on
Lukk) with `preventScroll`. Nothing reloads, and the address doesn't change. A tap on the open tile closes its sheet.
A tap on the other rule tile while the Regler sheet is open only switches the segment.

`auto-reload.js` must not reload while a sheet is open (what was picked would be lost): its "a `<details>` is open"
check also counts a `.qa-sheet` without `hidden`, with a jstest case. The `<details>` it already counts (the Avansert
row, §6.6) stays a `<details>`.

Why not `<details>` per tile, as the first revision had: an open `<details>` in the grid spans every column, which
puts its own tile across the width and the other tiles below the sheet (the mockup keeps the row), and two tiles
can't open one `<details>`. Two copies of the Regler form, one per tile, would double every id and name.

The bell: the phone's page is a sub-page (`band::sub`), so it has no bell. Tab pages keep it last, top right. Nothing
here touches the band.

### 6.2 The Regler sheet (no JS needed)

The sheet is a `<div class="group qa-sheet" id="rules-sheet" role="region" aria-labelledby=...>`. Its head is
`.row.qa-head`: an indigo tile with the clock glyph, "Regler" and **Lukk** (§6.1). Then, top to bottom:

**A. What runs** (only when something does), each in its own form, before the main form:

- An on-lock: "Skole på til 18:30", with the sub-line "Startet 16:30 · på telefonen" (or "venter på telefonen"), and
  **Avslutt nå** (`.qa-danger`, its own form) as the row's action, as in the mockup. Then `.row-stacked` "Forleng
  eller forkort" with `.segmented` `add` (+30 min, +1 t, Ut dagen; a segment that would pass midnight is `disabled`),
  a Til row (`<input type="time" name="til">`, empty, with the current end as `placeholder`; "I dag, aldri over
  midnatt"), the hidden `seen`, and the button "Lagre ny tid".
- Each running pause: "Leggetid på pause til 21:00" with **Avslutt nå** (today's `/lifts/{id}/end`).

When section A shows, the main form below it is a group of its own with the head **"Noe annet"** + Lukk (the mockup's
5).

**B. The main form** (`/rules-now`), its children in this order:

1. **The hoisted mode radios**: `mode=on` (id `m-on`) and `mode=pause` (`m-pause`), direct children of the form,
   before everything else, visually hidden (`.qa-mode`). The server checks the one in the address's `mode` (the
   tile's). `m-on` is `disabled` while an on-lock runs ("Avslutt Skole først for å slå på en annen regel", Q4), with
   no zone ("Venter på at telefonen sier hvilken tidssone den er i", §4.4) and in the day's last minute; then `m-pause`
   is checked whatever the tile.
2. **Regel**: a `.row` with the label "Regel" and the page's `<select class="pick" name="rule">` (the mockup's
   "Skole ⇕", today's Pause a rule… picker). Its options: **"Alle regler"** (`value="all"`) first, as the mockup's 3
   shows, then the phone's rules in `effective_rule_rows` order, with Leggetid last. Selected: "Alle regler" when the
   sheet opens in pause mode, else the first school rule (else the first rule); a refusal's `rule=` wins.
3. **Hva**: `.qa-hva`, a `.segmented` of two `<label for>` faces, "Slå på nå" (`for="m-on"`) and "Sett på pause"
   (`for="m-pause"`).
4. **Hvor lenge**, two panels, each a direct child of the form: `.when-on` holds `on_len` (1 t, 2 t, 3 t, Ut dagen;
   past-midnight ones `disabled`; 2 t checked, else the longest enabled, else Ut dagen) and the Til row ("I dag,
   aldri over midnatt"). `.when-pause` holds `pause_minutes` (15 min, 30 min, 1 t, 2 t; 1 t checked).
5. **The line on what happens**, one `.row-note` per mode, again direct children (`.when-on`, `.when-pause`). For on:
   the rule's mode in words ("Telefonen låses som i Skole: anrop med kontakter, Meldinger og Musikk virker. Nødanrop
   virker alltid."), built from the rule (`lock_now::mode_line`). Then, when they apply, `.qa-warn` lines:
   "Telefonen er åpnet for oppsett til 17:10. Det avsluttes når du slår på Skole." / "Skole er på pause til 17:00.
   Pausen avsluttes." / "Alle regler er på pause til 17:00. Pausen avsluttes." / "Skole gjelder allerede til 14:30."
   (§4.2) / an older launcher: "Åpningen kan ikke avsluttes herfra; regelen begynner når den er over." The warnings
   name the rule selected when the page was drawn; JS rewrites them on a change of rule. For pause: "Reglene kommer
   tilbake av seg selv, eller med Avslutt nå. «Alle regler» finnes bare for pause."
6. **One button** per panel, `type="submit"`, each a direct child: "Slå på" (`.when-on`) and "Sett på pause"
   (`.when-pause`).

**How the panels switch without JS**: sibling selectors on the hoisted radios. There is no `:has()` and no
`:focus-visible`, so this works on Chrome 80 and iOS 14. A `~` selector reaches only the radios' later **siblings**,
so every mode-dependent element is a direct child of the form; nothing mode-dependent sits inside a wrapper.

```css
.qa-mode { position: absolute; opacity: 0; width: 1px; height: 1px; pointer-events: none; }
.when-on, .when-pause { display: none; }
#m-on:checked ~ .when-on, #m-pause:checked ~ .when-pause { display: block; }
#m-on:checked ~ .qa-hva .face-on, #m-pause:checked ~ .qa-hva .face-pause { background: var(--surface); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); }
#m-on:disabled ~ .qa-hva .face-on { opacity: 0.4; cursor: default; }
#m-on:focus ~ .qa-hva .face-on, #m-pause:focus ~ .qa-hva .face-pause { outline: 2px solid var(--tint); outline-offset: -2px; }
#m-on:focus:not(:focus-visible) ~ .qa-hva .face-on, #m-pause:focus:not(:focus-visible) ~ .qa-hva .face-pause { outline: none; }
```

The faces copy `.segment-face`'s look; only "checked" moves to the hoisted selectors (the focus pair is
`style.css:3385-3386`'s). A panel's inputs keep their own names, so the server reads only the chosen mode's fields; a
hidden panel's checked radio is ignored. One action per form, chosen by `mode` in the handler, because a hidden second
submit button would still be the form's default for Enter in the Til field. Without JS "Alle regler" stays in the
select in both modes: "Alle regler" with Slå på is refused as `on_needs_rule`, and the sheet comes back open in on mode
with "Velg én regel for Slå på".

**With JS**, `quick-sheets.js` (about 120 lines with §6.1's part, `jstest/quick-sheets.test.js`):

- It writes each button as the full sentence: "Slå på Skole til 18:30" / "Sett Skole på pause i 1 t" / "Sett alle
  regler på pause i 1 t". The end is computed from the server-rendered phone-local now (`data-now-minute`) and the
  choice. The words come from `partials/js_words.html`.
- In on mode the "Alle regler" option is `hidden` and `disabled`; switching to on while it is selected selects the
  first school rule (else the first rule). Switching to pause shows it again and changes nothing else.
- A tap on a length segment fills Til. Typing in Til unchecks the length segments.
- A change of rule rewrites the warnings' rule name, and hides "Skole gjelder allerede…" for a rule it doesn't fit (the
  server renders one `data-already` per rule).

Without JS the generic buttons stay, and the server's flash after the tap says the full sentence ("Skole er slått på
til 18:30").

### 6.3 The Skjermtid sheet

Head: a teal tile, "Skjermtid" and Lukk. `.row-stacked` "Hvor mye" with `.segmented` `minutes` (15 min, 30 min
checked, 1 t). The note "Kommer i tillegg til dagens grense. Brukt i dag: 1 t 12 min av 2 t." (from
`time_state::screen_time`; without a current report, only the first sentence). The button "Gi ekstra tid", which JS
turns into "Gi 30 min ekstra". It posts `target=budget` to `/lifts`, unchanged.

### 6.4 Interaction rules

- One on-lock per phone (Q4). The rule can't be switched while it runs: end it, then turn another on.
- Turning a rule on ends the restrictions pause and Play install mode (`end_windows`, §5.1 step 4), the running pauses
  of that rule, and an "Alle regler" pause, in the same tap. The sheet says which before the tap.
- A pause started later lifts the on-lock as it lifts the rule: "Alle regler" through `rule_id: null`, the same rule
  through its twin (§4.3). When the pause ends inside the window, the lock is back.
- "Åpne for oppsett" started while an on-lock runs lifts it until the opening is over (the launcher's override comes
  first), as for every rule. Its sheet says so ("Skole på til 18:30 står på pause så lenge").
- An on-lock and a scheduled rule both lock (the intersection of their modes). The card's order is §4.2.
- Every restriction stays liftable: Avslutt nå, an "Alle regler" pause, Åpne for oppsett, and on the phone the Parent
  PIN.

### 6.5 Where forms return (the page must never jump to the top)

Every form posts to `#quick`, and `scroll-restore.js` keeps the place, as for every form on the summary; a tile's link
and Lukk without JS do the same (§6.1). A refusal redirects with `&open=rules&mode=..&rule=..` (or `open=time`), the
server renders that sheet open, and its flash is the sheet's first row after the head (`role="status"`,
`flash-error`). A success closes the sheet, and the flash goes in Today's group as now. `done_flash` gets the new
codes: `on`, `on_changed`, `on_ended`, `on_busy`, `changed_elsewhere`, `on_no_zone`, `on_needs_rule`, `til_past`,
`til_invalid`, `past_midnight`; `over` and `gone` are reused. An unknown `open=` or `mode=` is ignored (all sheets
closed, the tile's default). `tests::time_pages::every_form_on_the_time_pages_returns_to_its_group` takes the new
forms.

### 6.6 Innstillinger › Avansert › Åpne for oppsett

A new group follows the Innstillinger group on the summary, with the header **Avansert** (`#setup`), for a
`window_relock_v1` phone (an older one has no group, as it has no "Lås opp 2 t" today). It holds one row,
`<details class="act-sheet row-sheet" id="setup-sheet">` whose summary is a `.row` with a graphite unlock tile, the
label "Åpne for oppsett" and a chevron. It opens the same sheet pattern right under the row: head "Åpne for oppsett" +
Lukk (`data-confirm-cancel`: `confirm.js` already closes the `<details>` it sits in; without JS it is hidden and the
row closes it). Then:

- **Not open**: the note "Apper, Innstillinger og Play Butikk åpnes på telefonen til Emma i 2 timer, så du kan sette
  opp noe der. Den låses igjen av seg selv, eller med Lås igjen." (plus the on-lock line in §6.4 when one runs), and
  the button "Åpne for oppsett i 2 t" (`.qa-danger`), posting to `/start-pause`.
- **Open** (the condition of §6.1's row): the row's value is "til 17:10" (or nothing without a time), and the sheet
  holds "Åpnet for oppsett til 17:10" and **Lås igjen** (`/end-windows`) instead of the open button.

The 2 h stays fixed, so there is no length choice and no launcher change. `window_relock::flash("start_pause_sent")`
moves to the group `setup`, and the "Sent…" line shows in the group; `end_windows_sent` stays in `quick` (§6.1's row
is where Lås igjen is usually tapped). `auto-reload.js` behaves as today. The About and Advanced rows below it stay as
they are (Q3 is about the double "Avansert").

### 6.7 CSS

`style.css` gets `.qa-grid`, `.qa`, `.qa-label`, `.qa-sub`, `.qa-chosen`, `.qa-on`, `.qa-sheet`, `.qa-head`,
`.qa-mode`, `.qa-hva`, `.qa-warn` and `.qa-danger`, from the approved mockup (`build.py`'s style block; its 6-column
grid becomes `repeat(3, ...)`), plus §6.2's mode rules and `.row-sheet`. The tile colours are the existing
`tile-teal`, `tile-indigo` and `tile-graphite`, and the tokens the existing ones (light and dark). `.act-grid`, `.act`
and `.act-sheet` stay while another page uses them (`grep` at build); otherwise they go.

## 7. The state lines

All are in the reader's language, and the times are the phone's local clock as stored. A phone whose zone differs
from Phone Setup's adds "(telefonens tid)".

| Tile | Line, in priority order |
|---|---|
| Skjermtid | "48 min igjen i dag" (budget + extra − used, from a report of today); "Brukt opp i dag"; "Ingen grense i dag" (unlimited); nothing without a current report. |
| Pause regler | The latest-ending running pause: "Leggetid på pause til 21:00" / "Alle regler på pause til 17:00". Else nothing. `.qa-on` while one runs. |
| Slå på regel | An on-lock: "Skole på til 18:30" (and "· venter" until a report after its start or change shows it). Else nothing. `.qa-on` while it runs. |
| Avansert row | "til 17:10" while open for setup (§6.6). |

A tile with no line keeps the line's height, so the three tiles stay level.

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
`security.*`. The nb words are the user's: Skjermtid, Pause regler, Slå på regel, Regler, Regel, Hva, Slå på nå, Sett
på pause, Hvor lenge, Til, Ut dagen, Noe annet, Forleng eller forkort, Lagre ny tid, Avslutt nå, Hvor mye, Åpne for
oppsett, Avansert, Lås igjen, Lukk, Alle regler. The en words: Screen time, Pause rules, Turn on rule, Rules, Rule,
What, Turn on now, Pause, How long, Until, Rest of the day, Something else, Extend or shorten, Save new time, End now,
How much, Open for setup, Advanced, Lock again, Close, All rules.

The sentences, nb / en (parameters in braces):

| Where | nb | en |
|---|---|---|
| Skjermtid's line | {left} igjen i dag / Brukt opp i dag / Ingen grense i dag | {left} left today / Used up today / No limit today |
| Pause regler's line | {rule} på pause til {at} / Alle regler på pause til {at} | {rule} paused until {at} / All rules paused until {at} |
| Slå på regel's line | {rule} på til {at} · venter | {rule} on until {at} · waiting |
| Buttons | Slå på {rule} til {at} / Sett {rule} på pause i {duration} / Sett alle regler på pause i {duration} / Gi {duration} ekstra / Åpne for oppsett i 2 t | Turn on {rule} until {at} / Pause {rule} for {duration} / Pause all rules for {duration} / Give {duration} extra / Open for setup for 2 h |
| Setup row | Åpnet for oppsett til {at} · Apper og Play Butikk er åpne | Open for setup until {at} · Apps and Play Store are open |
| Warnings | Telefonen er åpnet for oppsett til {at}. Det avsluttes når du slår på {rule}. / {rule} gjelder allerede til {at}. / {rule} er på pause til {at}. Pausen avsluttes. | The phone is open for setup until {at}. That ends when you turn on {rule}. / {rule} already runs until {at}. / {rule} is paused until {at}. The pause ends. |
| Refusals | Velg én regel for Slå på / Emma har allerede {rule} på til {at} / Endret av en annen forelder: nå til {at} | Pick one rule to turn on / {name} already has {rule} on until {at} / Changed by another parent: now until {at} |
| Waiting | Avsluttet · venter på telefonen / Ny tid sendt · venter på telefonen | Ended · waiting for the phone / New time sent · waiting for the phone |

The phone's name is a parameter wherever the table says Emma. The JS gets its words through
`partials/js_words.html`. `tests::i18n_pages::nb_pages_have_no_english` reads the summary with a running on-lock and
with each sheet open (`?open=`).

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
- **The two-mode sheet without JS** reads well but says only "Slå på" on the button, and keeps "Alle regler" in the
  select in on mode (refused with a reason). The flash after it says the full sentence.
- **The Parent PIN's offline override** is invisible to the server (§5.1): a lock turned on while it runs starts when
  it runs out.

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
  twin in `lifts`; the summary's badge names "Skole" and shows "until 18:30"; the rule tiles are hidden without
  rules; `?open=rules&mode=pause` renders the sheet with `m-pause` checked and "Alle regler" selected, `mode=on` with
  `m-on` and the school rule; the Regler sheet renders open with the reason, the posted mode and rule after a
  refusal; `policy_json_keys_snapshot` unchanged (it runs anyway).
- `jstest/quick-sheets.test.js`: a tile opens its sheet in place and checks its mode, Lukk closes it, the button
  sentences, Til from a segment, and the "Alle regler" switch. `jstest/auto-reload.test.js`: one case, no reload while
  a `.qa-sheet` is shown.
- Existing tests to update: the summary's Quick Actions (`tests::step10`, `tests::summary`) and the forms-return
  table.

## 13. Steps

1. **The model and the policy** (migration 0142, `lock_now.rs` pure parts and reads, `served_rule_rows`,
   `build_time_policy`, the badge readers, retention) with `lock_now::tests`. Nothing is visible yet; the policy is
   unchanged while the table is empty.
2. **The routes** (`rules_now.rs`, `create_rule_lift` taken out of `create_lift`, `NOT_SAVES`, security events) with
   `tests::lock_now`'s route cases.
3. **The page** (Quick Actions as three tiles and their sheets, `?open=`/`mode=`, the open-for-setup row,
   Innstillinger › Avansert, `quick-sheets.js`, the `auto-reload.js` check, CSS, the words, Activity and Today) with
   the page tests and the jstests. A browser pass on jelly36
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
- **Q4** (§6.4): one on-lock per phone at a time. While Skole is on, "Slå på nå" is disabled in the sheet ("Avslutt
  Skole først…"); the mockup's 5 shows the "Noe annet" form with both segments. Allowing a second on-lock means two
  copies, two state lines and two rows of Forleng; the lock would be their intersection. Keep one at a time?
