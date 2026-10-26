# Design 66: Bedtime as its own setting; day rules stay inside one day

Revised 2026-10-26 after `docs/review/qa-66-design.md` (every finding applied).

Round L6, item 3 (PLAN.md, "Round L6 - the time-rule screen"). Server only: the launcher needs no change (§3.4). The
rest of round L6 (the rule screen's look, no code entry on it) is not this note.

## 1. Why

The rule editor says "An end before the start ends the next morning (21:00 to 07:00); the same start and end lasts
24 hours" (`server/templates/schedule_rule.html:72`). The user found that confusing (2026-10-26): "Start må være før
slutt", "burde leggetid være en egen input-greie ... leggetid som 1 faktor, ikke 7". So a bedtime is one setting per
phone, entered once, and it alone may run past midnight; every other rule starts and ends on the same day.

## 2. The parent's view

**Device › Time** (`device_time.html`) gets a **Bedtime** group between Recent and Rules, one row because the bedtime
holds whatever the Own rules switch says (§3.1): `Bedtime  Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00  >`, footer
"Every night on the phone's clock, with Own rules on or off." With no bedtime the row says "Not set". The row opens
the **Bedtime page** (`GET /devices/{id}/bedtime`, `device_bedtime.html`, tab Devices like Device › Time), one form
shaped like the rule editor: Save in the sticky top bar, the save's flash and a refusal's reason there.

```
< Time                                                    Save
Bedtime
| Sun-Thu nights          21:00  -  07:00 |
| Fri-Sat nights          22:30  -  09:00 |
From evening to morning, on the phone's clock. Fri-Sat are the
nights before Saturday and Sunday. Leave a row empty for no
bedtime on those nights.
| Calls with contacts                 (on) |
The phone is locked during bedtime. Emergency calls always work.
EXCEPTIONS
| Apps that still work              Musikk > |      (the rule editor's <details>, inside the form)
They must also be allowed under Apps on the phone.

| Remove Bedtime...                         |      (a <details> confirm outside the form)
```

- **Two rows, always shown, no switch.** "Nights before a free day" = Friday and Saturday evenings (natt til lørdag,
  natt til søndag): a split every Norwegian parent knows, with no calendar behind it; Sunday night is a school night.
  Two fixed rows beat a "Different on weekends" switch: nothing hidden, no script, no state the parent can't see.
- **Defaults**: a new phone has no bedtime until the parent adds one. A phone without one opens the page with both
  rows filled in as 21:00-07:00, Calls with contacts on, no exceptions, and the button says **Add** (nothing is stored
  before that, like New Rule). Add and Save are the same single upsert (below).
- **Settings › Time Rules** (`schedules.html`): each phone's row in the Phones group gets the bedtime as its
  sub-line ("Bedtime Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00" or "No bedtime"), so every bedtime can be seen on one
  page. There is no "everyone's bedtime" (§6 Q1).
- **Summary words** (`bedtime_summary`): "Every night 21:00-07:00" when the rows are equal, "Sun-Thu 21:00-07:00 ·
  Fri-Sat 22:30-09:00", "Sun-Thu nights 21:00-07:00" when the other row is empty. A bedtime row whose stored data
  doesn't read (days that don't parse, or days 6 and 0-3 / 4 and 5 not equal; a restored or hand-edited row) says
  **Can't be read** (`text-alarm`) in both places, as `rule_rows` does (`schedules.rs:152-176`); its page opens with
  the rows empty and a warning, and a save rewrites all seven days.
- **Wording.** The PWA is English only today (`lang="en"`). For a later Norwegian PWA: Bedtime = **Leggetid**;
  "Sun-Thu nights" = **Søn-tor**; "Fri-Sat nights" = **Fre-lør**; Remove Bedtime = **Fjern leggetid**; the footer =
  "Fra kveld til morgen, på telefonens klokke. Fredag og lørdag er kveldene før en fridag. La en rad stå tom for ingen
  leggetid de kveldene."
