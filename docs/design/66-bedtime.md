# Design 66: Bedtime as its own setting; day rules stay inside one day

Round L6, item 3 (PLAN.md, "Round L6 - the time-rule screen"). Server only: the launcher needs no change (§3.4).
The rest of round L6 (the rule screen's look, no code entry on it) is not this note.

## 1. Why

The rule editor says "An end before the start ends the next morning (21:00 to 07:00); the same start and end lasts
24 hours" (`server/templates/schedule_rule.html:72`). The user found that confusing (2026-10-26): "Start må være før
slutt", and "burde leggetid være en egen input-greie ... leggetid som 1 faktor, ikke 7". So a bedtime is one setting
per phone, entered once, and it alone may run past midnight; every other rule starts and ends on the same day.

## 2. The parent's view

**Device › Time** (`device_time.html`) gets a **Bedtime** group between Recent and Rules, one row, because the
bedtime holds whatever the Own rules switch says (§3.1):

```
BEDTIME
| Bedtime        Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00   > |
Every night on the phone's clock, with Own rules on or off.
```

With no bedtime the row says "Not set". The row opens the **Bedtime page** (`GET /devices/{id}/bedtime`,
`device_bedtime.html`, tab Devices like Device › Time), one form shaped like the rule editor: Save in the sticky
top bar, the save's flash and a refusal's reason there.

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
  natt til søndag): a split every Norwegian parent knows, with no calendar behind it. Sunday night is a school night.
  Two fixed rows beat a "Different on weekends" switch: nothing hidden, no script, no state the parent can't see.
- **Defaults**: no bedtime until the parent adds one. A phone without one opens the page with both rows filled in
  as 21:00-07:00, Calls with contacts on, no exceptions, and the button says **Add** (nothing is stored before that,
  like New Rule). A new phone starts with no bedtime.
- **Settings › Time Rules** (`schedules.html`): each phone's row in the Phones group gets the bedtime as its
  sub-line ("Bedtime Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00" or "No bedtime"), so every bedtime can be seen on one
  page. There is no "everyone's bedtime" (§6 Q1).
- **Summary words** (`bedtime_summary`): "Every night 21:00-07:00" when the rows are equal, "Sun-Thu 21:00-07:00 ·
  Fri-Sat 22:30-09:00", "Sun-Thu nights 21:00-07:00" when the other row is empty.
- **Wording.** The PWA is English only today (`lang="en"`). The same words in `nb` for the phone and for a later
  Norwegian PWA: Bedtime = **Leggetid**; "Sun-Thu nights" = **Søn-tor**; "Fri-Sat nights" = **Fre-lør**; the footer
  = "Fra kveld til morgen, på telefonens klokke. Fredag og lørdag er kveldene før en fridag. La en rad stå tom for
  ingen leggetid de kveldene."; Remove Bedtime = **Fjern leggetid**.
- **The ordinary rule editor** (`schedule_rule.html`): the ⓘ panel's text goes. The footer becomes "On the phone's
  clock, from the start to the end of the same day; 00:00-23:59 is the whole day. Leave a day empty when the rule
  is off. A night past midnight goes in Bedtime, on each phone's Time page." The Type select offers School and
  Other; Bedtime shows only on a stored rule of that kind (a day rule kept from before, so a save keeps its kind).

**Forms and returns** (`docs/notes/server/pwa.md`, saves never jump to the top): the save posts to
`/devices/{id}/bedtime#bedtime` and returns 303 to `/devices/{id}/bedtime?saved=1#bedtime` (the form, Save at its
top, as a rule's save). Remove posts to `/devices/{id}/bedtime/remove` and returns to
`/devices/{id}/time?notice=bedtime_removed#bedtime`, like a rule's delete back to its list
(`settings_pages::leaves_the_page`). A refusal renders the page again (400) as typed, the reason under its row,
`autofocus` and `aria-invalid`, and in the top bar. An unknown device is a 404, a DB error a 500. A save nudges the
phone and writes `bedtime_saved` / `bedtime_removed` to the security log.

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

Why a row and not a table of four times: lifts (`time_lifts.rule_id`), Pause a Rule's picker, the phone's
`time_state.active_rule_id`, the badge (`time_state::rule_days`) and the security log all work on rule ids already.
A row with a real id keeps all of them working.

**Per phone, outside the rule sets.** `time_rules::effective_rule_rows` returns the rule set in force (everyone's,
or the phone's own while Own rules is on) **plus** the phone's bedtime row, last. Everyone's rules and the Own
Rules list leave out `bedtime = 1` rows. The Own rules switch never adds or drops the bedtime.

### 3.2 The policy: no contract change

The bedtime goes to the phone as **one more `time_policy.rules[]` entry in today's shape**: `{id, name: "", kind:
"bedtime", calls_allowed, exempt_apps, days}` with `start > end` windows. The phone has read rules that cross
midnight since design 06 (`TimeRules.kt` `spanOn`, `activeSpan` checks today's and yesterday's occurrence). So the
policy keys don't change, and neither do `policy_json_keys_snapshot`, `PolicyResponseCompatTest` or any
`testdata/` file. A new field would mean a contract change in both directories for nothing the phone can't already
do. The empty name is on purpose (§3.4).

**Served windows** (`time_rules::served_days`, used by `TimeRuleRow::to_policy` and `time_state::rule_days` so the
phone and the badge agree): an ordinary window ending at 23:59 is served with `end: 0`, which the phone reads as
"until midnight": `{start, 23:59}` becomes `{start, 0}`, and `{00:00, 23:59}` becomes `{0, 0}`, 24 h. Otherwise
the phone (end exclusive) would leave every all-day or to-the-end-of-the-day rule open from 23:59 to 00:00. A
stored rule that already ends at 23:59 locks one minute more from now on.

### 3.3 Validation

- **Ordinary rules** (`parse_rule_form`): both times `HH:MM` in 00:00-23:59 and **end > start**, else
  `RuleError::Day(i, "{Day}: the end must be after the start. For a night, use Bedtime.")`. `days_summary` drops
  "for 24 h" and the next-morning case. Only the form checks this: `parse_days` (the policy read) still accepts any
  window, so an older or restored row is served, never a 500 (§3.5 converts them at startup).
- **Bedtime** (`parse_bedtime_form`): in each row both times or neither, `HH:MM`; the evening in 12:00-23:59, the
  morning in 00:00-11:59 ("Bedtime starts after noon and ends before noon"). That keeps every window `start > end`
  and under 24 h, and leaves out the shapes that read badly ("bedtime from 01:00"; one that ends at 13:00). Both
  rows empty is refused: "Give the times, or remove the bedtime." The exempt apps are checked as a rule's (at most
  50, package names).

### 3.4 What the phone shows

The time-rule screen and the glance's rule card take their title from `ruleLabel(kind, name)`
(`ui/TimeLockModel.kt`): the rule's own name, else the kind's label. A blank name with kind `bedtime` gives
`lock_kind_bedtime`: **"Leggetid"** (nb) / **"Bedtime"** (en). `RuleCardTest` pins that already
(`ruleLabel(KIND_BEDTIME, "")`), and launchers from before r13 did the same (`LockActivity.kindLabel`). So the
server sends the bedtime with `name: ""` and the phone names it in its own language, with no launcher change. The
glyph is the moon (`RuleCardKind.BEDTIME`) and line 2 the existing "Til 07:00" (`lockEnd`). `lock_reason` is
`BEDTIME`.

The server's side: `time_state::lock_name` reads an empty `active_rule_name` as no name, so the badge falls back to
"Bedtime" from `lock_reason` (today it would show an empty name). The lift's `rule_name` and the picker read the row's
stored name "Bedtime".

### 3.5 Existing rules that cross midnight: converted at startup

**Decision: convert, don't refuse.** A parent with a 21:00-07:00 rule should find it as the phone's Bedtime, locking
exactly as before. A rule that was refused until edited would keep the old wording on the PWA and keep the editor's
crossing case alive. Converting can be exact minute for minute, so the phone locks the same either way; then
nothing that crosses midnight is left outside the bedtime.

`time_rules::migrate_crossing(db)`, called from `connect_db` right after `migrate_legacy` (`main.rs:1573`), one
transaction per phone and one for everyone's rules. It needs no marker: it runs at every start and finds nothing once
done, which also catches a restored older backup. For each rule with `bedtime = 0`:

1. **Normalise, in place** (the same lock): a window ending at 00:00 becomes `{start, 23:59}` (served `{start, 0}`,
   §3.2); a 24 h window at 00:00 (`{0, 0}`) becomes `{0, 23:59}`. Legacy "(evening)" rules end up here and stay
   day rules.
2. **Bedtime-shaped?** Kind `bedtime` or `custom` (never school); every window crosses with start >= 12:00 and end <
   12:00; days 6, 0-3 equal, days 4-5 equal, not all null.
3. **Per phone, from the set in force**: Own rules on → its own rules; off → everyone's. The first bedtime-shaped
   rule in that set (kind `bedtime` before `custom`, then `sort_order`, id) becomes its bedtime. If it is the phone's
   own, the row is changed in place (`bedtime = 1`, kind `bedtime`, name "Bedtime"; the id, calls and apps kept). If
   it is everyone's, it is copied into each phone that follows everyone's rules, and those phones' lifts that are
   still delivered are moved to the copy (`UPDATE time_lifts SET rule_id = <copy> WHERE device_id = ? AND rule_id =
   <old>`). Once copied, the global row is deleted.
4. **Everything else that still crosses** (a second bedtime-shaped rule, a school rule, odd days, a 24 h window not
   at 00:00, every crossing rule in a set no phone follows) is **split exactly**: the row keeps its id, its day
   windows, and each crossing day D as `{start, 23:59}`; a new row "<name> (morning)" (cut to 60 characters) gets
   `{0, end}` on day D+1, with the same kind, calls and apps.

Each conversion writes `time_rule_to_bedtime` or `time_rule_split` to the security log (no actor). What the phone sees
change: a converted custom rule now shows "Leggetid" and the moon instead of its own name (§6 Q3), and it ranks
as a bedtime (school > bedtime > custom). A pause that was running on a split rule's morning part ends at the
upgrade. A phone on Own rules that later goes back to everyone's no longer picks up a global bedtime it once
followed; it keeps its own. Both are rare, and accepted.

## 4. Edge cases

- **A bedtime and a day rule overlapping** (a custom "Skjermfri" 20:00-21:30, bedtime from 21:00): both are active
  21:00-21:30. `decideTimeLock` takes the strongest kind for the title (bedtime over custom), allows calls only if
  both do, and leaves usable only the apps both exempt. This is today's rule, unchanged.
- **A bedtime ending after a day rule starts** (bedtime to 07:30, school from 07:15): school wins the title from
  07:15. The card's "Til" is school's end, because `lockEnd` checks that nothing locks at the end.
- **DST**: windows are the phone's wall clock (`LocalDateTime`), so bedtime ends at 09:00 on the clock on both DST
  nights; the night is an hour shorter or longer in real time. A morning time inside the spring gap (02:30) ends at
  the jump. The PWA's summaries show the times as typed, without a DST note. The badge already refuses a "Til" in a
  gap or overlap (`Zone::single_instant`).
- **"Ask for more time" at bedtime** (design r13 D3): unchanged. The request says "Bedtime" (`state: rule`,
  `lock_reason` BEDTIME), and the parent can pause Bedtime (it is in Pause a Rule's picker) or add minutes, which
  don't lift a rule. Rules converted from custom now say "Bedtime" instead of "A time rule".
- **A pause that outlasts the evening time**: a pause is a lift on the rule's id for its whole length, active or
  not. Pausing Bedtime (or All rules) for 2 h at 20:30 with bedtime at 21:00 keeps the phone open until 22:30. A
  save of the bedtime's times keeps a running pause (same id). Remove Bedtime deletes the row, and the phone ignores
  a lift for a rule it no longer has, as for a deleted rule today.
- **Screen-time budget**: bedtime is a rule, so the budget lock waits for it to end, and "Til 07:00" with today's
  budget used up stays (r13). Music plays at bedtime only as an exempt app (design 21 §3), now set on the Bedtime
  page.
- **Zones**: there are no zone-based rules. Design 64's zones are events only, and the phone judges no zone.
- **The phone's DE call blocks** (`boot_call_blocks`) take a calls-off bedtime as they take any crossing no-calls
  window today.

## 5. Steps

Both steps on one branch (`bedtime`), merged together: after step 1 alone, a converted bedtime would have no page.

**Step 1 - model, serving, conversion, the day-rule form (server, M).**
`migrations/0101_bedtime.sql`; `src/time_rules.rs` (`TimeRuleRow.bedtime`, `served_days`, `to_policy`'s empty
name, `effective_rule_rows` with the phone's bedtime, `migrate_crossing`); `src/main.rs` (the call after
`migrate_legacy`); `src/handlers/schedules.rs` (`parse_rule_form`'s end > start, `days_summary`, the Type select's
options, the lists without `bedtime = 1`); `templates/schedule_rule.html` (footer, ⓘ gone); `src/time_state.rs`
(`rule_days` from `served_days`, the empty name in `lock_name`); `migrations_73_to_100_in_order` → `_101`;
`server/CLAUDE.md` (the next free number 0102).

**Step 2 - the Bedtime page (server, S).**
`src/handlers/bedtime.rs` (show, save, remove; `parse_bedtime_form`, `bedtime_summary`); the three routes in the
admin router; `templates/device_bedtime.html` (head partial, reuses the editor's classes); `device_time.html` (the
Bedtime group, `notice=bedtime_removed`); `schedules.html` (the phone rows' sub-line);
`docs/notes/server/time-rules.md` (rules, wire, Admin UI, "Where forms return"); PLAN.md (the item's state).

**Launcher: nothing.** No code or string changes. `TimeRulesTest` covers crossing windows, ends at 00:00 (the
legacy minute-by-minute cases) and `{0, 0}`; `RuleCardTest` covers the blank-name bedtime label.

**Tests to pin** (server only; no form script, so no jstest):
- `time_rules::tests::crossing_rules_convert_exactly`: for a set of rule sets (everyone's 21:00-07:00 every night;
  Sun-Thu 21:00-07:00 + Fri-Sat 22:30-09:00; a legacy "(evening)" to 00:00; a 24 h Saturday; a school rule
  22:00-02:00; odd days; two bedtime-shaped rules), the lock served before and after `migrate_crossing` is equal
  minute by minute over a week (the `new_locked` checker on served days), and the expected rows are bedtime / split.
- `migrate_crossing` on a database: everyone's rule copied into the phones that follow it and deleted, an Own-rules
  phone's rule converted in place with its id, lifts repointed, and a second run changes nothing.
- `served_days`: `{s, 23:59}` → `{s, 0}`, `{0, 23:59}` → `{0, 0}`, the bedtime untouched.
- `parse_rule_form` refuses end <= start (equal and earlier) under the day's field, and takes 00:00-23:59;
  `parse_bedtime_form`'s ranges, one empty row allowed, both refused; the days built (4, 5 = Fri-Sat).
- The policy (`tests::time_rules`): a phone on everyone's rules gets those rules plus `{name: "", kind: "bedtime"}`,
  and switching Own rules on keeps the bedtime; the keys snapshot unchanged.
- Pages (`tests::time_pages`): the redirect table rows for save / remove / refusal (`assert_lands`), the
  forms-land check picks up the new page by itself; the badge says "Bedtime" for an empty `active_rule_name`.

## 6. Open questions for the user

1. **Per phone, or with the rule set like the Daily Limit?** Default: **per phone, always** (the user's "per
   phone"; children's bedtimes differ by age). The cost: a new phone starts with none, and Settings › Time Rules
   shows each phone's bedtime instead of one for everyone.
2. **Holidays.** Friday and Saturday are the only free-day nights; nothing knows about vinterferie, juleferie or
   the eve of a public holiday. Default: **no calendar**. The parent changes the times or pauses Bedtime that
   evening (a pause is at most 2 h today).
3. **The name on the phone.** Default: a bedtime is always "Leggetid" on the phone, also when it was converted from
   a rule the parent named ("Natt" becomes "Leggetid"). The alternative is to keep a converted rule's own name and
   send it.