- **The ordinary rule editor** (`schedule_rule.html`): the ⓘ panel's text goes. The footer becomes "On the phone's
  clock, from the start to the end of the same day; 00:00-23:59 is the whole day. Leave a day empty when the rule
  is off. A night past midnight goes in Bedtime, on each phone's Time page." The Type select offers School and
  Other; Bedtime shows only on a stored rule of that kind (a day rule kept from before, so a save keeps its kind).
  It never handles the phone's `bedtime = 1` row (§3.3).

**Forms and returns** (`docs/notes/server/pwa.md`, saves never jump to the top): the save posts to
`/devices/{id}/bedtime#bedtime` and returns 303 to `/devices/{id}/bedtime?saved=1#bedtime` (Save at the form's top, as
a rule's save). Remove posts to `/devices/{id}/bedtime/remove` and returns to
`/devices/{id}/time?notice=bedtime_removed#bedtime`, like a rule's delete (`settings_pages::leaves_the_page`). A
refusal renders the page again (400) as typed, the reason under its row, `autofocus` and `aria-invalid`, and in the top
bar. An unknown device is a 404, a DB error a 500. A save nudges the phone and writes `bedtime_saved` /
`bedtime_removed` to the security log. The save is one statement, `INSERT INTO time_rules (...) VALUES (...) ON
CONFLICT(device_id) WHERE bedtime = 1 DO UPDATE SET ...` (SQLite takes a partial index as the conflict target), so two
saves of a new bedtime at once can't hit the unique index and give a 500.

## 3. The model

### 3.1 Storage: a flagged `time_rules` row

Migration `0101_bedtime.sql`:

```sql
ALTER TABLE time_rules ADD COLUMN bedtime INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_time_rules_bedtime ON time_rules(device_id) WHERE bedtime = 1;
```

A phone's bedtime is the row with `bedtime = 1` and `device_id = <phone>`: kind `bedtime`, name "Bedtime",
`calls_allowed`, `exempt_apps_json`, and `days_json` built from the two rows: Sun-Thu on days 6, 0, 1, 2, 3, Fri-Sat
on days 4 and 5 (a window that starts on day D runs into D+1, so Friday's entry is the night to Saturday). An empty
row is `null` on its days. The page reads the times back from days 0 and 4.

Why a row, not a table of four times: lifts (`time_lifts.rule_id`), Pause a Rule's picker, `active_rule_id`, the badge
(`time_state::rule_days`) and the security log all work on rule ids already.

**Per phone, outside the rule sets: two readers** in `time_rules.rs`. `rule_set_rows(db, Option<device>)` is the rule
set alone, `AND bedtime = 0` (`None` = everyone's, `Some(d)` = the phone's own). `effective_rule_rows(db, device,
custom)` = `rule_set_rows` of the set in force (everyone's, or the phone's own while Own rules is on) **plus** the
phone's `bedtime = 1` row, last (its Own branch, `SELECT * ... WHERE device_id = ?` at `time_rules.rs:418`, would
otherwise return the bedtime twice). **Without the bedtime**: everyone's list (`schedules.rs:266`, now `None`) and the
Own Rules list (`schedules.rs:1329`, now `Some(id)`, feeding `rule_rows`). **With it** (calls unchanged): the policy
(`device_api.rs:566`), lifts (`lifts.rs:97`), the badge (`devices.rs:268`), the summary (`devices.rs:1522`), attention
(`attention.rs:488`) and Device › Time's effective set, which feeds Pause a Rule's picker (`schedules.rs:1319`). The
Own rules switch never adds or drops the bedtime. `bedtime = 1` is set only on a device row (the unique index doesn't
cover a NULL `device_id`).

### 3.2 The policy: no contract change

The bedtime goes to the phone as **one more `time_policy.rules[]` entry in today's shape**: `{id, name: "", kind:
"bedtime", calls_allowed, exempt_apps, days}` with `start > end` windows. The phone has read rules that cross
midnight since design 06 (`TimeRules.kt` `spanOn`, `activeSpan`). So the policy keys, `policy_json_keys_snapshot`,
`PolicyResponseCompatTest` and every `testdata/` file stay. The empty name is on purpose (§3.4).

**Served windows** (`time_rules::served_days`, used by `TimeRuleRow::to_policy` and `time_state::rule_days` so the
phone and the badge agree): every window ending at 23:59 is served with `end: 0`, which the phone reads as "until
midnight": `{start, 23:59}` becomes `{start, 0}`, and `{00:00, 23:59}` becomes `{0, 0}`, 24 h. It ignores the
bedtime flag: no bedtime ends at 23:59 (its morning is before 12:00). Otherwise the phone (end exclusive) would leave
every all-day rule open from 23:59 to 00:00. A stored rule that already ends at 23:59 locks one minute more from now
on, and its badge reads "00:00 tomorrow" (`time_state.rs:287`), not "23:59".

**Older launchers.** One without `time_rules_v1` gets only the frozen legacy columns (`device_api.rs:252-285`), so the
Bedtime never reaches it; Device › Time already warns about it (`schedules.rs:1401`). D1 removes that path later.

### 3.3 Validation

- **Ordinary rules** (`parse_rule_form`): both times `HH:MM` in 00:00-23:59 and **end > start**, else
  `RuleError::Day(i, "{Day}: the end must be after the start. For a night, use Bedtime.")`; the one exception is
  23:59-23:59, taken as "23:59 to midnight" (what §3.5 stores for such a window, served `{23:59, 0}`), so a stored
  row of that shape saves unchanged. `days_summary` drops "for 24 h" and the next-morning case. Only the form checks
  this: `parse_days` (the policy read) still accepts any window, so an older or restored row is served, never a 500
  (§3.5 converts them at startup).
- **The rule editor never handles the bedtime row.** `rule_in_scope` (`schedules.rs:1011-1027`, relied on by
  `update_rule` and `delete_rule`) treats a `bedtime = 1` row as another scope's: 404, so day windows can never be
  saved into it. `show_rule` (`schedules.rs:696-708`) answers it with a 303 to `/devices/{id}/bedtime`, since a row
  converted in place keeps its id and the parent's old `/schedules/rules/{id}` link (history, bookmark) should land
  where the bedtime now lives.
- **Bedtime** (`parse_bedtime_form`): in each row both times or neither, `HH:MM`; the evening in 12:00-23:59, the
  morning in 00:00-11:59 ("Bedtime starts after noon and ends before noon"). That keeps every window `start > end`
  and under 24 h, and out of shapes that read badly ("bedtime from 01:00"). Both rows empty is refused: "Give the
  times, or remove the bedtime." The exempt apps are checked as a rule's (at most 50, package names).

### 3.4 What the phone shows

The time-rule screen and the glance's rule card take their title from `ruleLabel(kind, name)`
(`ui/TimeLockModel.kt`): the rule's own name, else the kind's label. A blank name with kind `bedtime` gives
`lock_kind_bedtime`: **"Leggetid"** (nb) / **"Bedtime"** (en); `RuleCardTest` pins that (`ruleLabel(KIND_BEDTIME,
"")`), and launchers from before r13 did the same (`LockActivity.kindLabel`). So the server sends `name: ""` and the
phone names it in its own language, with no launcher change. The glyph is the moon (`RuleCardKind.BEDTIME`), line 2
the existing "Til 07:00" (`lockEnd`), `lock_reason` `BEDTIME`.

The server's side needs no change: `TimeReport::parse` already trims an empty `active_rule_name` and drops it
(`time_state.rs:73-77`, pinned at line 465), and `lock_name` then falls back to "Bedtime" from `lock_reason`
(lines 112-130). The lift's `rule_name` and the picker read the stored name "Bedtime".

### 3.5 Existing rules that cross midnight: converted at startup

**Decision: convert, don't refuse.** A parent with a 21:00-07:00 rule finds it as the phone's Bedtime, locking exactly
as before. A refused rule would keep the old wording and the editor's crossing case alive.

`time_rules::migrate_crossing(db)`, called from `connect_db` right after `migrate_legacy` (`main.rs:1573`), before the
server listens. A failure there is fatal (`main.rs:262-280` exits unless a restore is undone), so it is
**idempotent and crash-safe**: no marker, it runs at every start and finds nothing once done (which also catches a
restored older backup); each unit below is **one transaction**, so a stop between two leaves whole units done; every
insert is `... WHERE NOT EXISTS (a bedtime = 1 row for that phone)` and every step skips a phone that already has one,
so the unique index is never hit; a rule whose `days_json` or `exempt_apps_json` doesn't read is **skipped** with a
`tracing::warn!` (it stays as it is, as today's 500 policy; saving it in the editor fixes it). Only an `sqlx::Error`
is returned. "**Crosses**" is read on the stored window: `start >= end` and `end != 23:59` (a stored `{23:59, 23:59}`,
served `{23:59, 0}`, is a one-minute window and never converts again). For each rule with `bedtime = 0`:

1. **Normalise, in place** (the same lock): a window ending at 00:00 becomes `{start, 23:59}` (served `{start, 0}`,
   §3.2); a 24 h window at 00:00 (`{0, 0}`) becomes `{0, 23:59}`. Legacy "(evening)" rules stay day rules.
2. **Bedtime-shaped?** Kind `bedtime` or `custom` (never school); every window crosses with start >= 12:00 and end <
   12:00; days 6, 0-3 equal, days 4-5 equal, not all null.
3. **Per phone** (one transaction each), **from the set in force**: Own rules on → its own rules; off → everyone's.
   A phone's first bedtime-shaped own rule (kind `bedtime` before `custom`, then `sort_order`, id) becomes its bedtime
   in place (`bedtime = 1`, kind `bedtime`, name "Bedtime"; id, calls and apps kept); a phone that already has a
   `bedtime = 1` row gets none, and the rule is split (step 4). **Everyone's rules**, after the phones, one
   transaction **per rule**: the first bedtime-shaped one (same order) is copied into **every** phone that follows
   everyone's rules, those phones' lifts on it are repointed to the copy (`UPDATE time_lifts SET rule_id = <copy>
   WHERE device_id = ? AND rule_id = <old>`: every such lift, ended or not, as `rule_name` is stored on the lift; the
   phone reads `ruleId` at each sync, `ScreenTime.kt:145-148`), and the global row is deleted, all in that
   transaction. If any of those phones already has a `bedtime = 1` row, no phone gets a copy and the rule is split
   instead, so no phone loses a lock.
4. **Everything else that still crosses** (a second bedtime-shaped rule, a school rule, odd days, a 24 h window not
   at 00:00 or 23:59, every crossing rule in a set no phone follows) is **split exactly**: the row keeps its id, its
   day windows, and each crossing day D as `{start, 23:59}`; a new row "<name> (morning)" (cut to 60 characters) gets
   `{0, end}` on day D+1, with the same kind, calls and apps. A crossing `{23:59, x}` becomes `{23:59, 23:59}` on D
   plus `{0, x}` on D+1, exact. A 24 h window at 23:59 (`{23:59, 23:59}`) can't be told from "23:59 to midnight" and
   is read as that: the one conversion that isn't exact, accepted (no parent enters it on purpose).

Each conversion writes `time_rule_to_bedtime` or `time_rule_split` to the security log (no actor); these two and
`bedtime_saved` / `bedtime_removed` get their words in `admin.rs`'s table, next to `time_rule_*` (lines 115-117). What
the phone sees change: a converted custom rule shows "Leggetid" and the moon instead of its own name (§6 Q3) and ranks
as a bedtime (school > bedtime > custom). A pause running on a split rule's morning part ends at the upgrade. A phone
on Own rules that later goes back to everyone's keeps its own bedtime and no longer follows a global one. Both are
rare, and accepted.

## 4. Edge cases

- **Overlap with a day rule** (a custom "Skjermfri" 20:00-21:30, bedtime from 21:00): both are active 21:00-21:30.
  `decideTimeLock` takes the strongest kind for the title, allows calls only if both do, and leaves usable only the
  apps both exempt: today's rule, unchanged. Bedtime to 07:30 and school from 07:15: school wins the title from
  07:15, and the card's "Til" is school's end (`lockEnd` checks that nothing locks at the end).
- **DST**: windows are the phone's wall clock (`LocalDateTime`), so bedtime ends at 09:00 on the clock on both DST
  nights; the night is an hour shorter or longer in real time. A morning time inside the spring gap (02:30) ends at
  the jump. The badge already refuses a "Til" in a gap or overlap (`Zone::single_instant`).
- **"Ask for more time" at bedtime** (design r13 D3): unchanged. The request says "Bedtime" (`state: rule`,
  `lock_reason` BEDTIME; a rule converted from custom no longer says "A time rule"), and the parent can pause Bedtime
  (it is in Pause a Rule's picker) or add minutes.
- **A pause that outlasts the evening time**: a pause is a lift on the rule's id for its whole length, active or
  not. Pausing Bedtime for 2 h at 20:30 with bedtime at 21:00 keeps the phone open until 22:30. A save keeps a
  running pause (same id); Remove deletes the row, and the phone ignores a lift for a rule it no longer has.
- **Screen-time budget**: bedtime is a rule, so the budget lock waits for it to end ("Til 07:00" stays, r13). Music
  plays at bedtime only as an exempt app (design 21 §3), now set on the Bedtime page. No zone-based rules exist
  (design 64's zones are events only). The phone's call blocks (`boot_call_blocks`) take a calls-off bedtime as they
  take any crossing no-calls window today.

## 5. Steps

Both steps on one branch (`bedtime`), merged together: after step 1 alone, a converted bedtime would have no page.

**Step 1 - model, serving, conversion, the day-rule form (server, M).** `migrations/0101_bedtime.sql`;
`src/time_rules.rs` (`TimeRuleRow.bedtime`, `served_days`, `rule_set_rows`, `effective_rule_rows`, `migrate_crossing`);
`src/main.rs` (the call); `src/handlers/schedules.rs` (`parse_rule_form`'s end > start, `days_summary`, the Type select,
the two lists on `rule_set_rows`, `show_rule`'s redirect and `rule_in_scope`'s 404 for a `bedtime = 1` row);
`templates/schedule_rule.html` (footer, ⓘ gone); `src/time_state.rs` (`rule_days` from `served_days`; `lock_name`
stays); `src/handlers/admin.rs` (the four event words); `migrations_73_to_100_in_order` → `_101`; `server/CLAUDE.md`
(the next free number 0102).

**Step 2 - the Bedtime page (server, S).** `src/handlers/bedtime.rs` (show, save as the upsert, remove;
`parse_bedtime_form`, `bedtime_summary`, "Can't be read"); the three routes; `templates/device_bedtime.html`;
`device_time.html` (the group, `notice=bedtime_removed`); `schedules.html` (the phone rows' sub-line);
`docs/notes/server/time-rules.md` (rules, wire, Admin UI, "Where forms return"); PLAN.md.

**Launcher: nothing** (`TimeRulesTest` covers crossing windows and `{0, 0}`, `RuleCardTest` the blank-name label).

**Existing tests that change** (step 1; under `server/src/`): `tests/time_rules.rs:103-116` posts an Own rule 21:00 to
07:00 through the form, which end > start now refuses, and lines 151-153 assert day 6 is `{1260, 420}`: it posts a day
rule and asserts that. The legacy-conversion test (`tests/time_rules.rs:828-845`: `connect_db` twice, `rows.len() ==
3`) stays as the end-to-end check of `migrate_legacy` → `migrate_crossing` and its second run; no phone follows
everyone's rules, so the global "Bedtime" and "Outside allowed hours" are split and the phone's own rule becomes
`bedtime = 1`: its row asserts are rewritten. `handlers/schedules.rs:1507` (`days_summary`) drops "for 24 h".

**Tests to pin** (server only; no form script, so no jstest):
- `time_rules::tests::crossing_rules_convert_exactly`: for a set of rule sets (everyone's 21:00-07:00 every night;
  Sun-Thu 21:00-07:00 + Fri-Sat 22:30-09:00; a legacy "(evening)" to 00:00; a 24 h Saturday; a school rule 22:00-02:00;
  odd days; two bedtime-shaped rules; a `{23:59, 07:00}` window), the lock served before and after `migrate_crossing`
  is equal minute by minute over a week (the `new_locked` checker on served days), the expected rows are bedtime /
  split, and a second run changes nothing (no extra "(morning)" row); the 24 h window at 23:59 is the one difference.
- `migrate_crossing` on a database: everyone's rule copied into every follower, lifts repointed, the global row
  deleted; an Own-rules phone's rule converted in place with its id; a phone that already has a `bedtime = 1` row
  (also as a follower) gets no second row and the rule is split; an unreadable `days_json` row is skipped and the
  start succeeds; a run stopped after the first phone, then run again, ends in the same rows.
- `served_days`: `{s, 23:59}` → `{s, 0}`, `{0, 23:59}` → `{0, 0}`, `{23:59, 23:59}` → `{23:59, 0}`. `parse_rule_form`
  refuses end <= start under the day's field, takes 00:00-23:59 and 23:59-23:59; `parse_bedtime_form`'s ranges, one
  empty row allowed, both refused; the days built (4, 5 = Fri-Sat).
- Policy (`tests::time_rules`): everyone's rules plus one `{name: "", kind: "bedtime"}`, kept once when Own rules goes
  on; the keys snapshot unchanged.
- Pages: `/devices/{id}/bedtime` joins the fixed list in `tests/shell.rs:689-721` (once with a bedtime stored, so the
  Remove form shows), and `["devices", n, "bedtime", "remove"]` joins `leaves_the_page` (`tests/settings_pages.rs:73-100`,
  as the zone delete at line 98). `tests::time_pages`: the redirect rows for save / remove / refusal (`assert_lands`);
  the bedtime row's `GET /schedules/rules/{id}` is a 303 to the Bedtime page, its POST save and delete 404, and neither
  list shows it; a bedtime that can't be read; two saves of a new bedtime end in one row.

## 6. Open questions for the user

**Answered 2026-10-26 (the user): all three on their defaults** - per phone always ("enig i leggetid per telefon alltid"), no
holiday calendar ("ferier er irrelevant, da får man gå inn og oppdatere"), a converted rule is "Leggetid" on the phone ("ok").
Nothing is open; the steps in §5 build as written.

1. **Per phone, or with the rule set like the Daily Limit?** Default: **per phone, always** (the user's "per phone";
   children's bedtimes differ by age). The cost: a new phone starts with none, and there is no one bedtime for all.
2. **Holidays.** Friday and Saturday are the only free-day nights; nothing knows about vinterferie, juleferie or a
   holiday's eve. Default: **no calendar**; the parent changes the times or pauses Bedtime (at most 2 h today).
3. **The name on the phone.** Default: a bedtime is always "Leggetid" on the phone, also when converted from a rule the
   parent named ("Natt" becomes "Leggetid"). The alternative is to keep a converted rule's name and send it.
